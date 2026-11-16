package com.hanxing.mifit;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 体重：按运动时间取库里该时间之前最近的一次称重；一条都没有时用手动值 */
public class Weight {

    public final List<double[]> list = new ArrayList<double[]>();   // {ts, kg} 升序
    public double manual;
    public boolean loaded;

    public static Weight load(Context c, RootShell sh) {
        Weight w = new Weight();
        boolean ok = readInto(w, sh);
        if (!ok) {
            // 同一个 shell 有时 dd 读不出来（先 cat 过轨迹文件之后），换一个新 shell 再试一次
            RootShell s2 = new RootShell();
            try {
                if (s2.start(c)) ok = readInto(w, s2);
            } catch (Exception ignored) { }
            android.util.Log.i("mfit", "weight retry ok=" + ok);
        }
        if (c != null) {
            w.manual = c.getSharedPreferences("mfit", Context.MODE_PRIVATE)
                    .getFloat("manualKg", 0f);
        }
        return w;
    }

    private static boolean readInto(Weight w, RootShell sh) {
        if (sh == null) return false;
        try {
            Db db = new Db(sh, Server.dbPrivate(sh));
            List<Map<String, Object>> rows = db.readTableObjects("weight_item_record");
            w.list.clear();
            for (Map<String, Object> row : rows) {
                Object o = row.get("weight");
                if (!(o instanceof Double) && !(o instanceof Long)) continue;
                double kgv = ((Number) o).doubleValue();
                Object t = row.get("time");
                long ts = t instanceof Long ? ((Long) t).longValue() : 0L;
                if (kgv <= 0 || ts <= 0) continue;
                w.list.add(new double[]{ts, kgv});
            }
            Collections.sort(w.list, new Comparator<double[]>() {
                public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
            });
            w.loaded = true;
            android.util.Log.i("mfit", "weight rows=" + w.list.size()
                    + (w.list.isEmpty() ? "" : (" last=" + w.list.get(w.list.size() - 1)[1]
                    + "@" + (long) w.list.get(w.list.size() - 1)[0])));
            return true;
        } catch (Exception e) {
            android.util.Log.w("mfit", "weight load fail: " + e);
            return false;
        }
    }

    /** 返回 {kg, 称重秒}；用手动值时秒为 0，取不到返回 null */
    public double[] at(long runStart) {
        double[] best = null;
        for (double[] p : list) {
            if (p[0] <= runStart) best = p;
            else break;
        }
        if (best != null) return new double[]{best[1], best[0]};
        if (!list.isEmpty()) return new double[]{list.get(0)[1], list.get(0)[0]};
        if (manual > 0) return new double[]{manual, 0.0};
        return null;
    }

    private static Weight cached;

    /** 全 App 复用一份：优先用 health 缓存里的全量称重（不用再读一次库），没有才回退读库 */
    public static synchronized Weight cached(Context c, RootShell sh) {
        if (cached == null || !cached.loaded) {
            Weight w = new Weight();
            Health h = c != null ? Cache.readHealth(c) : null;
            if (h != null && !h.weightAll.isEmpty()) {
                w.list.addAll(h.weightAll);
                Collections.sort(w.list, new Comparator<double[]>() {
                    public int compare(double[] a, double[] b) { return Double.compare(a[0], b[0]); }
                });
                w.loaded = true;
                android.util.Log.i("mfit", "weight from cache n=" + w.list.size());
            } else {
                w = load(c, sh);
            }
            cached = w;
        }
        if (c != null) {
            cached.manual = c.getSharedPreferences("mfit", Context.MODE_PRIVATE)
                    .getFloat("manualKg", 0f);
        }
        return cached;
    }

    public static synchronized void reset() { cached = null; }

    /** 只给体重数字：「58.3kg」，手动输入时带前缀 */
    public static String kg(double[] info) {
        if (info == null) return "";
        String kg = String.format(java.util.Locale.US, "%.1f", info[0]);
        return info[1] <= 0 ? "手动 " + kg + "kg" : kg + "kg";
    }

    /** 「58.3kg 10-30」这样的标注 */
    public static String label(double[] info) {
        if (info == null) return "";
        String kg = String.format(java.util.Locale.US, "%.1f", info[0]);
        if (info[1] <= 0) return "手动 " + kg + "kg";
        java.util.Calendar cl = java.util.Calendar.getInstance();
        cl.setTimeInMillis((long) info[1] * 1000);
        return kg + "kg " + String.format(java.util.Locale.US, "%d-%02d-%02d",
                cl.get(java.util.Calendar.YEAR), cl.get(java.util.Calendar.MONTH) + 1,
                cl.get(java.util.Calendar.DAY_OF_MONTH));
    }
}
