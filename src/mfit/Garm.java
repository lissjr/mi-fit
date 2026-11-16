package com.hanxing.mifit;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 佳明中国直传：SSO 登录 → OAuth1 授权 → multipart 上传 FIT */
public class Garm {
    private static final String GK = "fc3e99d2-118c-44b8-8ae3-03370dde24c0";
    private static final String GS = "E08WAR897WEy2knn7aFBrvegVAf0AFdWBBF";
    private static final String UA = "com.garmin.android.apps.connectmobile";

    public static String host = "garmin.cn";              // garmin.cn / garmin.com
    public static String api = "https://connectapi.garmin.cn";
    private static String sso = "https://sso.garmin.cn";
    private static String mig = "https://mobile.integration.garmin.cn/gcm/android";
    private static String loc = "zh_CN";

    public static void setRegion(boolean cn) {
        if (cn) {
            host = "garmin.cn"; api = "https://connectapi.garmin.cn";
            sso = "https://sso.garmin.cn"; mig = "https://mobile.integration.garmin.cn/gcm/android";
            loc = "zh_CN";
        } else {
            host = "garmin.com"; api = "https://connectapi.garmin.com";
            sso = "https://sso.garmin.com"; mig = "https://mobile.integration.garmin.com/gcm/android";
            loc = "en_US";
        }
    }

    /** 把旧版明文令牌一次性换成密文（幂等，已是密文就跳过） */
    public static void migrate(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("mfit", Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor ed = sp.edit();
        boolean changed = false;
        for (String k : new String[]{"gtoken", "go1", "gos", "acct_u", "acct_p"}) {
            String v = sp.getString(k, "");
            if (v.length() == 0) continue;
            if (Vault.open(v).length() > 0) continue;        // 已经是密文
            String s = Vault.seal(v);
            if (s.length() > 0) {
                ed.putString(k, s);
                changed = true;
            }
        }
        if (changed) ed.apply();
    }

    /** 读加密字段；旧版明文原样返回，下次写入自动换成密文 */
    private static String read(android.content.SharedPreferences sp, String k) {
        String v = sp.getString(k, "");
        if (v.length() == 0) return v;
        String p = Vault.open(v);
        return p.length() > 0 ? p : v;
    }

    public static String token(Context c) {
        return read(c.getSharedPreferences("mfit", Context.MODE_PRIVATE), "gtoken");
    }

    private static String lO1, lOS;      // login 临时暂存 OAuth1，随 saveToken 一起落盘

    public static void saveToken(Context c, String t) {
        android.content.SharedPreferences.Editor ed =
                c.getSharedPreferences("mfit", Context.MODE_PRIVATE).edit();
        ed.putString("gtoken", Vault.seal(t)).putBoolean("gcn", host.endsWith(".cn"));
        if (lO1 != null && lOS != null) {                 // 登录顺手把 OAuth1 存下来，供自动续期
            ed.putString("go1", Vault.seal(lO1)).putString("gos", Vault.seal(lOS));
            lO1 = null; lOS = null;
        }
        ed.apply();
    }

    public static boolean hasToken(Context c) { return token(c).length() > 20; }

    /** JWT 里的 exp（秒），解析不到返回 0 */
    public static long tokenExp(Context c) {
        String t = token(c);
        if (t.length() < 20) return 0;
        try {
            String[] p = t.split("\\.");
            if (p.length < 2) return 0;
            String b = p[1];
            int pad = (4 - b.length() % 4) % 4;
            for (int i = 0; i < pad; i++) b = b + "=";
            byte[] raw = android.util.Base64.decode(b, android.util.Base64.URL_SAFE);
            org.json.JSONObject o = new org.json.JSONObject(new String(raw, "UTF-8"));
            return o.optLong("exp", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 登录态是否还有效（过期或快过期都算不可用） */
    public static boolean tokenValid(Context c) {
        long exp = tokenExp(c);
        return exp > 0 && exp - 60 > System.currentTimeMillis() / 1000L;
    }

    /** 保存 OAuth1 token（可长期用来换新的 access token，不用再输账号密码） */
    public static void saveOAuth1(Context c, String t, String s) {
        if (t == null || s == null || t.length() < 5 || s.length() < 5) return;
        c.getSharedPreferences("mfit", Context.MODE_PRIVATE).edit()
                .putString("go1", Vault.seal(t)).putString("gos", Vault.seal(s)).apply();
    }

    public static boolean hasOAuth1(Context c) {
        return read(c.getSharedPreferences("mfit", Context.MODE_PRIVATE), "go1").length() > 5;
    }

    /** 用 OAuth1 token 换新的 access token；成功返回 true */
    public static synchronized boolean refresh(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("mfit", Context.MODE_PRIVATE);
        String ot = read(sp, "go1"), os = read(sp, "gos");
        if (ot.length() < 5 || os.length() < 5) return false;
        try {
            String eurl = api + "/oauth-service/oauth/exchange/user/2.0";
            Map<String, String> extra = new HashMap<String, String>();
            extra.put("audience", "GARMIN_CONNECT_MOBILE_ANDROID_DI");
            String auth = authHeader("POST", eurl, extra, ot, os);
            Map<String, String> h = new HashMap<String, String>();
            h.put("Authorization", auth);
            h.put("Content-Type", "application/x-www-form-urlencoded");
            Resp r = req("POST", eurl, h,
                    "audience=GARMIN_CONNECT_MOBILE_ANDROID_DI".getBytes("UTF-8"));
            String tok = jsonField(r.body, "access_token");
            if (tok != null && tok.length() > 20) {
                saveToken(c, tok);
                android.util.Log.i("mfit", "garmin token refreshed");
                return true;
            }
            android.util.Log.w("mfit", "garmin refresh fail code=" + r.code);
        } catch (Exception e) {
            android.util.Log.w("mfit", "garmin refresh err " + e.getMessage());
        }
        return false;
    }

    /** 拿一个尽量可用的 token：本地过期 → 用 OAuth1 换 → 再不行用记住的账号密码重登 */
    public static String ensureToken(Context c) {
        if (tokenValid(c)) return token(c);
        if (refresh(c)) return token(c);
        if (relogin(c)) return token(c);
        return token(c);
    }

    /** 用记住的账号密码重新登录；成功返回 true */
    public static synchronized boolean relogin(Context c) {
        if (!Vault.on(c)) return false;
        String u = Vault.user(c), p = Vault.pass(c);
        if (u.length() == 0 || p.length() == 0) return false;
        try {
            String t = login(u, p);
            if (t != null && t.length() > 20) {
                saveToken(c, t);
                android.util.Log.i("mfit", "garmin relogin ok");
                return true;
            }
        } catch (Exception e) {
            android.util.Log.w("mfit", "garmin relogin fail " + e.getMessage());
        }
        return false;
    }

    /** 有没有办法自动恢复登录（OAuth1 凭据或记住的账号都用不上就是没有） */
    public static boolean canAutoLogin(Context c) {
        return hasOAuth1(c) || Vault.on(c);
    }

    // ---------- HTTP ----------
    private static class Resp {
        int code;
        String body;
    }

    private static Resp req(String method, String url, Map<String, String> headers, byte[] body)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(40000);
        conn.setRequestProperty("User-Agent", UA);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet())
            conn.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            conn.setDoOutput(true);
            conn.getOutputStream().write(body);
        }
        Resp r = new Resp();
        r.code = conn.getResponseCode();
        InputStream in = r.code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
        }
        r.body = sb.toString();
        conn.disconnect();
        return r;
    }

    // ---------- OAuth1 ----------
    private static String pct(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A")
                    .replace("%7E", "~");
        } catch (Exception e) {
            return s;
        }
    }

    private static String hmacB64(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
        byte[] raw = mac.doFinal(data.getBytes("UTF-8"));
        return android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP);
    }

    private static String authHeader(String method, String url, Map<String, String> extra,
                                    String tok, String tokSecret) throws Exception {
        int q = url.indexOf('?');
        Map<String, String> all = new HashMap<String, String>();
        if (q >= 0) {
            for (String kv : url.substring(q + 1).split("&")) {
                if (kv.length() == 0) continue;
                String[] a = kv.split("=", 2);
                all.put(dec(a[0]), dec(a.length > 1 ? a[1] : ""));
            }
        }
        if (extra != null) all.putAll(extra);
        Map<String, String> op = new HashMap<String, String>();
        op.put("oauth_consumer_key", GK);
        op.put("oauth_nonce", Long.toHexString(System.nanoTime()) + Long.toHexString((long) (Math.random() * 1e12)));
        op.put("oauth_signature_method", "HMAC-SHA1");
        op.put("oauth_timestamp", String.valueOf(System.currentTimeMillis() / 1000));
        op.put("oauth_version", "1.0");
        if (tok != null && tok.length() > 0) op.put("oauth_token", tok);
        all.putAll(op);

        List<String> keys = new ArrayList<String>(all.keySet());
        Collections.sort(keys);
        StringBuilder enc = new StringBuilder();
        for (String k : keys) {
            if (enc.length() > 0) enc.append('&');
            enc.append(pct(k)).append('=').append(pct(all.get(k)));
        }
        String baseUrl = q >= 0 ? url.substring(0, q) : url;
        String base = method.toUpperCase() + "&" + pct(baseUrl) + "&" + pct(enc.toString());
        String key = pct(GS) + "&" + pct(tokSecret == null ? "" : tokSecret);
        String sig = hmacB64(key, base);
        op.put("oauth_signature", sig);

        StringBuilder h = new StringBuilder("OAuth ");
        boolean first = true;
        for (String k : op.keySet()) {
            if (!first) h.append(", ");
            h.append(pct(k)).append("=\"").append(pct(op.get(k))).append('"');
            first = false;
        }
        return h.toString();
    }

    private static String dec(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------- 登录 ----------
    /** 账号密码 → access_token；失败抛异常，message 给用户看 */
    public static String login(String user, String pass) throws Exception {
        // 1) SSO
        String body = "{\"username\":\"" + esc(user) + "\",\"password\":\"" + esc(pass)
                + "\",\"rememberMe\":false,\"captchaToken\":\"\"}";
        Map<String, String> h = new HashMap<String, String>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        Resp r = req("POST", sso + "/mobile/api/login?clientId=GCM_ANDROID_DARK&locale=" + loc
                + "&service=" + pct(mig), h, body.getBytes("UTF-8"));

        String ticket = null;
        String status = jsonField(r.body, "type");
        if (r.body != null && r.body.contains("\"serviceTicketId\""))
            ticket = jsonField(r.body, "serviceTicketId");
        if (ticket == null) {
            if (status.contains("MFA_REQUIRED"))
                throw new Exception("这个账号开了两步验证，暂不支持");
            String msg = jsonField(r.body, "message");
            throw new Exception(status.equals("INVALID_USERNAME_PASSWORD") ? "账号或密码不正确"
                    : ("登录失败 " + r.code + (msg.length() > 0 ? "：" + msg : "")));
        }

        // 2) preauthorized → oauth_token/secret
        String purl = api + "/oauth-service/oauth/preauthorized?ticket=" + pct(ticket)
                + "&login-url=" + pct(mig) + "&accepts-mfa-tokens=true";
        String auth1 = authHeader("GET", purl, null, null, null);
        Map<String, String> h1 = new HashMap<String, String>();
        h1.put("Authorization", auth1);
        Resp r1 = req("GET", purl, h1, null);
        if (r1.code != 200) throw new Exception("换 OAuth1 失败（HTTP " + r1.code + "）");
        String oTok = form(r1.body, "oauth_token");
        String oSec = form(r1.body, "oauth_token_secret");
        if (oTok == null || oSec == null) throw new Exception("OAuth1 返回不完整");
        // 3) exchange → access_token
        lO1 = oTok; lOS = oSec;
        String eurl = api + "/oauth-service/oauth/exchange/user/2.0";
        Map<String, String> extra = new HashMap<String, String>();
        extra.put("audience", "GARMIN_CONNECT_MOBILE_ANDROID_DI");
        String auth2 = authHeader("POST", eurl, extra, oTok, oSec);
        Map<String, String> h2 = new HashMap<String, String>();
        h2.put("Authorization", auth2);
        h2.put("Content-Type", "application/x-www-form-urlencoded");
        Resp r2 = req("POST", eurl, h2, "audience=GARMIN_CONNECT_MOBILE_ANDROID_DI".getBytes("UTF-8"));
        String tok = jsonField(r2.body, "access_token");
        if (tok == null || tok.length() < 20) {
            String body2 = r2.body == null ? "" : r2.body;
            throw new Exception("授权失败（HTTP " + r2.code + "）："
                    + body2.substring(0, Math.min(120, body2.length())));
        }
        return tok;
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "").replace("\r", "");
    }

    private static String jsonField(String json, String key) {
        if (json == null) return "";
        String p = "\"" + key + "\"";
        int i = json.indexOf(p);
        if (i < 0) return "";
        int c = json.indexOf(':', i + p.length());
        if (c < 0) return "";
        int start = c + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return "";
        if (json.charAt(start) == '"') {
            int end = start + 1;
            StringBuilder sb = new StringBuilder();
            while (end < json.length() && json.charAt(end) != '"') {
                if (json.charAt(end) == '\\') end++;
                sb.append(json.charAt(end));
                end++;
            }
            return sb.toString();
        }
        int end = start;
        while (end < json.length() && ",}\n ".indexOf(json.charAt(end)) < 0) end++;
        return json.substring(start, end).trim();
    }

    private static String form(String body, String key) {
        if (body == null) return null;
        for (String kv : body.trim().split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && key.equals(dec(kv.substring(0, e)))) return dec(kv.substring(e + 1));
        }
        return null;
    }

    // ---------- 上传 ----------
    public static class Result {
        public boolean ok;
        public boolean duplicate;
        public int code;
        public String msg;
    }

    public static Result upload(Context c, byte[] fit, String name) throws Exception {
        String tok = ensureToken(c);
        if (tok.length() < 20) throw new Exception("还没登录佳明");
        Result res = doUpload(tok, fit, name);
        if (res.code == 401 && (refresh(c) || relogin(c))) res = doUpload(token(c), fit, name);
        return res;
    }

    private static Result doUpload(String tok, byte[] fit, String name) throws Exception {
        String boundary = "----mfit" + System.currentTimeMillis();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bo.write(("--" + boundary + "\r\n").getBytes("UTF-8"));
        bo.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\n")
                .getBytes("UTF-8"));
        bo.write("Content-Type: application/octet-stream\r\n\r\n".getBytes("UTF-8"));
        bo.write(fit);
        bo.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));

        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + tok);
        h.put("User-Agent", "GCM-Android");
        h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        Resp r = req("POST", api + "/upload-service/upload/.fit", h, bo.toByteArray());

        Result res = new Result();
        res.code = r.code;
        res.msg = r.body;
        res.ok = (r.code >= 200 && r.code < 300);
        if (r.code == 409 && r.body != null && r.body.toLowerCase().contains("duplicate")) {
            res.ok = true;
            res.duplicate = true;
        }
        return res;
    }

    /** 佳明账号里最新一条体重，形如 "10-30 58.3 kg"；查不到返回空 */
    public static String latestWeight(Context c) {
        String tok = ensureToken(c);
        if (tok.length() < 20) return "";
        try {
            long now = System.currentTimeMillis() / 1000L;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd");
            String sd = f.format(new java.util.Date((now - 90 * 86400L) * 1000L));
            String ed = f.format(new java.util.Date(now * 1000L));
            Map<String, String> h = new HashMap<String, String>();
            h.put("Authorization", "Bearer " + tok);
            Resp r = req("GET", api + "/weight-service/weight/dateRange?startDate=" + sd
                    + "&endDate=" + ed, h, null);
            if (r.code != 200 || r.body == null) return "";
            org.json.JSONObject o = new org.json.JSONObject(r.body);
            org.json.JSONArray arr = o.optJSONArray("dateWeightList");
            if (arr == null || arr.length() == 0) return "";
            long bestDate = -1;
            double kg = 0;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject it = arr.getJSONObject(i);
                long d = it.optLong("date", 0);
                if (d >= bestDate) {
                    bestDate = d;
                    kg = it.optDouble("weight", 0) / 1000.0;
                }
            }
            if (kg <= 0) return "";
            java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM-dd");
            return df.format(new java.util.Date(bestDate))
                    + "  " + String.format(java.util.Locale.US, "%.1f", kg) + " kg";
        } catch (Exception e) {
            android.util.Log.w("mfit", "latestWeight fail " + e.getMessage());
            return "";
        }
    }

    /** 佳明列表用的文件名 */
    public static String uploadName(Run r) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(r.start * 1000);
        return String.format("mi_%04d%02d%02d_%02d%02d_%dkm.fit",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE), (int) Math.round(r.dist / 1000.0));
    }

    private static String num1(double v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    /**
     * 把一次称重推给佳明：体重 + 体脂率 + 肌肉量 + 基础代谢 + BMI。
     * 走 weight_scale 的 FIT 上传；被拒时退回只写体重和 BMI。
     */
    public static Result pushWeight(Context c, long ts, double kg, double fat,
                                    double muscle, int bmr, double bmi) throws Exception {
        String tok = ensureToken(c);
        if (tok.length() < 20) throw new Exception("还没登录佳明");
        if (kg <= 0 || ts <= 0) throw new Exception("体重数据不完整");
        Result res = doPushBody(tok, ts, kg, fat, muscle, bmr, bmi);
        if (res.code == 401 && (refresh(c) || relogin(c)))
            res = doPushBody(token(c), ts, kg, fat, muscle, bmr, bmi);
        if (!res.ok && res.code != 409) {
            Result r2 = doPush(tok, ts, kg, bmi);
            if (r2.ok) return r2;
        }
        return res;
    }

    private static Result doPushBody(String tok, long ts, double kg, double fat,
                                     double muscle, int bmr, double bmi) throws Exception {
        java.util.Calendar cl = java.util.Calendar.getInstance();
        cl.setTimeInMillis(ts * 1000);
        String name = String.format(java.util.Locale.US, "mi_body_%04d%02d%02d_%02d%02d.fit",
                cl.get(java.util.Calendar.YEAR), cl.get(java.util.Calendar.MONTH) + 1,
                cl.get(java.util.Calendar.DAY_OF_MONTH), cl.get(java.util.Calendar.HOUR_OF_DAY),
                cl.get(java.util.Calendar.MINUTE));
        return doUpload(tok, Fit.buildBody(ts, kg, fat, muscle, bmr, bmi), name);
    }

    private static Result doPush(String tok, long ts, double kg, double bmi) throws Exception {
        java.util.Calendar cl = java.util.Calendar.getInstance();
        cl.setTimeInMillis(ts * 1000);
        String date = String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cl.get(java.util.Calendar.YEAR), cl.get(java.util.Calendar.MONTH) + 1,
                cl.get(java.util.Calendar.DAY_OF_MONTH));
        StringBuilder body = new StringBuilder();
        body.append("{\"date\":\"").append(date).append("\",\"value\":").append(num1(kg))
                .append(",\"unitKey\":\"kg\"");
        if (bmi > 0) body.append(",\"bmi\":").append(num1(bmi));
        body.append("}");
        Map<String, String> h = new HashMap<String, String>();
        h.put("Authorization", "Bearer " + tok);
        h.put("Content-Type", "application/json");
        Resp r = req("POST", api + "/weight-service/user-weight", h,
                body.toString().getBytes("UTF-8"));
        Result res = new Result();
        res.code = r.code;
        res.msg = r.body;
        res.ok = (r.code >= 200 && r.code < 300);
        return res;
    }
}
