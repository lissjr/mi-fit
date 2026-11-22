package com.hanxing.mifit;

import android.app.Activity;
import android.content.Intent;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** 单场详情：汇总卡片 + 曲线（联动游标）+ 地图 + 导出 FIT */
public class DetailActivity extends Activity {
    public static Run pending;
    private Run r;
    private Context ctx;
    private LinearLayout chartsHost, mapHost;
    private TextView mapLegend, trackStatus;
    private Track track;
    private View kpiView;
    private double kgUsed;           // 这场用的体重 kg（0 = 没取到）
    private double pwAvg, pwMax;     // 这场估算功率的平均值与最大值
    private double[] kgInfo;          // {kg, 称重秒}，秒=0 表示手动
    private final List<ChartView> charts = new ArrayList<ChartView>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView expBtn;

    public static void show(Context c, Run run) {
        pending = run;
        c.startActivity(new Intent(c, DetailActivity.class));
    }

    private int dp(float v) { return Ui.dp(ctx, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ctx = this;
        Garm.setRegion(getSharedPreferences("mfit", MODE_PRIVATE).getBoolean("gcn", true));
        r = pending;
        if (r == null) { finish(); return; }
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.backdrop(this));

        // ---- 顶栏 ----
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(Ui.glassBar(this, true));
        head.setPadding(dp(16), dp(14), dp(16), dp(10));
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.TOP);
        LinearLayout tl = new LinearLayout(ctx);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.addView(Ui.tv(ctx, String.format("%.2f", r.dist / 1000.0) + " km · " + r.label(),
                17, Ui.TXT, true));
        TextView s = Ui.tv(ctx, MainActivity.timeText(r.start) + " 起 · " + MainActivity.durText(r.timer)
                + (r.hasTrack() ? "" : " · 无轨迹"), 12, Ui.SUB, false);
        s.setPadding(0, dp(3), 0, 0);
        tl.addView(s);
        titleRow.addView(tl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = Ui.tv(ctx, "✕", 15, Ui.MID, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.glassCircle(ctx, false));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        titleRow.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));
        head.addView(titleRow);
        head.addView(Ui.divider(ctx));
        root.addView(head);

        // ---- 内容 ----
        ScrollView sc = new ScrollView(ctx);
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(24));
        body.addView(kpiView = kpiGrid());
        if (r.stride > 0 || r.ct > 0 || r.vamp > 0 || r.vsr > 0) {
            body.addView(sectionTitle("跑步动态", ""));
            body.addView(dynamicGrid());
        }

        trackStatus = Ui.tv(ctx, r.hasTrack() ? "正在解码逐点轨迹…" : "这一场没有逐点轨迹文件", 12.5f, Ui.SUB, false);
        body.addView(sectionTitle("曲线", ""));
        body.addView(trackStatus);
        chartsHost = new LinearLayout(ctx);
        chartsHost.setOrientation(LinearLayout.VERTICAL);
        body.addView(chartsHost);

        body.addView(sectionTitle("地图", ""));
        mapHost = new LinearLayout(ctx);
        mapHost.setOrientation(LinearLayout.VERTICAL);
        body.addView(mapHost);
        mapLegend = Ui.tv(ctx, "", 11, Ui.SUB, false);
        mapLegend.setPadding(0, dp(6), 0, 0);
        body.addView(mapLegend);

        body.addView(note());
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // ---- 底栏 ----
        LinearLayout barWrap = new LinearLayout(ctx);
        barWrap.setOrientation(LinearLayout.VERTICAL);
        barWrap.setBackground(Ui.glassBar(this, false));
        barWrap.addView(Ui.divider(ctx));
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(16), dp(10), dp(16), dp(10));
        final TextView gb = Ui.btn(ctx, Garm.hasToken(ctx) ? "上传 Garmin" : "登录佳明", false);
        if (!Garm.hasToken(ctx)) gb.setText("登录佳明");
        gb.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.util.Log.i("mfit", "garmin click token=" + Garm.hasToken(ctx));
                if (Garm.hasToken(ctx)) doUpload(gb);
                else askLogin(gb);
            }
        });
        expBtn = Ui.btn(ctx, "导出这一场", true);
        expBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { export(); }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.rightMargin = dp(8);
        bar.addView(gb, glp);
        bar.addView(expBtn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        barWrap.addView(bar);
        root.addView(barWrap);
        expBtn.post(new Runnable() {
            public void run() {
                int[] loc = new int[2];
                expBtn.getLocationOnScreen(loc);
                android.util.Log.i("mfit", "btn at " + loc[0] + "," + loc[1] + " size "
                        + expBtn.getWidth() + "x" + expBtn.getHeight());
            }
        });

        setContentView(root);
        Edge.setup(this, root, head, barWrap);

        if (r.hasTrack()) loadTrack();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        android.util.Log.i("mfit", "touch a=" + ev.getActionMasked() + " " + ev.getX() + "," + ev.getY()
                + " h=" + getWindow().getDecorView().getHeight());
        return super.dispatchTouchEvent(ev);
    }

    private void toast(String s) { Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show(); }

    // ---------- 轨迹 ----------
    private void loadTrack() {
        new Thread(new Runnable() {
            public void run() {
                RootShell sh = new RootShell();
                Track t = null;
                String err = null;
                double[] kinfo = null;
                try {
                    if (sh.start(ctx)) {
                        byte[] rec = r.recPath != null ? sh.cat(r.recPath) : null;
                        byte[] gps = r.gpsPath != null ? sh.cat(r.gpsPath) : null;
                        t = Track.analyze(rec, gps);
                        kinfo = Weight.cached(ctx, sh).at(r.start);
                    } else {
                        err = "本机这次记录要读手机里的轨迹，先到 root 管理器给 FitLink 授权";
                    }
                } catch (Exception e) {
                    android.util.Log.i("mfit", "track fail " + e);
                    err = "这一场的轨迹文件没读出来";
                }
                final Track ft = t;
                final String ferr = err;
                final double[] fk = kinfo;
                ui.post(new Runnable() {
                    public void run() {
                        if (ft == null || !ft.selfTest) {
                            trackStatus.setText("轨迹没画出来：" + (ferr == null ? "这一场的轨迹文件读不到内容" : ferr));
                            return;
                        }
                        track = ft;
                        kgInfo = fk;
                        kgUsed = fk != null ? fk[0] : 0;
                        android.util.Log.i("mfit", "detail kg=" + kgUsed
                                + " info=" + (fk == null ? "null" : (fk[0] + "@" + (long) fk[1])
                                + " run=" + r.start));
                        if ((r.isRun() || r.isWalk()) && kgUsed > 0) {
                            double[] pw = Power.series(ft.spd, ft.alt, ft.t, kgUsed, r.isWalk());
                            double sum = 0;
                            int n = 0;
                            pwMax = 0;
                            for (double p : pw) {
                                if (p > 0) { sum += p; n++; if (p > pwMax) pwMax = p; }
                            }
                            pwAvg = n > 0 ? sum / n : 0;
                        }
                        if (kpiView != null && kpiView.getParent() instanceof LinearLayout) {
                            LinearLayout p = (LinearLayout) kpiView.getParent();
                            int at = p.indexOfChild(kpiView);
                            p.removeView(kpiView);
                            kpiView = kpiGrid();
                            p.addView(kpiView, at);
                        }
                        trackStatus.setVisibility(View.GONE);
                        buildCharts(ft);
                        buildMap(ft);
                    }
                });
            }
        }).start();
    }

    private void buildCharts(Track t) {
        charts.clear();
        chartsHost.removeAllViews();
        addChart("心率", "bpm", t.hr, 0xFFEB2F96, false, t.mtArr);
        if (r.isRide()) {
            addChart("速度", "km/h", t.spd, 0xFF2F7CF6, false, t.mtArr);
        } else {
            addChart("配速", "/km", t.pace, 0xFF2F7CF6, true, t.mtArr);
            addChart("步频", "步/分", t.cad, 0xFF00A870, false, t.mtArr);
        }
        addChart("海拔", "m", t.alt, 0xFF8A53D6, false, t.mtArr);
        if (r.isRun()) {
            addChart("步幅", "cm", t.stride, 0xFF8A53D6, false, t.mtArr);
            addChart("触地时间", "ms", t.ct, 0xFFF77234, false, t.mtArr);
            addChart("垂直振幅", "cm", t.vamp, 0xFF13C2C2, false, t.mtArr);
            addChart("垂直步幅比", "%", t.vsr, 0xFFEB2F96, false, t.mtArr);
        }
        if ((r.isRun() || r.isWalk()) && kgUsed > 0) {
            double[] pw = Power.series(t.spd, t.alt, t.t, kgUsed, r.isWalk());
            if (pw.length > 0)
                addChart("功率 · " + Weight.label(kgInfo), "W", pw, 0xFFF5A623, false, t.mtArr);
        }
    }

    private void addChart(String name, String unit, double[] vals, int color, boolean paceMode,
                          double[] mt) {
        if (vals == null || vals.length == 0) return;
        boolean any = false;
        for (double v : vals) if (v > 0) { any = true; break; }
        if (!any) return;

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setBackground(Ui.glass(ctx, Ui.GLASS_HI, 16));
        wrap.setPadding(dp(8), dp(8), dp(8), dp(6));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.bottomMargin = dp(8);
        wrap.setLayoutParams(wlp);

        LinearLayout trow = new LinearLayout(ctx);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        trow.addView(Ui.tv(ctx, name, 12.5f, Ui.MID, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        trow.addView(Ui.tv(ctx, unit, 10.5f, Ui.SUB, false));
        wrap.addView(trow);

        final ChartView cv = new ChartView(ctx, vals, mt, unit, color);
        cv.setPaceMode(paceMode);
        cv.setOnCursor(new ChartView.OnCursor() {
            public void onCursor(int idx) {
                for (ChartView c : charts) c.setCursor(idx);
            }
        });
        wrap.addView(cv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        charts.add(cv);
        chartsHost.addView(wrap);
    }

    private void buildMap(Track t) {
        mapHost.removeAllViews();
        if (t.track.size() < 2) {
            mapHost.addView(Ui.tv(ctx, "（这一场的 GPS 点无效）", 12, Ui.SUB, false));
            return;
        }
        final MapView mv = new MapView(ctx);
        mv.setTrack(t.track, r.isRide());

        android.widget.FrameLayout box = new android.widget.FrameLayout(ctx);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        box.addView(mv, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout btns = new LinearLayout(ctx);
        btns.setOrientation(LinearLayout.VERTICAL);
        android.widget.FrameLayout.LayoutParams blp = new android.widget.FrameLayout.LayoutParams(
                dp(38), ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.RIGHT | Gravity.TOP;
        blp.setMargins(0, dp(8), dp(8), 0);
        btns.setLayoutParams(blp);
        String[] labels = {"+", "–", "⦿"};
        for (int i = 0; i < 3; i++) {
            TextView b = Ui.tv(ctx, labels[i], 17, Ui.TXT, true);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.round(ctx, 0xF2FFFFFF, 0, 9));
            b.setPadding(0, dp(6), 0, dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(38), dp(38));
            lp.bottomMargin = dp(6);
            b.setLayoutParams(lp);
            final int idx = i;
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (idx == 0) mv.zoomBy(1);
                    else if (idx == 1) mv.zoomBy(-1);
                    else mv.reset();
                }
            });
            btns.addView(b);
        }
        box.addView(btns);
        mapHost.addView(box);
        mapLegend.setText(r.isRide() ? "轨迹颜色：紫→红 = 慢→快（km/h）" : "轨迹颜色：紫→红 = 慢→快（配速）");
    }

    // ---------- 佳明 ----------
    private void askLogin(final TextView btn) {
        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(Ui.dlgTitle(ctx, "登录佳明中国"));
        body.addView(Ui.dlgBody(ctx, "填佳明中国的账号密码，登录后即可直接上传这一场。"));

        final android.widget.EditText eu = Ui.field(ctx, "佳明账号（邮箱）");
        final android.widget.EditText ep = Ui.field(ctx, "密码");
        ep.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(13);
        body.addView(eu, flp);
        LinearLayout.LayoutParams flp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp2.topMargin = dp(9);
        body.addView(ep, flp2);

        final android.app.Dialog[] holder = new android.app.Dialog[1];
        body.addView(Ui.btnRow(ctx, "取消", "登录",
                new Runnable() { public void run() { holder[0].dismiss(); } },
                new Runnable() {
                    public void run() {
                        final String u = eu.getText().toString().trim();
                        final String p = ep.getText().toString();
                        if (u.length() == 0 || p.length() == 0) { toast("账号密码都要填"); return; }
                        holder[0].dismiss();
                        btn.setEnabled(false);
                        btn.setText("登录中…");
                        new Thread(new Runnable() {
                            public void run() {
                                try {
                                    String tok = Garm.login(u, p);
                                    Garm.saveToken(ctx, tok);
                                    ui.post(new Runnable() {
                                        public void run() {
                                            btn.setEnabled(true);
                                            btn.setText("上传 Garmin");
                                            toast("已登录佳明，开始上传");
                                            doUpload(btn);
                                        }
                                    });
                                } catch (final Exception e) {
                                    ui.post(new Runnable() {
                                        public void run() {
                                            btn.setEnabled(true);
                                            btn.setText("登录佳明");
                                            toast("登录失败：" + e.getMessage());
                                        }
                                    });
                                }
                            }
                        }).start();
                    }
                }));
        holder[0] = Ui.panel(ctx, body);
        holder[0].show();
    }

    private void doUpload(final TextView btn) {
        android.util.Log.i("mfit", "upload start, token=" + (Garm.token(ctx).length()) + " api=" + Garm.api);
        btn.setEnabled(false);
        btn.setText("上传中…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] fit = Fit.build(r, track, kgUsed);
                    final Garm.Result res = Garm.upload(ctx, fit, Garm.uploadName(r));
                    android.util.Log.i("mfit", "upload done code=" + res.code + " ok=" + res.ok
                            + " dup=" + res.duplicate + " body=" + res.msg);
                    ui.post(new Runnable() {
                        public void run() {
                            btn.setEnabled(true);
                            btn.setText("上传 Garmin");
                            if (!res.ok) toast("上传失败 HTTP " + res.code
                                    + (res.msg != null && res.msg.length() > 0
                                    ? "：" + res.msg.substring(0, Math.min(90, res.msg.length())) : ""));
                            else if (res.duplicate) toast("这场之前就传过，佳明里已有同一条记录");
                            else toast("已送进 Garmin Connect（HTTP " + res.code + "），刷新 App 可见");
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            btn.setEnabled(true);
                            btn.setText("上传 Garmin");
                            toast("上传失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    // ---------- 导出 ----------
    private void export() {
        android.util.Log.i("mfit", "export click, track=" + (track != null));
        expBtn.setAlpha(0.5f);
        expBtn.setText("正在生成…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] fit = Fit.build(r, track, kgUsed);
                    String name = Fit.fileName(r);
                    String where = FitStore.save(ctx, fit, name);
                    final String msg = where;
                    ui.post(new Runnable() {
                        public void run() {
                            expBtn.setAlpha(1f);
                            expBtn.setText("导出这一场");
                            toast("已保存：" + msg);
                        }
                    });
                } catch (final Exception e) {
                    android.util.Log.e("mfit", "export fail", e);
                    ui.post(new Runnable() {
                        public void run() {
                            expBtn.setAlpha(1f);
                            expBtn.setText("导出这一场");
                            toast("导出失败：" + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private String save(byte[] data, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("无法写入 Download");
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            os.write(data);
            os.close();
            return "Download/" + name;
        }
        File dir = ctx.getExternalFilesDir(null);
        File f = new File(dir, name);
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.close();
        return f.getAbsolutePath();
    }

    // ---------- 汇总卡片 ----------
    private View sectionTitle(String name, String unit) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(16), 0, dp(6));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(Ui.dot(ctx));
        row.addView(Ui.tv(ctx, name, 13, Ui.TXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (unit.length() > 0) row.addView(Ui.tv(ctx, unit, 11, Ui.SUB, false));
        return row;
    }

    private View note() {
        TextView v = Ui.tv(ctx, "除功率外，汇总数值来自小米运动健康的官方字段，未做任何推算；"
                + "功率由体重（见上方「功率体重」）+ 速度 + 坡度估算。"
                + (r.hasTrack() ? "曲线与地图由逐点轨迹直接解码。" : "这场没有逐点轨迹文件，因此没有曲线和地图。"),
                11.5f, 0xFFA8AFBB, false);
        v.setPadding(0, dp(16), 0, 0);
        return v;
    }

    private View kpiGrid() {
        List<String[]> kv = new ArrayList<String[]>();
        kv.add(new String[]{"距离", String.format("%.2f", r.dist / 1000.0), "km"});
        kv.add(new String[]{"计时", MainActivity.durText(r.timer), ""});
        if (r.isRide()) {
            kv.add(new String[]{"平均速度", r.timer > 0 ? String.format("%.1f", MainActivity.kmh(r)) : "—", "km/h"});
        } else {
            kv.add(new String[]{"平均配速", r.dist > 1000 ? MainActivity.paceText(r.timer / (r.dist / 1000.0)) : "—", ""});
        }
        kv.add(new String[]{"平均心率", r.avgHr > 0 ? String.valueOf((int) r.avgHr) : "—", "bpm"});
        kv.add(new String[]{"最大心率", r.maxHr > 0 ? String.valueOf((int) r.maxHr) : "—", "bpm"});
        if (r.isRide()) {
            kv.add(new String[]{"最高速度", String.format("%.1f", r.rep.optDouble("max_speed", 0)), "km/h"});
        } else {
            kv.add(new String[]{"平均步频", r.avgCad > 0 ? String.valueOf((int) r.avgCad) : "—", "步/分"});
            kv.add(new String[]{"最大步频", r.maxCad > 0 ? String.valueOf((int) r.maxCad) : "—", "步/分"});
        }
        kv.add(new String[]{"累计上升", String.format("%.1f", r.ascent), "m"});
        kv.add(new String[]{"累计下降", String.format("%.1f", r.descent), "m"});
        if (kgUsed > 0 && (r.isRun() || r.isWalk()))
            kv.add(new String[]{"功率体重", Weight.kg(kgInfo), ""});
        if (pwAvg > 0) {
            kv.add(new String[]{"平均功率", String.valueOf((int) Math.round(pwAvg)), "W"});
            kv.add(new String[]{"最大功率", String.valueOf((int) Math.round(pwMax)), "W"});
        }
        kv.add(new String[]{"步数", String.valueOf(r.steps), ""});
        kv.add(new String[]{"活动卡路里", String.valueOf(r.cal), "kcal"});
        kv.add(new String[]{"总卡路里", String.valueOf((int) r.rep.optDouble("total_cal", 0)), "kcal"});
        return gridOf(kv);
    }

    private View dynamicGrid() {
        List<String[]> kv = new ArrayList<String[]>();
        if (r.stride > 0) kv.add(new String[]{"平均步幅", String.format("%.0f", r.stride), "cm"});
        if (r.ct > 0) kv.add(new String[]{"平均触地", String.format("%.0f", r.ct), "ms"});
        double minCt = r.rep.optDouble("min_touchdown_duration", 0);
        if (minCt > 0) kv.add(new String[]{"最小触地", String.format("%.0f", minCt), "ms"});
        double maxCt = r.rep.optDouble("max_contact_time", 0);
        if (maxCt > 0) kv.add(new String[]{"最大触地", String.format("%.0f", maxCt), "ms"});
        if (r.vamp > 0) kv.add(new String[]{"垂直振幅", String.format("%.1f", r.vamp), "cm"});
        double maxVa = r.rep.optDouble("max_vertical_amplitude", 0);
        if (maxVa > 0) kv.add(new String[]{"最大振幅", String.format("%.1f", maxVa), "cm"});
        double minVa = r.rep.optDouble("min_vertical_amplitude", 0);
        if (minVa > 0) kv.add(new String[]{"最小振幅", String.format("%.1f", minVa), "cm"});
        if (r.vsr > 0) kv.add(new String[]{"垂直步幅比", String.format("%.1f", r.vsr), "%"});
        double maxVsr = r.rep.optDouble("max_vertical_stride_ratio", 0);
        if (maxVsr > 0) kv.add(new String[]{"最大步幅比", String.format("%.1f", maxVsr), "%"});
        return gridOf(kv);
    }

    private View gridOf(List<String[]> kv) {
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < kv.size(); i += 3) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = i; j < Math.min(i + 3, kv.size()); j++) {
                row.addView(kpiCell(kv.get(j)),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            for (int j = kv.size(); j < i + 3; j++) {
                row.addView(new View(ctx), new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(8);
            row.setLayoutParams(rlp);
            grid.addView(row);
        }
        return grid;
    }

    private View kpiCell(String[] v) {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(Ui.glass(ctx, Ui.GLASS_HI, 12));
        l.setElevation(0);
        l.setPadding(dp(9), dp(9), dp(9), dp(9));
        l.addView(Ui.tv(ctx, v[0], 11, Ui.SUB, false));
        LinearLayout num = new LinearLayout(ctx);
        num.setOrientation(LinearLayout.HORIZONTAL);
        num.setGravity(android.view.Gravity.BOTTOM);
        num.addView(Ui.tv(ctx, v[1], 16, Ui.TXT, true));
        if (v[2].length() > 0) {
            TextView u = Ui.tv(ctx, " " + v[2], 10.5f, Ui.SUB, false);
            u.setPadding(0, 0, 0, dp(2));
            num.addView(u);
        }
        l.addView(num);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        l.setLayoutParams(lp);
        return l;
    }
}
