package com.hanxing.mifit;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 我的：账号 / 设备 / 同步 / 关于 */
public class MePage extends ScrollView {
    private final Activity a;
    private final LinearLayout body;
    private Runnable onChanged;

    private TextView garminState, garminBtn;
    private TextView syncText, cacheText, countText, garmUpState;
    private WatchArt art;
    private String deviceName = "未找到已配对的手表";

    public MePage(Activity act, Runnable onChanged) {
        super(act);
        this.a = act;
        this.onChanged = onChanged;
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(8), dp(12), dp(124));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        build();
    }

    private int dp(float v) { return Ui.dp(a, v); }

    private void build() {
        body.removeAllViews();

        // ---------- 账号 ----------
        section("账号");
        body.addView(accountCard());

        // ---------- 设备 ----------
        section("我的设备");
        body.addView(deviceCard());

        // ---------- 同步 ----------
        section("数据与同步");
        body.addView(syncCard());

        // ---------- 运行环境 ----------
        section("运行环境");
        body.addView(rootCard());

        // ---------- 关于 ----------
        section("关于");
        body.addView(aboutCard());
    }

    /** 读库靠 root，这里把授予情况直接亮出来 */
    private View rootCard() {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(Ui.tv(a, "Root 权限", 14, 0xFF1B2333, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView st = Ui.tv(a, "检测中…", 13, Ui.SUB, true);
        head.addView(st);
        c.addView(head);

        final TextView sub = Ui.tv(a, "", 10.5f, Ui.SUB, false);
        sub.setPadding(0, dp(7), 0, 0);
        c.addView(sub);

        new Thread(new Runnable() {
            public void run() {
                RootShell rs = new RootShell();
                final boolean ok;
                final String m;
                try {
                    ok = rs.start(a);
                    m = rs.mode();
                } catch (Throwable t) {
                    post(new Runnable() {
                        public void run() {
                            st.setText("未获取");
                            st.setTextColor(0xFFD14343);
                            sub.setText("需要在 Magisk / KernelSU 里给本 App 授予超级用户，否则读不到小米运动健康的数据");
                        }
                    });
                    return;
                }
                try { rs.stop(); } catch (Throwable ignored) { }
                post(new Runnable() {
                    public void run() {
                        st.setText(ok ? "已获取" : "未获取");
                        st.setTextColor(ok ? 0xFF2E9E5B : 0xFFD14343);
                        sub.setText(ok ? "" : "需要在 Magisk / KernelSU 里给本 App 授予超级用户，否则读不到小米运动健康的数据");
                        sub.setVisibility(ok ? View.GONE : View.VISIBLE);
                    }
                });
            }
        }).start();
        return c;
    }

    private void section(String t) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(14), dp(4), dp(7));
        r.addView(Ui.dot(a));
        r.addView(Ui.tv(a, t, 13, Ui.TXT, true));
        body.addView(r);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(a, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private View row(String k, String v) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(5), 0, dp(5));
        r.addView(Ui.tv(a, k, 12, Ui.SUB, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(Ui.tv(a, v, 12, Ui.MID, false));
        return r;
    }

    private View accountCard() {
        LinearLayout c = card();

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = Ui.tv(a, "G", 15, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.glassCircle(a, Garm.hasToken(a)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(40), dp(40));
        llp.rightMargin = dp(11);
        head.addView(logo, llp);

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.tv(a, "Garmin Connect（中国区）", 13.5f, Ui.TXT, true));
        boolean signed = Garm.hasToken(a) || Garm.canAutoLogin(a);
        boolean live = Garm.hasToken(a) && Garm.tokenValid(a);
        garminState = Ui.tv(a, !signed ? "未登录"
                : (live ? "已登录 · 可直接上传" : "已登录 · 过期自动续期"),
                11.5f, Ui.SUB, false);
        garminState.setPadding(0, dp(2), 0, 0);
        col.addView(garminState);
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        garminBtn = Ui.btn(a, signed ? "账号管理" : "登录", false);
        garminBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                GarmUi.menu(a, new Runnable() {
                    public void run() {
                        refreshAccount();
                        if (MePage.this.onChanged != null) MePage.this.onChanged.run();
                        // 刚登录上就把最新称重补推一次
                        if (a instanceof MainActivity && Garm.tokenValid(a)) ((MainActivity) a).syncWeightNow();
                    }
                });
            }
        });
        head.addView(garminBtn);
        c.addView(head);

        return c;
    }

    private View deviceCard() {
        LinearLayout c = card();
        LinearLayout rowWrap = new LinearLayout(a);
        rowWrap.setOrientation(LinearLayout.HORIZONTAL);

        int artRes = DeviceArt.of(deviceName);
        if (artRes != 0) {
            android.widget.ImageView pic = new android.widget.ImageView(a);
            pic.setImageResource(artRes);
            pic.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(96), dp(96));
            alp.rightMargin = dp(10);
            rowWrap.addView(pic, alp);
        } else {
            WatchArt fallback = new WatchArt(a);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(74), dp(88));
            alp.rightMargin = dp(13);
            rowWrap.addView(fallback, alp);
        }

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = Ui.tv(a, deviceName, 15, Ui.TXT, true);
        col.addView(name);
        LinearLayout live = new LinearLayout(a);
        live.setOrientation(LinearLayout.HORIZONTAL);
        live.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(a);
        dot.setBackground(Ui.round(a, 0xFF2AA45C, 0, 999));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.rightMargin = dp(5);
        live.addView(dot, dlp);
        live.addView(Ui.tv(a, "蓝牙已配对", 11, 0xFF2AA45C, false));
        live.setPadding(0, dp(4), 0, dp(3));
        col.addView(live);
        syncText = Ui.tv(a, "最后同步：—", 11.5f, Ui.SUB, false);
        col.addView(syncText);
        rowWrap.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(rowWrap);

        c.addView(sp());
        c.addView(row("数据来源", "小米运动健康 本地库直读"));
        countText = Ui.tv(a, "", 11.5f, Ui.SUB, false);
        countText.setPadding(0, dp(8), 0, 0);
        c.addView(countText);
        return c;
    }

    /** 「最近称重 X kg · 已推送/未推送」；点一下可立即再推 */
    private String weightStateText() {
        Health h = Cache.readHealth(a);
        if (h == null || !h.hasWeight || h.kg <= 0 || h.weightTs <= 0) return "还没读到称重记录";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm");
        String when = f.format(new java.util.Date(h.weightTs * 1000L));
        long done = a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE)
                .getLong("weightSyncedTs", 0);
        String tag = h.weightTs <= done ? "已推送" : "未推送";
        String kg = String.format(java.util.Locale.US, "%.1f", h.kg);
        return "最近称重 " + when + " · " + kg + " kg · " + tag + "（点这里立即再推一次）";
    }

    /** 自动上传运动记录的状态行 */
    private String garmUploadText() {
        if (!Garm.hasToken(a) && !Garm.canAutoLogin(a)) {
            return "未登录佳明，登录后新运动会自动上传";
        }
        long ts = a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE)
                .getLong("garmTs", 0);
        if (ts <= 0) return "还没上传过 · 下一场新运动自动上传（点这里立即补传）";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm");
        return "已自动上传至 " + f.format(new java.util.Date(ts)) + "（点这里立即补传）";
    }

    private View syncCard() {
        LinearLayout c = card();
        cacheText = Ui.tv(a, "", 12, Ui.MID, false);
        cacheText.setPadding(0, 0, 0, dp(4));
        c.addView(cacheText);

        // 自动同步到佳明（佳明只接受体重类数据的第三方写入）
        LinearLayout autoRow = new LinearLayout(a);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(0, dp(9), 0, 0);
        autoRow.addView(Ui.tv(a, "自动同步体重体脂到佳明", 12.5f, Ui.TXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        boolean autoOn = a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE)
                .getBoolean("autoWeight", false);
        Ui.Toggle tg = Ui.toggle(a, autoOn, new Ui.ToggleListener() {
            public void onToggle(boolean on) {
                a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("autoWeight", on).apply();
                if (on && a instanceof MainActivity) ((MainActivity) a).syncWeightNow();
            }
        });
        autoRow.addView(tg, new LinearLayout.LayoutParams(dp(44), dp(26)));
        c.addView(autoRow);

        // 自动上传运动记录到佳明
        LinearLayout autoRunRow = new LinearLayout(a);
        autoRunRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRunRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRunRow.setPadding(0, dp(10), 0, 0);
        autoRunRow.addView(Ui.tv(a, "自动上传运动到佳明", 12.5f, Ui.TXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        boolean arOn = a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE)
                .getBoolean("garmAuto", true);
        Ui.Toggle tg2 = Ui.toggle(a, arOn, new Ui.ToggleListener() {
            public void onToggle(boolean on) {
                a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE).edit()
                        .putBoolean("garmAuto", on).apply();
                if (on && a instanceof MainActivity) ((MainActivity) a).autoUploadNow();
            }
        });
        autoRunRow.addView(tg2, new LinearLayout.LayoutParams(dp(44), dp(26)));
        c.addView(autoRunRow);

        garmUpState = Ui.tv(a, garmUploadText(), 10.5f, Ui.SUB, false);
        garmUpState.setPadding(0, dp(5), 0, dp(3));
        garmUpState.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (a instanceof MainActivity) ((MainActivity) a).autoUploadNow();
            }
        });
        c.addView(garmUpState);

        // 最近一次称重与推送状态；点一下立即再推一次
        final TextView wState = Ui.tv(a, weightStateText(), 10.5f, Ui.SUB, false);
        wState.setPadding(0, 0, 0, dp(11));
        wState.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (a instanceof MainActivity) ((MainActivity) a).syncWeightNow();
            }
        });
        c.addView(wState);

        // 功率用的体重：库里按运动时间就近取，没有称重记录时用这里手填的值
        LinearLayout kgRow = new LinearLayout(a);
        kgRow.setOrientation(LinearLayout.HORIZONTAL);
        kgRow.setGravity(Gravity.CENTER_VERTICAL);
        kgRow.setPadding(0, 0, 0, dp(11));
        kgRow.addView(Ui.tv(a, "功率体重", 12.5f, Ui.TXT, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final float mk0 = a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE)
                .getFloat("manualKg", 0f);
        final TextView kgVal = Ui.tv(a, mk0 > 0
                ? String.format(java.util.Locale.US, "手动 %.1f kg", mk0) : "按称重记录", 11.5f, Ui.SUB, false);
        kgRow.addView(kgVal);
        kgRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LinearLayout body = new LinearLayout(a);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(Ui.dlgTitle(a, "手动体重"));
                body.addView(Ui.dlgBody(a, "有称重记录时按运动时间取最近一次，这里的值只在库里一条称重都没有时用。留空即清除。"));
                final android.widget.EditText et = Ui.field(a, "kg，例如 58.3");
                float cur = a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE)
                        .getFloat("manualKg", 0f);
                if (cur > 0) et.setText(String.format(java.util.Locale.US, "%.1f", cur));
                android.widget.LinearLayout.LayoutParams flp = new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                flp.topMargin = dp(10);
                body.addView(et, flp);
                final android.app.Dialog[] holder = new android.app.Dialog[1];
                body.addView(Ui.btnRow(a, "取消", "保存",
                        new Runnable() { public void run() { holder[0].dismiss(); } },
                        new Runnable() {
                            public void run() {
                                holder[0].dismiss();
                                double kg = 0;
                                try { kg = Double.parseDouble(et.getText().toString().trim()); }
                                catch (Exception ignored) { }
                                if (kg < 20 || kg > 300) kg = 0;
                                a.getSharedPreferences("mfit", android.content.Context.MODE_PRIVATE).edit()
                                        .putFloat("manualKg", (float) kg).apply();
                                Weight.reset();
                                kgVal.setText(kg > 0
                                        ? String.format(java.util.Locale.US, "手动 %.1f kg", kg) : "按称重记录");
                            }
                        }));
                holder[0] = Ui.panel(a, body);
                holder[0].show();
            }
        });
        c.addView(kgRow);

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        TextView full = Ui.btn(a, "同步全量数据", false);
        TextView clear = Ui.btn(a, "清除缓存", false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        blp.rightMargin = dp(8);
        full.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                LinearLayout body = new LinearLayout(a);
                body.setOrientation(LinearLayout.VERTICAL);
                body.addView(Ui.dlgTitle(a, "同步全量数据"));
                body.addView(Ui.dlgBody(a, "会重新读完整库、重扫全部轨迹，场次多时需要较长时间，期间可以继续用。"));
                final android.app.Dialog[] holder = new android.app.Dialog[1];
                body.addView(Ui.btnRow(a, "取消", "继续",
                        new Runnable() { public void run() { holder[0].dismiss(); } },
                        new Runnable() {
                            public void run() {
                                holder[0].dismiss();
                                if (a instanceof MainActivity) ((MainActivity) a).syncFull();
                            }
                        }));
                holder[0] = Ui.panel(a, body);
                holder[0].show();
            }
        });
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Cache.clear(a);
                refreshStats(0, 0, 0, "");
                Toast.makeText(a, "缓存已清除，下次启动会重新读库", Toast.LENGTH_SHORT).show();
            }
        });
        btns.addView(full, blp);
        btns.addView(clear, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(btns);
        return c;
    }

    private View aboutCard() {
        LinearLayout c = card();
        String ver = "—";
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            ver = pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception ignored) { }
        c.addView(row("版本", ver));
        c.addView(row("包名", a.getPackageName()));
        c.addView(row("作者", "涵星之辰"));
        return c;
    }

    private View sp() {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(9)));
        return v;
    }

    /** 列表/健康数据刷新后回填 */
    public void refreshStats(int runs, double km, int devices, String lastSync) {
        if (countText != null) {
            countText.setText("已读到 " + runs + " 场运动 · " + String.format("%.2f", km)
                    + " km · " + devices + " 台设备");
        }
        if (syncText != null) syncText.setText("最后同步：" + (lastSync == null || lastSync.length() == 0 ? "—" : lastSync));
        if (garmUpState != null) garmUpState.setText(garmUploadText());
        long stamp = Cache.healthStamp(a);
        if (cacheText != null) {
            if (stamp <= 0) cacheText.setText("本地缓存：还没有");
            else cacheText.setText("本地缓存：" + stampText(stamp) + " · 已启用自动同步");
        }
    }

    private static String stampText(long ts) {
        long d = System.currentTimeMillis() / 1000L - ts;
        if (d < 60) return "刚刚";
        if (d < 3600) return d / 60 + " 分钟前";
        if (d < 86400) return d / 3600 + " 小时前";
        return d / 86400 + " 天前";
    }

    public void refreshAccount() {
        if (garminState != null) {
            boolean signed = Garm.hasToken(a) || Garm.canAutoLogin(a);
            boolean live = Garm.hasToken(a) && Garm.tokenValid(a);
            garminState.setText(!signed ? "未登录"
                    : (live ? "已登录 · 可直接上传" : "已登录 · 过期自动续期"));
            garminState.setTextColor(Ui.SUB);
        }
        if (garminBtn != null) garminBtn.setText((Garm.hasToken(a) || Garm.canAutoLogin(a)) ? "账号管理" : "登录");
    }

    public void setDeviceName(String n) {
        if (n != null && n.length() > 0 && !n.equals(deviceName)) {
            deviceName = n;
            build();
        }
    }

    /** 手表小插画：表体 + 表带 + 屏幕渐变 */
    static class WatchArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        WatchArt(Activity a) { super(a); }

        @Override
        protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float bw = w * 0.66f, bh = h * 0.44f;

            // 表带
            p.setColor(0xFF2B3240);
            RectF band = new RectF(cx - bw * 0.30f, 0, cx + bw * 0.30f, h);
            cv.drawRoundRect(band, w * 0.12f, w * 0.12f, p);

            // 表体
            RectF bodyR = new RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2);
            p.setColor(0xFF14181F);
            cv.drawRoundRect(bodyR, w * 0.16f, w * 0.16f, p);

            // 屏幕
            RectF scr = new RectF(bodyR.left + w * 0.06f, bodyR.top + h * 0.035f,
                    bodyR.right - w * 0.06f, bodyR.bottom - h * 0.035f);
            android.graphics.LinearGradient lg = new android.graphics.LinearGradient(
                    scr.left, scr.top, scr.right, scr.bottom,
                    new int[]{0xFF7FD3FF, 0xFF2E7BE0, 0xFF6B3FD4}, null,
                    android.graphics.Shader.TileMode.CLAMP);
            p.setShader(lg);
            cv.drawRoundRect(scr, w * 0.13f, w * 0.13f, p);
            p.setShader(null);

            // 屏幕上的心跳折线
            p.setColor(0xCCFFFFFF);
            p.setStrokeWidth(w * 0.035f);
            android.graphics.Path path = new android.graphics.Path();
            float y0 = scr.centerY();
            path.moveTo(scr.left + w * 0.04f, y0);
            path.lineTo(scr.left + w * 0.14f, y0);
            path.lineTo(scr.left + w * 0.19f, y0 - h * 0.02f);
            path.lineTo(scr.left + w * 0.24f, y0 + h * 0.03f);
            path.lineTo(scr.left + w * 0.29f, y0 - h * 0.045f);
            path.lineTo(scr.left + w * 0.34f, y0);
            path.lineTo(scr.right - w * 0.04f, y0);
            cv.drawPath(path, p);

            // 右侧表冠
            p.setColor(0xFF3A4250);
            cv.drawRoundRect(new RectF(bodyR.right + w * 0.005f, cy - bh * 0.16f,
                    bodyR.right + w * 0.055f, cy + bh * 0.12f), w * 0.05f, w * 0.05f, p);
        }
    }
}
