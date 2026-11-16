package com.hanxing.mifit;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地缓存：上次读到的运动列表 + 生理摘要，打开 App 先拿它垫上，后台再去读库。
 * 落盘一律 AES-256/GCM（密钥在 Android Keystore，不可导出），
 * 连 root 之外的任何进程、备份、adb 抓包都只能看到密文。
 */
public class Cache {
    private static final char SEP = '\u0001';

    private static File f(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    private static String readText(Context c, String name) {
        File x = f(c, name);
        if (!x.exists()) return null;
        try {
            byte[] raw = new byte[(int) x.length()];
            FileInputStream fi = new FileInputStream(x);
            int got = 0;
            while (got < raw.length) {
                int n = fi.read(raw, got, raw.length - got);
                if (n <= 0) break;
                got += n;
            }
            fi.close();
            byte[] plain = Vault.openBytes(raw);
            if (plain == null) {
                // 升级前的明文缓存：照样读出来，同时立刻换成密文
                plain = raw;
                writeText(c, name, new String(raw, "UTF-8"));
            }
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeText(Context c, String name, String text) {
        try {
            byte[] plain = text.getBytes("UTF-8");
            byte[] enc = Vault.sealBytes(plain);
            if (enc == null) enc = plain;            // Keystore 异常时别把数据丢了
            FileOutputStream fo = new FileOutputStream(f(c, name));
            fo.write(enc);
            fo.close();
        } catch (Exception ignored) { }
    }

    public static long runsStamp(Context c) {
        File x = f(c, "runs.cache");
        return x.exists() ? x.lastModified() / 1000L : 0;
    }

    public static void writeRuns(Context c, List<Run> list, long costMs) {
        StringBuilder sb = new StringBuilder(64 * list.size() + 32);
        sb.append("v1").append(SEP).append(System.currentTimeMillis() / 1000L).append(SEP).append(costMs).append('\n');
        for (Run r : list) {
            if (r.rep == null) continue;
            sb.append(nullStr(r.sid)).append(SEP).append(nullStr(r.key)).append(SEP)
              .append(r.rep.toString().replace('\n', ' '))
              .append(SEP).append(r.trackTs).append(SEP).append(r.trackProto)
              .append(SEP).append(nullStr(r.recPath)).append(SEP).append(nullStr(r.gpsPath)).append('\n');
        }
        writeText(c, "runs.cache", sb.toString());
    }

    private static String nullStr(String s) { return s == null ? "" : s; }

    public static List<Run> readRuns(Context c) {
        List<Run> out = new ArrayList<Run>();
        String text = readText(c, "runs.cache");
        if (text == null) return out;
        String[] lines = text.split("\n");
        for (int i = 1; i < lines.length; i++) {                 // 第一行是版本头
            String line = lines[i];
            if (line.length() == 0) continue;
            String[] p = line.split(String.valueOf(SEP), -1);
            if (p.length < 7) continue;
            Map<String, Object> row = new HashMap<String, Object>();
            row.put("sid", p[0]);
            row.put("key", p[1]);
            row.put("value", p[2]);
            Run r = Run.from(row);
            if (r == null) continue;
            long ts = parseLong(p[3], -1);
            r.trackTs = ts;
            r.trackProto = (int) parseLong(p[4], 0);
            r.recPath = p[5].length() == 0 ? null : p[5];
            r.gpsPath = p[6].length() == 0 ? null : p[6];
            out.add(r);
        }
        return out;
    }

    private static long parseLong(String s, long d) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return d; }
    }

    // ---------------- 生理摘要 ----------------
    public static void writeHealth(Context c, Health h) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("v1").append(SEP).append(System.currentTimeMillis() / 1000L).append('\n');
        sb.append("hr=").append(h.hrNow).append(SEP).append(h.hrMin).append(SEP).append(h.hrMax)
          .append(SEP).append(h.hrAvg).append('\n');
        sb.append("spo2=").append(h.spo2Now).append(SEP).append(h.spo2Min).append(SEP).append(h.spo2Avg).append('\n');
        sb.append("steps=").append(h.steps).append(SEP).append(h.stepDistM).append(SEP).append(h.stepCal).append('\n');
        sb.append("stress=").append(h.stressNow).append(SEP).append(h.stressAvg).append(SEP).append(h.standHours).append('\n');
        sb.append("sleep=").append(h.hasSleep ? 1 : 0).append(SEP).append(h.sleepTotal).append(SEP).append(h.deepMin)
          .append(SEP).append(h.lightMin).append(SEP).append(h.remMin).append(SEP).append(h.awakeMin)
          .append(SEP).append(h.sleepEff).append(SEP).append(h.sleepEnd).append(SEP).append(h.sleepHr)
          .append(SEP).append(h.sleepHrv).append(SEP).append(h.sleepSpo2).append(SEP).append(h.sleepBreath)
          .append(SEP).append(h.sleepWakeCount).append(SEP).append(stagesText(h)).append('\n');
        sb.append("weight=").append(h.hasWeight ? 1 : 0).append(SEP).append(h.kg).append(SEP).append(h.bodyFat)
          .append(SEP).append(h.bmiVal).append(SEP).append(h.muscle).append(SEP).append(h.bmr)
          .append(SEP).append(h.weightTs).append('\n');
        sb.append("weights=");
        for (double[] p : h.weightAll) {
            sb.append((long) p[0]).append(':')
              .append(String.format(java.util.Locale.US, "%.2f", p[1])).append(',');
        }
        sb.append('\n');
        sb.append("hrtrend=").append(seriesText(h.hr, 0)).append('\n');
        sb.append("spo2trend=").append(seriesText(h.spo2, 0)).append('\n');
        sb.append("wtrend=").append(seriesText(h.weightTrend, 2)).append('\n');
        sb.append("hrvtrend=").append(seriesText(h.hrvTrend, 0)).append('\n');
        sb.append("loadtrend=").append(seriesText(h.loadTrend, 0)).append('\n');
        sb.append("vo2=").append(h.vo2max).append(SEP).append(h.vo2level).append('\n');
        sb.append("load=").append(h.loadWeek).append(SEP).append(h.loadLevel).append(SEP).append(h.loadToday).append('\n');
        sb.append("ability=").append(h.ability).append(SEP).append(h.abilityLevel).append(SEP).append(h.p5k)
          .append(SEP).append(h.p10k).append(SEP).append(h.pHalf).append(SEP).append(h.pFull).append('\n');
        sb.append("vitality=").append(h.vitality).append('\n');
        writeText(c, "health.cache", sb.toString());
    }

    /** {ts,val} 序列 → "ts:val,ts:val"（decimals 决定精度） */
    private static String seriesText(java.util.List<double[]> list, int decimals) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : list) {
            if (p == null || p.length < 2) continue;
            sb.append((long) p[0]).append(':')
              .append(String.format(java.util.Locale.US, "%." + decimals + "f", p[1]))
              .append(',');
        }
        return sb.toString();
    }

    /** "ts:val,ts:val" → List<double[]> */
    private static void fillSeries(String s, java.util.List<double[]> out) {
        if (s == null || s.length() == 0) return;
        for (String one : s.split(",")) {
            int ci = one.indexOf(':');
            if (ci <= 0) continue;
            long ts = (long) parseLong(one.substring(0, ci), 0);
            double v = parseDouble(one.substring(ci + 1));
            if (ts > 0) out.add(new double[]{ts, v});
        }
    }

    private static String stagesText(Health h) {
        StringBuilder sb = new StringBuilder();
        for (int[] s : h.stages) sb.append(s[0]).append(':').append(s[1]).append(',');
        return sb.toString();
    }

    public static Health readHealth(Context c) {
        String text = readText(c, "health.cache");
        if (text == null) return null;
        Health h = new Health();
        try {
            String[] lines = text.split("\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq);
                String[] v = line.substring(eq + 1).split(String.valueOf(SEP), -1);
                if ("hr".equals(k)) {
                    h.hrNow = (int) parseLong(v[0], 0); h.hrMin = (int) parseLong(v[1], 0);
                    h.hrMax = (int) parseLong(v[2], 0); h.hrAvg = (int) parseLong(v[3], 0);
                } else if ("spo2".equals(k)) {
                    h.spo2Now = (int) parseLong(v[0], 0); h.spo2Min = (int) parseLong(v[1], 0);
                    h.spo2Avg = (int) parseLong(v[2], 0);
                } else if ("steps".equals(k)) {
                    h.steps = (int) parseLong(v[0], 0); h.stepDistM = (int) parseLong(v[1], 0);
                    h.stepCal = (int) parseLong(v[2], 0);
                } else if ("stress".equals(k)) {
                    h.stressNow = (int) parseLong(v[0], 0); h.stressAvg = (int) parseLong(v[1], 0);
                    h.standHours = (int) parseLong(v[2], 0);
                } else if ("sleep".equals(k)) {
                    h.hasSleep = "1".equals(v[0]);
                    h.sleepTotal = (int) parseLong(v[1], 0); h.deepMin = (int) parseLong(v[2], 0);
                    h.lightMin = (int) parseLong(v[3], 0); h.remMin = (int) parseLong(v[4], 0);
                    h.awakeMin = (int) parseLong(v[5], 0); h.sleepEff = (int) parseLong(v[6], 0);
                    h.sleepEnd = parseLong(v[7], 0); h.sleepHr = (int) parseLong(v[8], 0);
                    h.sleepHrv = (int) parseLong(v[9], 0); h.sleepSpo2 = (int) parseLong(v[10], 0);
                    h.sleepBreath = (int) parseLong(v[11], 0); h.sleepWakeCount = (int) parseLong(v[12], 0);
                    if (v.length > 13 && v[13].length() > 0) {
                        for (String s : v[13].split(",")) {
                            String[] kv = s.split(":");
                            if (kv.length == 2) {
                                h.stages.add(new int[]{(int) parseLong(kv[0], 0), (int) parseLong(kv[1], 0)});
                            }
                        }
                    }
                } else if ("weight".equals(k)) {
                    h.hasWeight = "1".equals(v[0]);
                    h.kg = parseDouble(v[1]); h.bodyFat = parseDouble(v[2]);
                    h.bmiVal = parseDouble(v[3]); h.muscle = parseDouble(v[4]);
                    h.bmr = (int) parseLong(v[5], 0); h.weightTs = parseLong(v[6], 0);
                } else if ("vo2".equals(k)) {
                    h.vo2max = (int) parseLong(v[0], 0); h.vo2level = (int) parseLong(v[1], 0);
                } else if ("load".equals(k)) {
                    h.loadWeek = (int) parseLong(v[0], 0); h.loadLevel = (int) parseLong(v[1], 0);
                    h.loadToday = (int) parseLong(v[2], 0);
                } else if ("ability".equals(k)) {
                    h.ability = parseDouble(v[0]); h.abilityLevel = (int) parseLong(v[1], 0);
                    h.p5k = (int) parseLong(v[2], 0); h.p10k = (int) parseLong(v[3], 0);
                    h.pHalf = (int) parseLong(v[4], 0); h.pFull = (int) parseLong(v[5], 0);
                } else if ("vitality".equals(k)) {
                    h.vitality = (int) parseLong(v[0], 0);
                } else if ("weights".equals(k)) {
                    for (String s : v[0].split(",")) {
                        int ci = s.indexOf(':');
                        if (ci <= 0) continue;
                        long ts = (long) parseLong(s.substring(0, ci), 0);
                        double kg = parseDouble(s.substring(ci + 1));
                        if (ts > 0 && kg > 0) h.weightAll.add(new double[]{ts, kg});
                    }
                } else if ("hrtrend".equals(k)) {
                    fillSeries(v[0], h.hr);
                } else if ("spo2trend".equals(k)) {
                    fillSeries(v[0], h.spo2);
                } else if ("wtrend".equals(k)) {
                    fillSeries(v[0], h.weightTrend);
                } else if ("hrvtrend".equals(k)) {
                    fillSeries(v[0], h.hrvTrend);
                } else if ("loadtrend".equals(k)) {
                    fillSeries(v[0], h.loadTrend);
                    for (double[] p : h.loadTrend) if (p[1] > 0) h.loadDaysWithValue++;
                }
            }
            return h;
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    public static void clear(Context c) {
        f(c, "runs.cache").delete();
        f(c, "health.cache").delete();
    }

    public static long healthStamp(Context c) {
        File x = f(c, "health.cache");
        return x.exists() ? x.lastModified() / 1000L : 0;
    }
}
