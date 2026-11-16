package com.hanxing.mifit;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 健康页：心率 / 睡眠 / 血氧 / 压力 / 步数 / 体重 */
public class HealthPage extends ScrollView {
    private final Activity a;
    private final LinearLayout body;
    private TextView stamp;

    private static final int C_HR = 0xFFE8463C;
    private static final int C_SPO2 = 0xFF14A0C0;
    private static final int C_SLEEP = 0xFF6B5BD2;
    private static final int C_STEP = 0xFF2AA45C;
    private static final int C_STRESS = 0xFF2B6BE0;
    private static final int C_WEIGHT = 0xFFFF7A2F;
    private static final int C_FAT = 0xFF9254DE;

    public HealthPage(Activity act) {
        super(act);
        this.a = act;
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(6), dp(12), dp(92));
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        empty("读取中…");
    }

    private int dp(float v) { return Ui.dp(a, v); }

    private void empty(String msg) {
        body.removeAllViews();
        TextView t = Ui.tv(a, msg, 13, Ui.SUB, false);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(70), 0, dp(70));
        body.addView(t);
    }

    /** 用一份数据整页重画 */
    public void render(Health h, String sync) {
        body.removeAllViews();
        stamp = Ui.tv(a, sync, 11.5f, Ui.SUB, false);
        stamp.setPadding(dp(4), 0, 0, dp(9));
        body.addView(stamp);

        if (h == null) { empty("还没有读到数据，下拉刷新或检查 root 授权"); return; }

        // ---------- 今日 ----------
        sectionTitle("今日体征", "小米运动健康 · 官方字段");
        LinearLayout r1 = row();
        r1.addView(kpiCard("心率", h.hrNow > 0 ? String.valueOf(h.hrNow) : "—", "bpm",
                h.hrMin > 0 ? "最低 " + h.hrMin + " · 最高 " + h.hrMax : "今日还没有采样",
                C_HR, null), half());
        r1.addView(kpiCard("血氧", h.spo2Now > 0 ? String.valueOf(h.spo2Now) : "—", "%",
                h.spo2Min > 0 ? "最低 " + h.spo2Min + " · 均 " + h.spo2Avg : "今日还没有采样",
                C_SPO2, null), half());
        body.addView(r1);

        LinearLayout r2 = row();
        r2.addView(kpiCard("步数", fmt(h.steps), "步",
                String.format("%.2f km · %d kcal", h.stepDistM / 1000.0, h.stepCal),
                C_STEP, null), half());
        r2.addView(kpiCard("压力", h.stressNow > 0 ? String.valueOf(h.stressNow) : "—", "",
                h.stressAvg > 0 ? "均 " + h.stressAvg + " · 有效站立 " + h.standHours + "h" : "今日还没有采样",
                C_STRESS, null), half());
        body.addView(r2);

        if (h.hasWeight) {
            LinearLayout r3 = row();
            String sub = "";
            if (h.muscle > 0) sub = String.format("肌肉 %.1fkg", h.muscle);
            if (h.bmr > 0) sub += (sub.length() > 0 ? " · " : "") + "基础代谢 " + h.bmr;
            r3.addView(kpiCard("体重", String.format("%.2f", h.kg), "kg",
                    sub.length() == 0 ? "只有体重，没有体成分" : sub, C_WEIGHT, null), half());
            if (h.bodyFat > 0) {
                String sub2 = "";
                if (h.bmiVal > 0) sub2 = String.format("BMI %.1f", h.bmiVal);
                r3.addView(kpiCard("体脂率", String.format("%.1f", h.bodyFat), "%", sub2, C_FAT, null), half());
            } else {
                r3.addView(new View(a), half());
            }
            body.addView(r3);
        }

        // ---------- 心率曲线 ----------
        if (!h.hr.isEmpty()) {
            List<double[]> ser = down(h.hr, 120);
            double lo = h.hrMin > 0 ? h.hrMin : 40, hi = h.hrMax > 0 ? h.hrMax : 160;
            body.addView(chartCard("今日心率", ser, lo - 5, hi + 5, C_HR,
                    spanText(h.hr) + " · 共 " + h.hr.size() + " 条，每格约 " + Math.max(1, h.hr.size() / 120) + " 分钟"));
        }

        // ---------- 睡眠 ----------
        sectionTitle("最近一晚睡眠", h.hasSleep ? endTimeText(h.sleepEnd) : "");
        if (h.hasSleep) body.addView(sleepCard(h));
        else body.addView(plain("库里没有可用的睡眠记录"));

        // ---------- 睡眠 HRV ----------
        if (h.hrvTrend.size() > 1) body.addView(hrvCard(h));

        // ---------- 血氧曲线 ----------
        if (!h.spo2.isEmpty()) {
            List<double[]> ser = down(h.spo2, 100);
            body.addView(chartCard("今日血氧", ser, 88, 100, C_SPO2, spanText(h.spo2) + " · 共 " + h.spo2.size() + " 条"));
        }

        // ---------- 体重趋势 ----------
        if (h.hasWeight && h.weightTrend.size() > 1) {
            List<double[]> ser = new ArrayList<double[]>(h.weightTrend);
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (double[] p : ser) { lo = Math.min(lo, p[1]); hi = Math.max(hi, p[1]); }
            body.addView(chartCard("体重趋势", ser, lo - 1, hi + 1, C_WEIGHT,
                    "最近 " + ser.size() + " 次称重"));
        }

        TextView foot = Ui.tv(a, "以上数值全部来自小米运动健康本地库的原始字段，未做任何推算；库里没有的指标不显示。",
                11, Ui.SUB, false);
        foot.setPadding(dp(4), dp(14), dp(4), 0);
        body.addView(foot);
    }

    // ---------------- 组件 ----------------
    private void sectionTitle(String title, String right) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(4), dp(10), dp(4), dp(6));
        r.addView(Ui.dot(a));
        r.addView(Ui.tv(a, title, 13, Ui.TXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (right != null && right.length() > 0) r.addView(Ui.tv(a, right, 11, Ui.SUB, false));
        body.addView(r);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private LinearLayout.LayoutParams half() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private View kpiCard(String title, String big, String unit, String sub, int color, String tag) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(a, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(13), dp(11), dp(13), dp(12));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(a);
        dot.setBackground(Ui.round(a, color, 0, 999));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(6);
        head.addView(dot, dlp);
        head.addView(Ui.tv(a, title, 12, Ui.SUB, false));
        c.addView(head);

        LinearLayout num = new LinearLayout(a);
        num.setOrientation(LinearLayout.HORIZONTAL);
        num.setGravity(Gravity.BOTTOM);
        num.setPadding(0, dp(4), 0, dp(1));
        TextView b = Ui.tv(a, big, 25, Ui.TXT, true);
        b.setTypeface(Font.heavy(a));
        num.addView(b);
        if (unit != null && unit.length() > 0) {
            TextView u = Ui.tv(a, " " + unit, 11, Ui.SUB, false);
            u.setPadding(0, 0, 0, dp(4));
            num.addView(u);
        }
        c.addView(num);

        TextView s = Ui.tv(a, sub == null ? "" : sub, 10.5f, Ui.SUB, false);
        s.setSingleLine(false);
        c.addView(s);
        return c;
    }

    /** 点列的实际时间跨度，用来标标题右侧，不硬写“24 小时” */
    private String spanText(java.util.List<double[]> s) {
        if (s.size() < 2) return "今日";
        int m = (int) Math.max(1, (s.get(s.size() - 1)[0] - s.get(0)[0]) / 60);
        return m >= 60 ? ("近 " + (m / 60) + " 小时 " + (m % 60) + " 分") : ("近 " + m + " 分钟");
    }

    private View chartCard(String title, List<double[]> ser, double lo, double hi, int color, String sub) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(a, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(13), dp(11), dp(13), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(4), dp(4), dp(4), dp(4));

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.tv(a, title, 12.5f, Ui.MID, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Ui.tv(a, sub, 10.5f, Ui.SUB, false));
        c.addView(head);

        MiniChart mc = new MiniChart(a);
        mc.setColor(color);
        mc.setAxis(true);
        mc.setData(ser, lo, hi);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
        mlp.topMargin = dp(8);
        c.addView(mc, mlp);

        c.setLayoutParams(clp);
        return c;
    }

    private View sleepCard(Health h) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(a, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(13), dp(12), dp(13), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(4), dp(4), dp(4), dp(4));
        c.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.BOTTOM);

        TextView big = Ui.tv(a, sleepText(h.sleepTotal), 25, Ui.TXT, true);
        top.addView(big);
        TextView eff = Ui.tv(a, h.sleepEff > 0 ? "  效率 " + h.sleepEff + "%" : "  睡眠", 11, Ui.SUB, false);
        eff.setPadding(0, 0, 0, dp(4));
        top.addView(eff);
        c.addView(top);

        // 分期条
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackground(Ui.round(a, 0x11000000, 0, 8));
        bar.setClipToOutline(true);
        int total = 0;
        List<int[]> st = new ArrayList<int[]>();
        if (!h.stages.isEmpty()) {
            // 相邻同状态合并
            int curState = h.stages.get(0)[0], curMin = 0;
            for (int[] s : h.stages) {
                if (s[0] == curState) curMin += s[1];
                else { st.add(new int[]{curState, curMin}); curState = s[0]; curMin = s[1]; }
            }
            st.add(new int[]{curState, curMin});
        } else {
            st.add(new int[]{2, Math.max(1, h.deepMin)});
            st.add(new int[]{3, Math.max(1, h.lightMin)});
            st.add(new int[]{4, Math.max(1, h.remMin)});
            st.add(new int[]{5, Math.max(1, h.awakeMin)});
        }
        for (int[] s : st) total += s[1];

        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(15));
        blp.setMargins(0, dp(11), 0, dp(9));
        for (int[] s : st) {
            View seg = new View(a);
            seg.setBackgroundColor(stageColor(s[0]));
            bar.addView(seg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(1, s[1])));
        }
        if (total > 0) c.addView(bar, blp);

        // 明细
        LinearLayout legs = new LinearLayout(a);
        legs.setOrientation(LinearLayout.HORIZONTAL);
        legs.addView(legend("深睡", h.deepMin, 0xFF3E5C99));
        legs.addView(legend("浅睡", h.lightMin, 0xFF7FA6D9));
        legs.addView(legend("REM", h.remMin, 0xFF9B7FCB));
        legs.addView(legend("清醒", h.awakeMin, 0xFFFF7A2F));
        c.addView(legs);

        StringBuilder sb = new StringBuilder();
        if (h.sleepHr > 0) sb.append("睡眠心率 ").append(h.sleepHr).append(" bpm");
        if (h.sleepHrv > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append("HRV ").append(h.sleepHrv).append(" ms");
        if (h.sleepSpo2 > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append("血氧 ").append(h.sleepSpo2).append("%");
        if (h.sleepBreath > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append("呼吸 ").append(h.sleepBreath).append(" 次/分");
        if (h.sleepWakeCount > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append("醒来 ").append(h.sleepWakeCount).append(" 次");
        TextView foot = Ui.tv(a, sb.toString(), 11, Ui.SUB, false);
        foot.setPadding(0, dp(6), 0, 0);
        c.addView(foot);
        return c;
    }

    /** 睡眠 HRV：昨夜值 + 近若干晚趋势 + 基线 */
    private View hrvCard(Health h) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.glass(a, Ui.GLASS_HI, 16));
        c.setElevation(0);
        c.setPadding(dp(13), dp(12), dp(13), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(dp(4), dp(4), dp(4), dp(4));
        c.setLayoutParams(clp);

        List<double[]> ser = new ArrayList<double[]>(h.hrvTrend);
        java.util.Collections.sort(ser, new java.util.Comparator<double[]>() {
            public int compare(double[] x, double[] y) {
                return x[0] < y[0] ? -1 : (x[0] > y[0] ? 1 : 0);
            }
        });
        int keep = 14;
        if (ser.size() > keep) ser = new ArrayList<double[]>(ser.subList(ser.size() - keep, ser.size()));

        double sum = 0, mn = 1e9, mx = -1e9;
        for (double[] p : ser) { sum += p[1]; mn = Math.min(mn, p[1]); mx = Math.max(mx, p[1]); }
        int base = (int) Math.round(sum / ser.size());
        int last = (int) Math.round(ser.get(ser.size() - 1)[1]);
        int diff = last - base;

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(a);
        dot.setBackground(Ui.round(a, C_SLEEP, 0, 999));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dlp.rightMargin = dp(6);
        head.addView(dot, dlp);
        head.addView(Ui.tv(a, "睡眠 HRV", 12.5f, Ui.MID, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(Ui.tv(a, "基线 " + base + " ms", 10.5f, Ui.SUB, false));
        c.addView(head);

        LinearLayout num = new LinearLayout(a);
        num.setOrientation(LinearLayout.HORIZONTAL);
        num.setGravity(Gravity.BOTTOM);
        num.setPadding(0, dp(4), 0, 0);
        num.addView(Ui.tv(a, String.valueOf(last), 25, Ui.TXT, true));
        TextView unit = Ui.tv(a, " ms", 12, Ui.SUB, false);
        unit.setPadding(0, 0, 0, dp(4));
        num.addView(unit);
        String tail = Math.abs(diff) < 3 ? "与基线持平"
                : (diff > 0 ? "比基线高 " + diff : "比基线低 " + (-diff)) + " ms";
        TextView t2 = Ui.tv(a, "  昨夜 · " + tail, 11, Ui.SUB, false);
        t2.setPadding(0, 0, 0, dp(5));
        num.addView(t2);
        c.addView(num);

        MiniChart mc = new MiniChart(a);
        mc.setColor(C_SLEEP);
        mc.setAxis(true);
        mc.setData(ser, mn - 3, mx + 3);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(88));
        mlp.topMargin = dp(6);
        c.addView(mc, mlp);

        TextView foot = Ui.tv(a, "近 " + ser.size() + " 晚 · 最低 " + (int) mn
                + " · 最高 " + (int) mx + " ms", 10.5f, Ui.SUB, false);
        foot.setPadding(0, dp(7), 0, 0);
        c.addView(foot);
        return c;
    }

    private View legend(String name, int min, int color) {        LinearLayout l = new LinearLayout(a);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, 0, dp(10), 0);
        View d = new View(a);
        d.setBackground(Ui.round(a, color, 0, 999));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(6), dp(6));
        dlp.rightMargin = dp(4);
        l.addView(d, dlp);
        l.addView(Ui.tv(a, name + " " + minutes(min), 10.5f, Ui.MID, false));
        return l;
    }

    private View plain(String s) {
        TextView t = Ui.tv(a, s, 12.5f, Ui.SUB, false);
        t.setBackground(Ui.glass(a, Ui.GLASS_HI, 16));
        t.setPadding(dp(14), dp(16), dp(14), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------- 小工具 ----------------
    private static int stageColor(int state) {
        if (state == 2) return 0xFF3E5C99;
        if (state == 3) return 0xFF7FA6D9;
        if (state == 4) return 0xFF9B7FCB;
        if (state == 5) return 0xFFFF7A2F;
        return 0xFFC8CFDA;
    }

    static String sleepText(int min) {
        if (min <= 0) return "—";
        return (min / 60) + " 小时 " + (min % 60) + " 分";
    }

    static String minutes(int min) {
        if (min <= 0) return "—";
        return (min / 60) + "h" + (min % 60) + "m";
    }

    static String fmt(int v) {
        if (v >= 10000) return String.format("%.1f万", v / 10000.0);
        return String.valueOf(v);
    }

    static String endTimeText(long ts) {
        if (ts <= 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts * 1000);
        return String.format("%02d-%02d %02d:%02d 结束",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    /** 抽稀到 n 个点 */
    static List<double[]> down(List<double[]> in, int n) {
        List<double[]> out = new ArrayList<double[]>();
        if (in.size() <= n) { out.addAll(in); return out; }
        double step = (double) in.size() / n;
        for (int i = 0; i < n; i++) {
            int idx = (int) Math.floor(i * step);
            if (idx >= 0 && idx < in.size()) out.add(in.get(idx));
        }
        return out;
    }
}
