package com.hanxing.mifit;

/** 用体重 + 速度 + 坡度估算功率（跑步/步行）；口径和佳明那套能耗近似一致，不含风阻 */
public class Power {

    public static final double RUN_CR = 0.11;    // 跑步每公斤每米的机械代价系数
    public static final double WALK_CR = 0.16;   // 步行更费劲一点

    /** spd 单位 km/h，alt 单位米（0/NaN 视为无海拔），t 相对秒；返回逐点功率 W */
    public static double[] series(double[] spd, double[] alt, double[] t, double kg, boolean walk) {
        int n = spd == null ? 0 : spd.length;
        if (n < 2 || kg <= 0) return new double[0];
        double cr = walk ? WALK_CR : RUN_CR;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = spd[i] > 0 ? spd[i] / 3.6 : 0;
        double[] vs = smooth(v, 2);
        boolean hasAlt = false;
        if (alt != null && alt.length >= n) {
            for (int i = 0; i < n; i++) if (alt[i] > 0) { hasAlt = true; break; }
        }
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            double vv = vs[i];
            if (vv <= 0.2) { raw[i] = 0; continue; }
            double grade = 0;
            if (hasAlt && i > 0 && alt[i] > 0 && alt[i - 1] > 0) {
                double dt = (t != null && t.length > i && t[i] > t[i - 1]) ? (t[i] - t[i - 1]) : 5.0;
                double ds = Math.max(1.0, vv * (dt > 0 ? dt : 5.0));
                grade = (alt[i] - alt[i - 1]) / ds;
                if (grade > 0.25) grade = 0.25;
                if (grade < -0.25) grade = -0.25;
            }
            double p = kg * vv * 9.81 * (cr + grade);
            raw[i] = p > 0 ? p : 0;
        }
        return smooth(raw, 2);
    }

    /** 滑动平均，半窗 w */
    private static double[] smooth(double[] a, int w) {
        int n = a.length;
        double[] o = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            int c = 0;
            for (int j = i - w; j <= i + w; j++) {
                if (j < 0 || j >= n) continue;
                s += a[j];
                c++;
            }
            o[i] = c > 0 ? s / c : 0;
        }
        return o;
    }

    public static double avg(double[] a) {
        if (a == null || a.length == 0) return 0;
        double s = 0;
        int c = 0;
        for (double v : a) if (v > 0) { s += v; c++; }
        return c > 0 ? s / c : 0;
    }
}
