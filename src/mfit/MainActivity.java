package com.hanxing.mifit;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 主界面：与网页版一致的两列卡片列表 */
public class MainActivity extends Activity {
    private final List<Run> all = new ArrayList<Run>();
    private final List<Run> shown = new ArrayList<Run>();
    private final Set<Long> sel = new HashSet<Long>();
    private String filter = "running";
    private int page = 60;
    private boolean selAllOn = false;

    private TextView sub, selN, moreBtn, gEntBtn;
    private volatile String btName = null;
    private LinearLayout tabsRow, grid;
    private ScrollView scroll;
    private final Handler ui = new Handler(Looper.getMainLooper());

    // 三页结构
    private FrameLayout content;
    private LinearLayout sportWrap, summaryBody;
    private FrameLayout listPage, floatWrap;
    private LinearLayout floatBar;
    private ToolIcon floatAll, garminBtn, exportIco;
    private LiquidGlass floatGlass;
    private FrameLayout tabWrap;
    private ScrollView summaryScroll;
    private HealthPage healthPage;
    private MePage mePage;
    private String tab = "sport";
    private final TextView[] tabLabel = new TextView[3];
    private final TabIconView[] tabIcon = new TabIconView[3];
    private final LinearLayout[] tabItem = new LinearLayout[3];
    private Health health;
    private boolean warnedExpired;
    private long lastSync = 0;
    private boolean loading = false;
    private boolean forceFull = false;
    private volatile boolean garmBusy = false;
    private final Handler auto = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Garm.migrate(this);          // 旧版明文令牌 → Keystore 密文
        RootShell.cleanLeftovers(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(Ui.backdrop(this));

        // ---- 一级顶栏（运动概览）----
        LinearLayout sumHead = new LinearLayout(this);
        sumHead.setOrientation(LinearLayout.VERTICAL);
        sumHead.setBackground(Ui.glassBar(this, true));
        LinearLayout sTop = new LinearLayout(this);
        sTop.setOrientation(LinearLayout.HORIZONTAL);
        sTop.setPadding(dp(16), dp(14), dp(16), dp(12));
        LinearLayout sTl = new LinearLayout(this);
        sTl.setOrientation(LinearLayout.VERTICAL);
        sTl.addView(Ui.tv(this, "运动", 17, Ui.TXT, true));
        sub = Ui.tv(this, "读取中…", 12, Ui.SUB, false);
        sub.setPadding(0, dp(3), 0, 0);
        sTl.addView(sub);
        sTop.addView(sTl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sumHead.addView(sTop);
        sumHead.addView(Ui.divider(this));

        // ---- 二级顶栏（全部记录）----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setBackground(Ui.glassBar(this, true));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(10), dp(16), dp(10));
        TextView back = Ui.tv(this, "\u2039", 22, Ui.PRIMARY, true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(10), 0, dp(14), 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeList(); }
        });
        top.addView(back);
        LinearLayout tl = new LinearLayout(this);
        tl.setOrientation(LinearLayout.VERTICAL);
        tl.addView(Ui.tv(this, "全部记录", 16, Ui.TXT, true));
        top.addView(tl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(top);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setPadding(dp(16), 0, dp(16), dp(10));
        hs.addView(tabsRow);
        head.addView(hs);
        head.addView(Ui.divider(this));

        // ---- 一级：最新记录 + 统计 + 训练状态 ----
        LinearLayout sumWrap = new LinearLayout(this);
        sumWrap.setOrientation(LinearLayout.VERTICAL);
        sumWrap.addView(sumHead);
        summaryScroll = new ScrollView(this);
        summaryBody = new LinearLayout(this);
        summaryBody.setOrientation(LinearLayout.VERTICAL);
        summaryBody.setPadding(dp(11), dp(6), dp(11), dp(92));
        summaryScroll.addView(summaryBody);
        sumWrap.addView(summaryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        sportWrap = sumWrap;

        // ---- 二级：全部记录列表 ----
        scroll = new ScrollView(this);
        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(11), dp(7), dp(11), dp(76));       // 只留到操作条上方，其余让内容露到底栏玻璃下面
        scroll.addView(grid);
        scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            public void onScrollChange(View v, int sx, int sy, int ox, int oy) {
                LiquidGlass.freeze(0);        // 滚动时也照常抓背景，玻璃内容跟着动
                if (sy > oy) {                // 往下滑到快见底，接着加载
                    View ch = scroll.getChildAt(0);
                    if (ch != null && ch.getBottom() - (scroll.getHeight() + sy) < dp(200)) {
                        loadMoreIfNeeded();
                    }
                }
            }
        });

        // ---- 选中后悬浮的竖向图标操作条（贴右边，只有图标） ----
        floatBar = new LinearLayout(this);
        floatBar.setOrientation(LinearLayout.VERTICAL);
        floatBar.setGravity(Gravity.CENTER_HORIZONTAL);
        floatBar.setPadding(dp(5), dp(6), dp(5), dp(6));

        selN = Ui.tv(this, "0", 12, Ui.ORANGE, true);
        selN.setVisibility(View.GONE);
        floatAll = new ToolIcon(this, ToolIcon.SELECT, "全选");
        garminBtn = new ToolIcon(this, ToolIcon.UPLOAD, "上传");
        exportIco = new ToolIcon(this, ToolIcon.EXPORT, "下载");
        addIcon(floatAll);
        addIcon(garminBtn);
        addIcon(exportIco);

        floatGlass = new LiquidGlass(this);
        floatGlass.setRadiusDp(30);
        floatGlass.setBlurDp(9);
        floatGlass.setElevation(0);
        floatGlass.addView(floatBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        floatWrap = new FrameLayout(this);
        floatWrap.setPadding(dp(12), dp(12), dp(12), dp(12));
        floatWrap.addView(floatGlass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        floatWrap.setClipChildren(false);
        floatWrap.setVisibility(View.GONE);
        FrameLayout.LayoutParams fwlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fwlp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        fwlp.setMargins(0, 0, dp(10), dp(18));
        floatWrap.setLayoutParams(fwlp);

        listPage = new FrameLayout(this);
        listPage.setBackground(Ui.backdrop(this));
        listPage.setVisibility(View.GONE);
        LinearLayout listCol = new LinearLayout(this);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listCol.addView(head);
        listCol.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        listPage.addView(listCol, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listPage.addView(floatWrap);

        // ---- 三页容器 ----
        content = new PullRoot(this);
        content.addView(sportWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        healthPage = new HealthPage(this);
        healthPage.setVisibility(View.GONE);
        content.addView(healthPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mePage = new MePage(this, new Runnable() {
            public void run() { load(); }
        });
        mePage.setVisibility(View.GONE);
        content.addView(mePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(listPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((PullRoot) content).setAction("正在同步最新数据…", new Runnable() {
            public void run() { load(); }
        });
        ((PullRoot) content).lift();
        // 底栏是浮在内容上的，内容铺满整屏，滚到底时进到玻璃后面
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.BOTTOM;
        tlp.setMargins(0, 0, 0, dp(18));
        root.addView(buildTabs(), tlp);

        setContentView(root);
        Edge.setup(this, root, sumHead, tabWrap, head);
        floatAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 已全选就清空，否则把当前分类下的全部选中
                boolean allOn = selAllOn && !sel.isEmpty();
                sel.clear();
                selAllOn = !allOn;
                if (selAllOn) for (Run r : shown) sel.add(r.start);
                renderGrid();
            }
        });
        garminBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (Garm.hasToken(MainActivity.this) || Garm.canAutoLogin(MainActivity.this)) batchUpload();
                else GarmUi.menu(MainActivity.this, new Runnable() { public void run() { refreshGarmin(); } });
            }
        });
        exportIco.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { batchExport(); }
        });


        load();
    }

    /** 全量同步：忽略缓存，重读整库 + 重扫轨迹 */
    public void syncFull() {
        forceFull = true;
        Cache.clear(this);
        all.clear();
        health = null;
        sub.setText("全量同步中…");
        Toast.makeText(this, "正在全量同步，场次多时要一会儿", Toast.LENGTH_SHORT).show();
        load();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private void addIcon(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(54));
        lp.topMargin = dp(2);
        floatBar.addView(v, lp);
    }

    // ---------------- 三页切换 ----------------
    private View buildTabs() {
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(12), dp(6), dp(12), dp(8));
        wrap.setClipChildren(false);
        tabWrap = wrap;

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        LiquidGlass barGlass = new LiquidGlass(this);       // 真·液态玻璃胶囊
        barGlass.setRadiusDp(32);
        barGlass.setBlurDp(14);
        barGlass.setElevation(0);
        barGlass.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] names = {"健康", "运动", "我的"};
        String[] keys = {"health", "sport", "me"};
        for (int i = 0; i < names.length; i++) {
            final String k = keys[i];
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(7), 0, dp(6));
            item.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            // 图标在文字上方，选中态变橙
            TabIconView icon = new TabIconView(this, i);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(19), dp(19));
            ilp.bottomMargin = dp(3);
            item.addView(icon, ilp);

            TextView label = Ui.tv(this, names[i], 13, Ui.SUB, true);
            label.setGravity(Gravity.CENTER);
            item.addView(label);
            item.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { switchTab(k); }
            });
            bar.addView(item);
            tabItem[i] = item;
            tabIcon[i] = icon;
            tabLabel[i] = label;
        }
        wrap.addView(barGlass, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 让健康/我的两页内容避开状态栏（它们的顶部没有玻璃顶栏）
        content.post(new Runnable() {
            public void run() {
                int top = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsets wi = content.getRootWindowInsets();
                    if (wi != null) top = wi.getInsets(android.view.WindowInsets.Type.statusBars()).top;
                } else {
                    int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                    if (id > 0) top = getResources().getDimensionPixelSize(id);
                }
                healthPage.setPadding(0, top, 0, 0);
                mePage.setPadding(0, top, 0, 0);
                mePage.refreshStats(all.size(), totalKm(), countDevices(), syncLabel());
            }
        });
        switchTab("sport");
        return wrap;
    }

    private void switchTab(String k) {
        if (!"sport".equals(k) && listPage != null
                && listPage.getVisibility() == View.VISIBLE) closeList();   // 离开运动页就收起列表
        tab = k;
        sportWrap.setVisibility("sport".equals(k) ? View.VISIBLE : View.GONE);
        updateFloat();
        healthPage.setVisibility("health".equals(k) ? View.VISIBLE : View.GONE);
        mePage.setVisibility("me".equals(k) ? View.VISIBLE : View.GONE);
        PullRoot pr = (PullRoot) content;
        boolean listShown = listPage != null && listPage.getVisibility() == View.VISIBLE;
        pr.setPage("sport".equals(k) ? (listShown ? scroll : summaryScroll)
                : ("health".equals(k) ? healthPage : mePage));
        String[] keys = {"health", "sport", "me"};
        for (int i = 0; i < keys.length; i++) {
            boolean on = keys[i].equals(k);
            tabLabel[i].setTextColor(on ? Ui.ORANGE : Ui.SUB);
            tabLabel[i].setTypeface(Font.heavy(this));
            if (tabIcon[i] != null) tabIcon[i].setActive(on);
            if (tabItem[i] != null) {
                tabItem[i].setBackground(on ? Ui.round(this, 0x2EFF7A2F, 0x5CFFFFFF, 22) : null);
            }
        }
        if ("me".equals(k)) {
            mePage.refreshAccount();
            mePage.refreshStats(all.size(), totalKm(), countDevices(), syncLabel());
        }
    }

    private String syncLabel() {
        if (all.isEmpty()) return "—";
        return timeText(all.get(0).end > 0 ? all.get(0).end : all.get(0).start);
    }

    /** 开关打开时，把比上次同步更新的一次称重推给佳明（后台线程调用） */
    private void autoSyncWeight(Health h) {
        try {
            if (h == null || !h.hasWeight || h.kg <= 0 || h.weightTs <= 0) return;
            if (!getSharedPreferences("mfit", MODE_PRIVATE).getBoolean("autoWeight", false)) return;
            if (!Garm.hasToken(this) && !Garm.canAutoLogin(this)) return;
            if (!Garm.tokenValid(this) && !Garm.canAutoLogin(this)) {
                if (!warnedExpired) {
                    warnedExpired = true;
                    ui.post(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this,
                                    "佳明登录已过期，体重没能自动同步 —— 去「我的 → 账号管理」重新登录一次",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
                return;
            }
            long done0 = getSharedPreferences("mfit", MODE_PRIVATE).getLong("weightSyncedTs", 0);
            if (h.weightTs <= done0) return;
            Garm.Result r = Garm.pushWeight(this, h.weightTs, h.kg,
                    h.bodyFat, h.muscle, h.bmr, h.bmiVal);
            android.util.Log.i("mfit", "autoWeight kg=" + h.kg + " bmi=" + h.bmiVal
                    + " code=" + r.code);
            if (r.ok) {
                getSharedPreferences("mfit", MODE_PRIVATE).edit()
                        .putLong("weightSyncedTs", h.weightTs).apply();
            }
        } catch (Exception e) {
            android.util.Log.w("mfit", "autoWeight fail " + e.getMessage());
        }
    }

    /** 打开开关时立刻推一次当前最新称重 */
    void syncWeightNow() {
        final Health h = health;
        if (h == null || !h.hasWeight) {
            Toast.makeText(this, "还没读到体重数据", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Garm.hasToken(this) && !Garm.canAutoLogin(this)) {
            Toast.makeText(this, "先登录佳明账号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Garm.tokenValid(this) && !Garm.canAutoLogin(this)) {
            Toast.makeText(this, "佳明登录已过期，点「账号管理」重新登录后再试", Toast.LENGTH_LONG).show();
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                String tmp;
                boolean ok = false;
                String back = "";
                try {
                    Garm.Result r = Garm.pushWeight(MainActivity.this, h.weightTs, h.kg,
                            h.bodyFat, h.muscle, h.bmr, h.bmiVal);
                    ok = r.ok;
                    if (ok) {
                        getSharedPreferences("mfit", MODE_PRIVATE).edit()
                                .putLong("weightSyncedTs", h.weightTs).apply();
                        back = Garm.latestWeight(MainActivity.this);
                    }
                    tmp = ok ? ("已同步 " + String.format(java.util.Locale.US, "%.1f", h.kg) + " kg"
                            + (back.length() > 0 ? " · 佳明最新 " + back : ""))
                            : (r.code == 401
                                ? "佳明登录已失效，去「我的 → 账号管理」重新登录一次"
                                : ("同步失败 " + r.code));
                } catch (Exception e) {
                    tmp = "同步失败：" + e.getMessage();
                }
                final String msg = tmp;
                final boolean fok = ok;
                ui.post(new Runnable() {
                    public void run() {
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                        if (fok) mePage.refreshStats(all.size(), totalKm(), countDevices(), syncLabel());
                    }
                });
            }
        }).start();
    }

    private void refreshGarmin() {
        boolean on = Garm.hasToken(this);
        if (gEntBtn != null) {
            gEntBtn.setTextColor(on ? Color.WHITE : Ui.ORANGE);
            gEntBtn.setBackground(Ui.glassCircle(this, on));
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private java.util.List<Run> picked() {
        java.util.List<Run> out = new ArrayList<Run>();
        for (Run r : all) if (sel.contains(r.start)) out.add(r);
        return out;
    }

    private Track trackOf(RootShell sh, Run r) {
        if (sh == null || r.recPath == null) return null;
        try {
            byte[] rec = sh.cat(r.recPath);
            byte[] gps = r.gpsPath != null ? sh.cat(r.gpsPath) : null;
            Track t = Track.analyze(rec, gps);
            return (t != null && t.selfTest) ? t : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void batchExport() {
        final java.util.List<Run> list = picked();
        if (list.isEmpty()) { toast("先在卡片右上角勾选要导出的场次"); return; }
        toast("开始导出 " + list.size() + " 场…");
        new Thread(new Runnable() {
            public void run() {
                RootShell sh = new RootShell();
                int ok = 0, bad = 0;
                try { sh.start(MainActivity.this); } catch (Exception ignored) { }
                Weight wt = Weight.cached(MainActivity.this, sh);
                for (Run r : list) {
                    try {
                        Track t = r.hasTrack() ? trackOf(sh, r) : null;
                        double[] ki = wt.at(r.start);
                        byte[] fit = Fit.build(r, t, ki != null ? ki[0] : 0);
                        FitStore.save(MainActivity.this, fit, Fit.fileName(r));
                        ok++;
                    } catch (Exception e) {
                        bad++;
                    }
                }
                final int fok = ok, fbad = bad;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("导出完成：成功 " + fok + " 场"
                                + (fbad > 0 ? "，失败 " + fbad + " 场" : "")
                                + "，文件在 Download/");
                    }
                });
            }
        }).start();
    }

    private void batchUpload() {
        final java.util.List<Run> list = picked();
        if (list.isEmpty()) { toast("先勾选要上传的场次"); return; }
        if (!Garm.hasToken(this) && !Garm.canAutoLogin(this)) { toast("先在任意一场的详情页点「登录佳明」，登录后即可批量上传"); return; }
        toast("开始上传 " + list.size() + " 场…");
        android.util.Log.i("mfit", "batch upload start n=" + list.size());
        new Thread(new Runnable() {
            public void run() {
                RootShell sh = new RootShell();
                int ok = 0, bad = 0, dup = 0;
                try { sh.start(MainActivity.this); } catch (Exception ignored) { }
                Weight wt = Weight.cached(MainActivity.this, sh);
                for (Run r : list) {
                    try {
                        Track t = r.hasTrack() ? trackOf(sh, r) : null;
                        double[] ki = wt.at(r.start);
                        byte[] fit = Fit.build(r, t, ki != null ? ki[0] : 0);
                        Garm.Result res = Garm.upload(MainActivity.this, fit, Garm.uploadName(r));
                        android.util.Log.i("mfit", "batch item " + r.start + " code=" + res.code
                                + " ok=" + res.ok + " dup=" + res.duplicate);
                        if (res.ok) { ok++; if (res.duplicate) dup++; } else bad++;
                    } catch (Exception e) {
                        bad++;
                    }
                }
                final int fok = ok, fbad = bad, fdup = dup;
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast("上传完成：成功 " + fok + " 场"
                                + (fdup > 0 ? "（其中 " + fdup + " 场早就传过）" : "")
                                + (fbad > 0 ? "，失败 " + fbad + " 场" : ""));
                    }
                });
            }
        }).start();
    }

    /** 有新运动记录时自动传到佳明：只传跑步/骑行/步行，只传上次同步之后的新记录 */
    void autoUploadGarmin(final List<Run> list) {
        if (!getSharedPreferences("mfit", MODE_PRIVATE).getBoolean("garmAuto", true)) return;
        if (!Garm.hasToken(this) && !Garm.canAutoLogin(this)) return;
        if (list == null || list.isEmpty()) return;
        final long lastSid = getSharedPreferences("mfit", MODE_PRIVATE).getLong("garmSid", 0);
        long newest = list.get(0).start;
        if (newest <= 0) return;
        if (lastSid == 0) {
            // 首次启用：以「次新」一场为基线，不把历史几百场一次推上去，
            // 同时留出最新一场，下次启动就会把它补传上去
            long base0 = list.size() >= 2 ? list.get(1).start : newest;
            getSharedPreferences("mfit", MODE_PRIVATE).edit()
                    .putLong("garmSid", base0).apply();
            android.util.Log.i("mfit", "garmAuto baseline=" + base0 + " newest=" + newest);
            return;
        }
        if (newest <= lastSid || garmBusy) return;

        final List<Run> todo = new ArrayList<Run>();
        for (Run r : list) {
            if (r.start <= lastSid) break;                 // list 按时间倒序
            if (r.isRun() || r.isRide() || r.isWalk()) todo.add(r);
        }
        if (todo.isEmpty()) return;
        Collections.reverse(todo);                          // 从旧到新：中途失败不会漏掉更早的
        garmBusy = true;
        android.util.Log.i("mfit", "garmAuto start n=" + todo.size());
        new Thread(new Runnable() {
            public void run() {
                int ok = 0, bad = 0;
                RootShell sh = new RootShell();
                boolean ready = false;
                try { ready = sh.start(MainActivity.this); } catch (Exception ignored) { }
                Weight wt = ready ? Weight.cached(MainActivity.this, sh) : null;
                long progressed = lastSid;
                for (Run r : todo) {
                    try {
                        Track t = (ready && r.hasTrack()) ? trackOf(sh, r) : null;
                        double[] ki = wt != null ? wt.at(r.start) : null;
                        byte[] fit = Fit.build(r, t, ki != null ? ki[0] : 0);
                        Garm.Result res = Garm.upload(MainActivity.this, fit, Garm.uploadName(r));
                        android.util.Log.i("mfit", "garmAuto item " + r.start + " code=" + res.code
                                + " ok=" + res.ok + " dup=" + res.duplicate);
                        if (res.ok) {
                            ok++;
                            progressed = Math.max(progressed, r.start);
                            getSharedPreferences("mfit", MODE_PRIVATE).edit()
                                    .putLong("garmSid", progressed)
                                    .putLong("garmTs", System.currentTimeMillis()).apply();
                        } else {
                            bad++;
                            if (res.code == 401) break;     // 登录失效就别硬刷
                        }
                    } catch (Exception e) {
                        bad++;
                        android.util.Log.w("mfit", "garmAuto item fail " + e.getMessage());
                    }
                }
                try { sh.stop(); } catch (Exception ignored) { }
                garmBusy = false;
                final int fok = ok, fbad = bad;
                ui.post(new Runnable() {
                    public void run() {
                        if (fok > 0) toast("已自动上传 " + fok + " 场到佳明"
                                + (fbad > 0 ? "，" + fbad + " 场失败" : ""));
                        mePage.refreshStats(all.size(), totalKm(), countDevices(), syncLabel());
                    }
                });
            }
        }).start();
    }

    /** 打开开关时就地补传一次 */
    void autoUploadNow() {
        autoUploadGarmin(new ArrayList<Run>(all));
    }

    // ---------------- 数据 ----------------
    private void load() {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            public void run() {
                // 1) 本地缓存先垫上：解析放到后台，打开 App 不被它卡住首屏
                List<Run> seed = all;
                if (all.isEmpty()) {
                    final List<Run> cached = Cache.readRuns(MainActivity.this);
                    seed = cached;
                    if (!cached.isEmpty()) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (!all.isEmpty()) return;
                                all.addAll(cached);
                                sub.setText("本地缓存 " + all.size() + " 场 · 后台同步中…");
                                renderTabs();
                                if ("sport".equals(tab)) renderSummary();
                                if (listPage.getVisibility() == View.VISIBLE) renderGrid();
                            }
                        });
                    }
                }
                if (health == null) {
                    final Health ch = Cache.readHealth(MainActivity.this);
                    if (ch != null) {
                        ui.post(new Runnable() {
                            public void run() {
                                if (health != null) return;
                                health = ch;
                                healthPage.render(health, "缓存数据，后台更新中");
                                if ("sport".equals(tab)) renderSummary();
                            }
                        });
                    }
                }
                final RootShell sh = new RootShell();
                if (!sh.start(MainActivity.this)) {
                    loading = false;
                    ui.post(new Runnable() {
                        public void run() {
                            sub.setText("拿不到 root：需要 Magisk/KernelSU 授权才能直读小米运动健康的数据库");
                        }
                    });
                    return;
                }
                long t0 = System.currentTimeMillis();
                // 手表型号：本地库没有设备型号字段，从系统蓝牙配对表里取（只取一次，结果落盘复用）
                if (btName == null) btName = getSharedPreferences("mfit", MODE_PRIVATE).getString("btName", null);
                if (btName != null && mePage != null) mePage.setDeviceName(btName);
                if (btName == null) {
                    try {
                        String dump = sh.raw("/system/bin/dumpsys bluetooth_manager | /system/bin/grep -E '0x001F00|Watch|Band'");
                        for (String ln : dump.split("\n")) {
                            if (ln.contains("耳机") || ln.contains("Headset") || ln.contains("AirDots")
                                    || ln.contains("Redmi Buds")) continue;
                            int p = ln.lastIndexOf("] ");
                            if (p <= 0) continue;
                            String nm = ln.substring(p + 2).trim();
                            if (nm.length() == 0) continue;
                            btName = nm;
                            getSharedPreferences("mfit", MODE_PRIVATE).edit()
                                    .putString("btName", nm).apply();
                            ui.post(new Runnable() {
                                public void run() { mePage.setDeviceName(btName); }
                            });
                            break;
                        }
                    } catch (Exception ignored) { }
                }
                try {
                    long tb0 = System.currentTimeMillis();
                    String dbPath = Server.dbPrivate(sh);
                    if (sh.size(dbPath) <= 0)
                        throw new Exception("没找到小米运动健康的数据库，请确认已安装并登录过小米运动健康");
                    Db db = new Db(sh, dbPath);
                    db.open();
                    long tb1 = System.currentTimeMillis();

                    // 先看今天有没有新记录：只读表尾几行，很快
                    long newest = 0;
                    for (Map<String, Object> row : db.readTail("sport_report", 8)) {
                        Object del = row.get("isDeleted");
                        if (del instanceof Long && ((Long) del).longValue() == 1) continue;
                        Object v = row.get("value");
                        if (!(v instanceof String)) continue;
                        try {
                            long st = new org.json.JSONObject((String) v).optLong("start_time", 0);
                            newest = Math.max(newest, st);
                        } catch (Exception ignored) { }
                    }
                    long cachedNewest = seed.isEmpty() ? 0 : seed.get(0).start;
                    final List<Run> base = new ArrayList<Run>(seed);
                    final boolean force = forceFull;
                    final boolean full = force || base.isEmpty() || newest > cachedNewest;
                    forceFull = false;
                    android.util.Log.i("mfit", "stage db=" + (tb1 - tb0) + "ms tail="
                            + (System.currentTimeMillis() - tb1) + "ms full=" + full);

                    if (full) {
                        long f0 = System.currentTimeMillis();
                        // 有新场次时只读表尾几百行，跟缓存合并；只有用户点「同步全量数据」或首次才整表读
                        boolean inc = !force && !base.isEmpty() && newest > 0;
                        List<Map<String, Object>> rows = inc
                                ? db.readTail("sport_report", 300)
                                : db.readTableObjects("sport_report");
                        long f1 = System.currentTimeMillis();
                        Map<Long, TrackIdx.File2> idx = TrackIdx.scan(sh);
                        long f2 = System.currentTimeMillis();
                        final List<Run> list = new ArrayList<Run>();
                        long oldestRead = Long.MAX_VALUE;
                        for (Map<String, Object> row : rows) {
                            Object del = row.get("isDeleted");
                            if (del instanceof Long && ((Long) del).longValue() == 1) continue;
                            Run r = Run.from(row);
                            if (r == null) continue;
                            oldestRead = Math.min(oldestRead, r.start);
                            if (inc && r.start <= cachedNewest) continue;
                            TrackIdx.File2 f = TrackIdx.match(idx, r.start);
                            if (f != null) {
                                r.trackTs = f.ts;
                                r.trackProto = f.proto;
                                r.recPath = f.recordPath;
                                r.gpsPath = f.gpsPath;
                            }
                            list.add(r);
                        }
                        // 增量读到的还不够老，说明新记录比 300 行更多，回退整表
                        if (inc && rows.size() >= 300 && oldestRead > cachedNewest) {
                            list.clear();
                            for (Map<String, Object> row : db.readTableObjects("sport_report")) {
                                Object del = row.get("isDeleted");
                                if (del instanceof Long && ((Long) del).longValue() == 1) continue;
                                Run r = Run.from(row);
                                if (r == null) continue;
                                TrackIdx.File2 f = TrackIdx.match(idx, r.start);
                                if (f != null) {
                                    r.trackTs = f.ts;
                                    r.trackProto = f.proto;
                                    r.recPath = f.recordPath;
                                    r.gpsPath = f.gpsPath;
                                }
                                list.add(r);
                            }
                        } else if (inc) {
                            list.addAll(base);     // 缓存里的旧记录原样保留
                        }
                        Collections.sort(list, new Comparator<Run>() {
                            public int compare(Run a, Run c) { return Long.compare(c.start, a.start); }
                        });
                        long f3 = System.currentTimeMillis();
                        android.util.Log.i("mfit", "full rows=" + rows.size() + " read=" + (f1 - f0)
                                + "ms scan=" + (f2 - f1) + "ms build=" + (f3 - f2) + "ms");
                        final long ms = System.currentTimeMillis() - t0;
                        Cache.writeRuns(MainActivity.this, list, ms);
                        ui.post(new Runnable() {
                            public void run() {
                                all.clear();
                                all.addAll(list);
                                sub.setText(all.size() + " 场 · "
                                        + String.format("%.2f", totalKm()) + " km · 最后同步 "
                                        + MainActivity.this.clockText());
                                renderTabs();
                                renderGrid();
                                mePage.refreshStats(all.size(), totalKm(), countDevices(), syncLabel());
                            }
                        });
                        autoUploadGarmin(list);
                    } else {
                        autoUploadGarmin(base);
                    }

                    // 生理数据（心率/睡眠/血氧/体重/摄氧量/负荷）
                    long th0 = System.currentTimeMillis();
                    final Health h = Health.load(db);
                    long th1 = System.currentTimeMillis();
                    health = h;
                    Cache.writeHealth(MainActivity.this, h);
                    long th2 = System.currentTimeMillis();
                    autoSyncWeight(h);
                    android.util.Log.i("mfit", "health hr=" + h.hr.size() + " spo2=" + h.spo2.size()
                            + " steps=" + h.steps + " sleep=" + h.hasSleep + " kg=" + h.kg
                            + " vo2=" + h.vo2max + " load=" + h.loadWeek + " err=" + h.err
                            + " | load=" + (th1 - th0) + "ms cache=" + (th2 - th1) + "ms"
                            + " total=" + (th2 - t0) + "ms dd=" + db.ioCount);
                    ui.post(new Runnable() {
                        public void run() {
                            String t = MainActivity.this.clockText();
                            healthPage.render(h, full ? ("最后同步 " + t) : (t + " · 已是最新"));
                            ((PullRoot) content).stop();
                            if (!full) {
                                sub.setText(all.size() + " 场 · " + String.format("%.2f", totalKm())
                                        + " km · 最后同步 " + MainActivity.this.clockText());
                            }
                            if ("sport".equals(tab)) {
                                renderSummary();
                                if (listPage.getVisibility() == View.VISIBLE) renderGrid();
                            }
                            mePage.refreshStats(all.size(), totalKm(), countDevices(), syncLabel());
                        }
                    });
                } catch (final Exception e) {
                    final String diag = sh.ready()
                            ? sh.raw("echo id=$(id); echo se=$(cat /proc/self/attr/current); ls -l /data/data/com.mi.health/databases/ 2>&1")
                            : "(shell 未就绪)";
                    ui.post(new Runnable() {
                        public void run() { sub.setText("读取失败：" + e.getMessage() + "\n" + diag); }
                    });
                } finally {
                    loading = false;
                }
            }
        }).start();
    }

    private String clockText() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        scheduleAuto();
    }

    @Override
    protected void onPause() {
        super.onPause();
        auto.removeCallbacksAndMessages(null);
    }

    /** 每 90 秒静默比对一次，有新记录自动刷新，不用手点刷新 */
    private void scheduleAuto() {
        auto.removeCallbacksAndMessages(null);
        auto.postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing()) load();
                scheduleAuto();
            }
        }, 90000);
    }


    // ---------------- 筛选 ----------------
    private boolean pass(Run r, String k) {
        if ("all".equals(k)) return true;
        if ("running".equals(k)) return r.isRun();
        if ("riding".equals(k)) return r.isRide();
        if ("walking".equals(k)) return r.isWalk();
        return r.key != null && r.key.matches(".*(training|fitness|taichi|rope|rowing|climbing).*");
    }

    private int countTracks() {
        int n = 0;
        for (Run r : all) if (r.hasTrack()) n++;
        return n;
    }

    private double totalKm() {
        double s = 0;
        for (Run r : all) s += r.dist;
        return s / 1000.0;
    }

    private int countDevices() {
        HashSet<String> s = new HashSet<String>();
        for (Run r : all) s.add(String.valueOf(r.sid));
        return s.size();
    }

    // ---------------- 渲染 ----------------
    private void renderTabs() {
        tabsRow.removeAllViews();
        String[] keys = {"running", "riding", "walking", "training", "all"};
        String[] names = {"跑步", "骑行", "步行", "健身", "全部"};
        for (int i = 0; i < keys.length; i++) {
            int n = 0;
            for (Run r : all) if (pass(r, keys[i])) n++;
            if (n == 0 && !"all".equals(keys[i])) continue;
            TextView t = Ui.pill(this, names[i] + " " + n, keys[i].equals(filter));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(7);
            t.setLayoutParams(lp);
            final String k = keys[i];
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { filter = k; page = 60; renderTabs(); renderGrid(); }
            });
            tabsRow.addView(t);
        }
    }

    // ---------------- 一级：最新一场 + 入口 ----------------
    private void renderSummary() {
        if (summaryBody == null) return;
        summaryBody.removeAllViews();
        if (all.isEmpty()) {
            TextView e = Ui.tv(this, "还没有读到运动记录", 13, Ui.SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(52), 0, dp(52));
            summaryBody.addView(e);
        } else {
            TextView t = Ui.tv(this, "最新一场", 12, Ui.SUB, false);
            t.setPadding(dp(5), dp(2), 0, dp(2));
            summaryBody.addView(t);
            View holder = card(all.get(0));
            if (holder instanceof FrameLayout) {
                View tick = ((FrameLayout) holder).getChildAt(1);
                if (tick != null) tick.setVisibility(View.GONE);   // 一级页不勾选，点进去看详情
            }
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.setMargins(dp(5), 0, dp(5), dp(5));
            holder.setLayoutParams(hlp);
            summaryBody.addView(holder);
        }

        TextView more = Ui.btn(this, "全部 " + all.size() + " 场记录", false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(5), dp(8), dp(5), dp(8));
        more.setLayoutParams(mlp);
        more.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openList(); }
        });
        summaryBody.addView(more);

        summaryBody.addView(statsCard());
        if (health != null) summaryBody.addView(abilityCard());
    }

    private void openList() {
        if (listPage == null) return;
        listPage.setVisibility(View.VISIBLE);
        listPage.bringToFront();
        ((PullRoot) content).lift();
        if (grid.getChildCount() == 0) renderGrid();
        ((PullRoot) content).setPage(scroll);
        if (tabWrap != null) tabWrap.setVisibility(View.GONE);   // 二级页不摆底栏
        grid.setPadding(dp(11), dp(7), dp(11), dp(14));
        updateFloat();
    }

    @Override
    public void onBackPressed() {
        if (listPage != null && listPage.getVisibility() == View.VISIBLE) {
            closeList();
            return;
        }
        super.onBackPressed();
    }

    private void closeList() {
        if (listPage == null) return;
        listPage.setVisibility(View.GONE);
        ((PullRoot) content).setPage(summaryScroll);
        if (tabWrap != null) tabWrap.setVisibility(View.VISIBLE);
        grid.setPadding(dp(11), dp(7), dp(11), dp(76));
        updateFloat();
    }
    // ---------------- 运动页「运动统计」 ----------------
    private View statsCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(14), dp(12), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), dp(5), dp(5), dp(7));
        c.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.dot(this));
        head.addView(Ui.tv(this, "运动统计", 13.5f, Ui.TXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(head);

        long totalSec = 0;
        double totalKm = 0;
        for (Run r : all) { totalSec += r.timer; totalKm += r.dist; }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(metric("场次", String.valueOf(all.size()), "", "全部"));
        row.addView(metric("总距离", String.format("%.2f", totalKm / 1000.0), "km",
                all.isEmpty() ? "无记录" : "合计"));
        row.addView(metric("总时长", String.format("%.0f", totalSec / 3600.0), "小时",
                all.isEmpty() ? "无记录" : "合计"));
        c.addView(row);

        // 本月
        java.util.Calendar now = java.util.Calendar.getInstance();
        int my = now.get(java.util.Calendar.YEAR), mm = now.get(java.util.Calendar.MONTH);
        int cnt = 0;
        double kmNow = 0;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        for (Run r : all) {
            cal.setTimeInMillis(r.start * 1000L);
            if (cal.get(java.util.Calendar.YEAR) == my && cal.get(java.util.Calendar.MONTH) == mm) {
                cnt++;
                kmNow += r.dist;
            }
        }
        TextView month = Ui.tv(this, "本月 " + cnt + " 场 · " + String.format("%.2f", kmNow / 1000.0) + " km",
                12, Ui.MID, false);
        month.setPadding(0, dp(9), 0, dp(2));
        c.addView(month);

        // 分类
        Flow cats = new Flow(this, 5);
        cats.setPadding(0, dp(7), 0, 0);
        cats.addView(Ui.chip(this, "跑步 " + countType("running"), false));
        cats.addView(Ui.chip(this, "骑行 " + countType("riding"), false));
        cats.addView(Ui.chip(this, "步行 " + countType("walking"), false));
        cats.addView(Ui.chip(this, "健身 " + countType("fitness"), false));
        c.addView(cats);
        return c;
    }

    private int countType(String k) {
        int n = 0;
        for (Run r : all) if (pass(r, k)) n++;
        return n;
    }

    // ---------------- 运动页顶部的「能力状态」 ----------------
    private View abilityCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(this, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(14), dp(12), dp(14), dp(13));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(5), dp(5), dp(5), dp(7));
        c.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.dot(this));
        head.addView(Ui.tv(this, "训练状态", 13.5f, Ui.TXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Ui.tv(this, "小米运动健康算法", 10.5f, Ui.SUB, false));
        c.addView(head);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setPadding(0, dp(10), 0, dp(2));
        metrics.addView(metric("最大摄氧量", health.vo2max > 0 ? String.valueOf(health.vo2max) : "—",
                "ml/kg/min", health.vo2max > 0 ? "等级 " + health.vo2level : "无数据"));
        metrics.addView(metric("跑步能力", health.ability > 0 ? String.format("%.1f", health.ability) : "—",
                "", health.ability > 0 ? "等级 " + health.abilityLevel : "无数据"));
        metrics.addView(metric("7 天负荷", health.loadWeek > 0 ? String.valueOf(health.loadWeek) : "—",
                "", health.loadLevel > 0 ? "负荷等级 " + health.loadLevel : "无数据"));
        c.addView(metrics);

        // 训练负荷柱状（每天的实际训练负荷）
        if (!health.loadTrend.isEmpty()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            row.addView(Ui.tv(this, "每日训练负荷", 11.5f, Ui.SUB, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(Ui.tv(this, "今天 " + (health.loadToday > 0 ? health.loadToday : "—"),
                    11.5f, Ui.SUB, false));
            c.addView(row);

            MiniChart mc = new MiniChart(this);
            mc.setBar(true);
            mc.setAxis(true);
            mc.setColor(0xFF2B6BE0);
            double hi = 1;
            for (double[] p : health.loadTrend) hi = Math.max(hi, p[1]);
            mc.setData(health.loadTrend, 0, hi * 1.15);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(66));
            mlp.topMargin = dp(6);
            c.addView(mc, mlp);
        }

        // 成绩预测
        if (health.p5k > 0) {
            LinearLayout pred = new LinearLayout(this);
            pred.setOrientation(LinearLayout.HORIZONTAL);
            pred.setPadding(0, dp(10), 0, 0);
            pred.addView(predItem("5K", health.p5k));
            pred.addView(predItem("10K", health.p10k));
            pred.addView(predItem("半马", health.pHalf));
            pred.addView(predItem("全马", health.pFull));
            c.addView(pred);
        }
        return c;
    }

    private View metric(String title, String big, String unit, String sub) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(Ui.tv(this, title, 11, Ui.SUB, false));

        LinearLayout num = new LinearLayout(this);
        num.setOrientation(LinearLayout.HORIZONTAL);
        num.setGravity(Gravity.BOTTOM);
        num.setPadding(0, dp(2), 0, 0);
        num.addView(Ui.tv(this, big, 24, Ui.TXT, true));
        if (unit.length() > 0) {
            TextView u = Ui.tv(this, " " + unit, 9.5f, Ui.SUB, false);
            u.setPadding(0, 0, 0, dp(4));
            num.addView(u);
        }
        c.addView(num);
        c.addView(Ui.tv(this, sub, 10.5f, Ui.SUB, false));
        return c;
    }

    private View predItem(String name, int sec) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        c.addView(Ui.tv(this, name, 11, Ui.SUB, false));
        c.addView(Ui.tv(this, sec > 0 ? durText(sec) : "—", 14, Ui.MID, true));
        return c;
    }

    private void renderGrid() {
        shown.clear();
        for (Run r : all) if (pass(r, filter)) shown.add(r);
        grid.removeAllViews();
        if (shown.isEmpty()) {
            TextView e = Ui.tv(this, "这一分类下没有记录", 14, Ui.SUB, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(56), 0, dp(56));
            grid.addView(e);
            updateFloat();
            return;
        }
        int n = Math.min(shown.size(), page);
        for (int i = 0; i < n; i++) grid.addView(card(shown.get(i)));
        updateFloat();
    }

    private boolean appending = false;

    /** 划到底自动续上后面的记录，不再点按钮 */
    private void loadMoreIfNeeded() {
        if (appending || grid == null || shown == null) return;
        int cur = grid.getChildCount();
        if (cur <= 0 || cur >= shown.size()) return;
        appending = true;
        try {
            int target = Math.min(shown.size(), page + 60);
            for (int i = cur; i < target; i++) grid.addView(card(shown.get(i)));
            page = Math.max(page, target);
            updateFloat();
        } finally {
            appending = false;
        }
    }

    /** 只在有选中且停在列表页时，露出悬浮的三个按钮 */
    private void updateFloat() {
        if (floatWrap == null) return;
        boolean listShown = listPage != null && listPage.getVisibility() == View.VISIBLE;
        boolean show = listShown && "sport".equals(tab) && !sel.isEmpty();
        floatWrap.setVisibility(show ? View.VISIBLE : View.GONE);
        if (grid != null) {
            // 没操作条时内容一直落到底栏玻璃下面；有操作条才让出高度，保证刻度可点
            int pb = show ? dp(76) : dp(12);
            if (grid.getPaddingBottom() != pb) {
                grid.setPadding(grid.getPaddingLeft(), grid.getPaddingTop(),
                        grid.getPaddingRight(), pb);
            }
        }
        if (show) {
            if (selN != null) selN.setText(String.valueOf(sel.size()));
            if (floatAll != null) floatAll.setActive(selAllOn && !sel.isEmpty());
        }
    }

    private View card(final Run r) {
        FrameLayout holder = new FrameLayout(this);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(dp(6), dp(4), dp(6), dp(4));
        holder.setLayoutParams(hlp);

        final boolean on = sel.contains(r.start);
        final LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(on ? Ui.round(this, 0xF9FFFFFF, Ui.ORANGE_BORDER, 16)
                : Ui.glass(this, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 第一行：时间 + 类型（有轨迹时同一个蓝色标签里标出）
        Flow when = new Flow(this, 6);
        when.setPadding(0, 0, dp(32), 0);
        when.addView(Ui.tv(this, dateText(r.start), 11.5f, Ui.SUB, false));
        when.addView(Ui.tag(this, r.hasTrack() ? r.label() + " · 轨迹" : r.label(), r.hasTrack()));
        c.addView(when);

        // 第二行：距离（大）+ 右侧时长 · 配速
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(3), 0, 0);

        String km = String.format("%.2f", r.dist / 1000.0);
        SpannableString dist = new SpannableString(km + " km");
        dist.setSpan(new AbsoluteSizeSpan(dpS(12)), km.length() + 1, dist.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        dist.setSpan(new ForegroundColorSpan(Ui.SUB), km.length() + 1, dist.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView big = Ui.tv(this, "", 22, Ui.TXT, true);
        big.setText(dist);
        big.setSingleLine(true);
        big.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(big);

        String mid = durText(r.timer) + " · " + (r.isRide()
                ? (r.timer > 0 ? String.format("%.1f km/h", kmh(r)) : "—")
                : (r.dist > 1000 ? paceText(r.timer / (r.dist / 1000.0)) : "—"));
        TextView midTv = Ui.tv(this, mid, 12, Ui.MID, false);
        midTv.setSingleLine(true);
        midTv.setGravity(Gravity.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        mlp.leftMargin = dp(8);
        midTv.setLayoutParams(mlp);
        line.addView(midTv);
        c.addView(line);

        // 小标签
        Flow chips = new Flow(this, 5);
        chips.setPadding(0, dp(9), 0, 0);
        boolean any = false;
        if (r.avgHr > 0) { chips.addView(Ui.chip(this, "心率 " + (int) r.avgHr, false)); any = true; }
        if (r.isRun() && r.avgCad > 0) { chips.addView(Ui.chip(this, "步频 " + (int) r.avgCad, false)); any = true; }
        if (!r.isRun() && !r.isRide() && r.avgCad > 0) { chips.addView(Ui.chip(this, "步频 " + (int) r.avgCad, false)); any = true; }
        if (r.ascent > 0) { chips.addView(Ui.chip(this, "爬升 " + (int) Math.round(r.ascent) + "m", true)); any = true; }
        if (r.cal > 0) { chips.addView(Ui.chip(this, r.cal + " kcal", false)); any = true; }
        if (!any) chips.addView(Ui.chip(this, "无明细", false));
        c.addView(chips);

        // 勾选圈：点它就选中/取消，不动列表滚动位置
        final TextView tick = Ui.tv(this, on ? "✓" : "", 13, Color.WHITE, true);
        tick.setGravity(Gravity.CENTER);
        tick.setBackground(on ? Ui.round(this, Ui.PRIMARY, 0, 999)
                : Ui.round(this, 0xF2FFFFFF, 0xFFC4CCD8, 999));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(26), dp(26));
        tlp.gravity = Gravity.TOP | Gravity.RIGHT;
        tlp.setMargins(0, dp(9), dp(9), 0);
        tick.setElevation(0);      
        tick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean now = !sel.contains(r.start);
                if (now) sel.add(r.start); else sel.remove(r.start);
                selAllOn = false;
                tick.setText(now ? "✓" : "");
                tick.setBackground(now ? Ui.round(MainActivity.this, Ui.PRIMARY, 0, 999)
                        : Ui.round(MainActivity.this, 0xF2FFFFFF, 0xFFC4CCD8, 999));
                c.setBackground(now ? Ui.round(MainActivity.this, 0xF9FFFFFF, Ui.ORANGE_BORDER, 16)
                        : Ui.glass(MainActivity.this, Ui.GLASS_HI, 16));
                updateFloat();
            }
        });

        holder.addView(c);
        holder.addView(tick, tlp);
        holder.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { DetailActivity.show(MainActivity.this, r); }
        });
        return holder;
    }

    private int dpS(int sp) { return (int) (sp * getResources().getDisplayMetrics().scaledDensity); }

    static double kmh(Run r) { return r.timer > 0 ? (r.dist / 1000.0) / (r.timer / 3600.0) : 0; }

    static String paceText(double secPerKm) {
        if (secPerKm <= 0) return "—";
        int s = (int) Math.round(secPerKm);
        return (s / 60) + "'" + String.format("%02d", s % 60) + "\"";
    }

    static String durText(int sec) {
        int h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    static String dateText(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts * 1000);
        return String.format("%d-%02d-%02d %02d:%02d", c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    static String timeText(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts * 1000);
        return String.format("%02d-%02d %02d:%02d", c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE));
    }
}
