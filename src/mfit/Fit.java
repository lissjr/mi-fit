package com.hanxing.mifit;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** FIT 编码，口径与网页版 fitcore.js 完全一致（含 DeviceInfo，避免佳明做高程校正） */
public class Fit {
    private static final int ENUM = 0x00, U8 = 0x02, S16 = 0x83, U16 = 0x84, S32 = 0x85, U32 = 0x86,
            U32Z = 0x8C, F32 = 0x88;
    private static final long FIT_EPOCH = 631065600L;
    private static final double SCALE = 11930464.7111;

    private static long fitSec(long unixSec) { return unixSec - FIT_EPOCH; }

    private static int num(double v, boolean valid, int type) {
        if (!valid) {
            if (type == U8 || type == ENUM) return 0xFF;
            if (type == S32) return 0x7FFFFFFF;
            if (type == U32 || type == U32Z) return 0xFFFFFFFF;
            return 0xFFFF;
        }
        if (type == U8 || type == ENUM) return (int) Math.max(0, Math.min(254, Math.round(v)));
        if (type == S32 || type == S16) return (int) Math.round(v);
        return (int) Math.max(0, Math.round(v));
    }

    private static class W {
        List<byte[]> chunks = new ArrayList<byte[]>();
        Map<Integer, int[][]> defs = new HashMap<Integer, int[][]>();

        void def(int lt, int global, int[][] fields) {
            byte[] b = new byte[fields.length * 3 + 6];
            b[0] = (byte) (0x40 | lt);
            b[1] = 0; b[2] = 0;
            b[3] = (byte) (global & 0xFF);
            b[4] = (byte) ((global >> 8) & 0xFF);
            b[5] = (byte) fields.length;
            for (int i = 0; i < fields.length; i++) {
                b[6 + i * 3] = (byte) fields[i][0];
                b[7 + i * 3] = (byte) fields[i][1];
                b[8 + i * 3] = (byte) fields[i][2];
            }
            chunks.add(b);
            defs.put(lt, fields);
        }

        void data(int lt, Map<Integer, Double> vals) {
            int[][] fields = defs.get(lt);
            int size = 1;
            for (int[] f : fields) size += f[1];
            byte[] b = new byte[size];
            b[0] = (byte) (lt & 0x0F);
            int o = 1;
            for (int[] f : fields) {
                int fid = f[0], fsz = f[1], ftype = f[2];
                Double dv = vals.get(fid);
                boolean valid = dv != null;
                double v = valid ? dv : 0;
                int iv = num(v, valid, ftype);
                if (fsz == 1) b[o] = (byte) iv;
                else if (fsz == 2) { b[o] = (byte) (iv & 0xFF); b[o + 1] = (byte) ((iv >> 8) & 0xFF); }
                else {
                    b[o] = (byte) (iv & 0xFF);
                    b[o + 1] = (byte) ((iv >> 8) & 0xFF);
                    b[o + 2] = (byte) ((iv >> 16) & 0xFF);
                    b[o + 3] = (byte) ((iv >> 24) & 0xFF);
                }
                o += fsz;
            }
            chunks.add(b);
        }

        byte[] build() {
            int total = 0;
            for (byte[] c : chunks) total += c.length;
            byte[] out = new byte[12 + total + 2];
            out[0] = 12; out[1] = 0x20;
            int prof = 21218;
            out[2] = (byte) (prof & 0xFF);
            out[3] = (byte) ((prof >> 8) & 0xFF);
            out[4] = (byte) (total & 0xFF);
            out[5] = (byte) ((total >> 8) & 0xFF);
            out[6] = (byte) ((total >> 16) & 0xFF);
            out[7] = (byte) ((total >> 24) & 0xFF);
            out[8] = 0x2E; out[9] = 0x46; out[10] = 0x49; out[11] = 0x54;
            int p = 12;
            for (byte[] c : chunks) { System.arraycopy(c, 0, out, p, c.length); p += c.length; }
            int crc = crc16(out, 12 + total);
            out[12 + total] = (byte) (crc & 0xFF);
            out[13 + total] = (byte) ((crc >> 8) & 0xFF);
            return out;
        }
    }

    private static int crc16(byte[] b, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (b[i] & 0xFF);
            for (int j = 0; j < 8; j++)
                crc = ((crc & 1) != 0) ? ((crc >> 1) ^ 0xA001) : (crc >> 1);
        }
        return crc & 0xFFFF;
    }

    private static void deviceInfo(W w, int lt, long startSec) {
        w.def(lt, 23, new int[][]{
                {253, 4, U32}, {0, 1, U8}, {1, 1, U8}, {2, 2, U16},
                {3, 4, U32Z}, {4, 2, U16}, {5, 2, U16}});
        Map<Integer, Double> v = new HashMap<Integer, Double>();
        v.put(253, (double) fitSec(startSec));
        v.put(0, 0.0); v.put(1, 0.0); v.put(2, 1.0);
        v.put(3, 3702826104.0); v.put(4, 2700.0); v.put(5, 700.0);
        w.data(lt, v);
    }

    /** 小米 category/key → FIT sport */
    public static int sportOf(String cat, String key) {
        String[][] m = {
                {"running", "1"}, {"outdoor_running", "1"}, {"indoor_running", "1"},
                {"playground_running", "1"}, {"trail_running", "1"},
                {"outdoor_riding", "2"}, {"indoor_riding", "2"},
                {"walking", "11"}, {"outdoor_walking", "11"}, {"indoor_walking", "11"},
                {"strength_training", "10"}, {"physical_training", "10"}, {"free_training", "10"},
                {"high_interval_training", "10"}, {"indoor_fitness", "10"}, {"taichi", "10"},
                {"rope_skipping", "10"}, {"rowing_machine", "15"}, {"climbing", "16"},
                {"basketball", "6"}, {"parallel_skiing", "13"}, {"pingpong", "4"},
                {"e_sports", "0"}, {"esports", "0"}, {"motion_sensing_game", "0"}
        };
        for (String[] p : m) if (key != null && p[0].equals(key)) return Integer.parseInt(p[1]);
        for (String[] p : m) if (cat != null && p[0].equals(cat)) return Integer.parseInt(p[1]);
        return 0;
    }

    /**
     * 体重 + 体脂率 + 肌肉量 + 基础代谢 + BMI，写成 weight_scale 消息。
     * 佳明的体重曲线只认这条通道，心率/睡眠/血氧没有写入入口。
     */
    public static byte[] buildBody(long ts, double kg, double fat, double muscle, int bmr, double bmi) {
        W w = new W();
        w.def(0, 0, new int[][]{{0, 1, ENUM}, {1, 2, U16}, {2, 2, U16}, {3, 4, U32Z}, {4, 4, U32}});
        Map<Integer, Double> fid = new HashMap<Integer, Double>();
        fid.put(0, 9.0); fid.put(1, 1.0); fid.put(2, 2700.0);
        fid.put(3, 3702826104.0); fid.put(4, (double) fitSec(ts));
        w.data(0, fid);

        w.def(1, 49, new int[][]{{0, 2, U16}, {1, 1, U8}});
        Map<Integer, Double> fc = new HashMap<Integer, Double>();
        fc.put(0, 700.0); fc.put(1, 0.0);
        w.data(1, fc);

        deviceInfo(w, 2, ts);

        w.def(3, 30, new int[][]{
                {253, 4, U32}, {0, 2, U16}, {1, 2, U16}, {5, 2, U16}, {7, 2, U16}, {13, 2, U16}});
        Map<Integer, Double> ws = new HashMap<Integer, Double>();
        ws.put(253, (double) fitSec(ts));
        ws.put(0, kg * 100.0);
        if (fat > 0) ws.put(1, fat * 100.0);
        if (muscle > 0) ws.put(5, muscle * 100.0);
        if (bmr > 0) ws.put(7, bmr * 4.0);
        if (bmi > 0) ws.put(13, bmi * 10.0);
        w.data(3, ws);

        return w.build();
    }

    /**
     * 由官方汇总（Run）+ 可选逐点数据（Track）生成 FIT。
     * tr 为 null 时只写汇总，Session 里的平均速度等仍取官方字段。
     * kg > 0 时给跑步/步行写入估算功率（record power + session 平均/最大）。
     */
    public static byte[] build(Run r, Track tr) {
        return build(r, tr, 0);
    }

    public static byte[] build(Run r, Track tr, double kg) {
        long start = r.start;
        int dur = r.timer;
        long end = r.end > 0 ? r.end : start + dur;
        int elapsed = end > start ? (int) (end - start) : dur;

        double dist = r.dist;
        int cal = r.cal, steps = r.steps;
        double avgSpeed = r.rep.optDouble("avg_speed", 0) > 0
                ? r.rep.optDouble("avg_speed", 0) / 3.6
                : (dur > 0 ? dist / dur : 0);
        double maxSpeed = r.rep.optDouble("max_speed", 0) > 0
                ? r.rep.optDouble("max_speed", 0) / 3.6 : avgSpeed;
        int avgHr = (int) Math.round(r.avgHr), maxHr = (int) Math.round(r.maxHr);
        int sport = sportOf(r.category, r.key);
        boolean half = (sport == 1);
        int avgCad = r.avgCad > 0 ? Math.max(1, (int) Math.round(r.avgCad / (half ? 2 : 1))) : 0;
        int maxCad = r.maxCad > 0 ? Math.max(1, (int) Math.round(r.maxCad / (half ? 2 : 1))) : 0;
        int ascent = (int) Math.round(r.ascent), descent = (int) Math.round(r.descent);
        double vamp = r.vamp, ct = r.ct, vsr = r.vsr, stride = r.stride;

        // 功率只对跑步和步行做（骑行没有可靠的功率来源，宁可不写）
        boolean wantPower = kg > 0 && (sport == 1 || sport == 11);
        boolean walk = (sport == 11);
        double pAvg = 0, pMax = 0;

        W w = new W();
        w.def(0, 0, new int[][]{{0, 1, ENUM}, {1, 2, U16}, {2, 2, U16}, {3, 4, U32Z}, {4, 4, U32}});
        Map<Integer, Double> fileId = new HashMap<Integer, Double>();
        fileId.put(0, 4.0); fileId.put(1, 1.0); fileId.put(2, 2700.0);
        fileId.put(3, 3702826104.0); fileId.put(4, (double) fitSec(start));
        w.data(0, fileId);

        deviceInfo(w, 5, start);

        w.def(1, 21, new int[][]{{253, 4, U32}, {0, 1, ENUM}, {1, 1, ENUM}});
        Map<Integer, Double> ev = new HashMap<Integer, Double>();
        ev.put(253, (double) fitSec(start)); ev.put(0, 0.0); ev.put(1, 0.0);
        w.data(1, ev);

        if (tr != null && !tr.harness.isEmpty()) {
            List<Track.Frame> hs = tr.harness;
            boolean hasGps = false, hasHr = false, hasCad = false, hasStep = false,
                    hasStance = false, hasVamp = false, hasVsr = false, hasAlt = false;
            for (int i = 0; i < hs.size(); i++) {
                Track.Frame h = hs.get(i);
                if (!Double.isNaN(h.lat)) hasGps = true;
                if (h.hr > 0 && h.hr < 260) hasHr = true;
                if (h.cad > 0 && h.cad < 260) hasCad = true;
                if (h.stride > 30 && h.stride < 300) hasStep = true;
                if (h.ct > 50 && h.ct < 1000) hasStance = true;
                if (h.vamp > 0 && h.vamp < 500) hasVamp = true;
                if (h.vsr > 0 && h.vsr < 500) hasVsr = true;
                double a = tr.smAlt[i];
                if (a > 0 && a < 9000) hasAlt = true;
            }
            List<int[]> rf = new ArrayList<int[]>();
            rf.add(new int[]{253, 4, U32});
            if (hasGps) { rf.add(new int[]{0, 4, S32}); rf.add(new int[]{1, 4, S32}); }
            if (hasAlt) rf.add(new int[]{2, 2, U16});
            if (hasHr) rf.add(new int[]{3, 1, U8});
            if (hasCad) rf.add(new int[]{4, 1, U8});
            rf.add(new int[]{5, 4, U32});
            rf.add(new int[]{6, 2, U16});
            if (wantPower) rf.add(new int[]{7, 2, U16});
            if (hasVamp) rf.add(new int[]{39, 2, U16});
            if (hasStance) rf.add(new int[]{41, 2, U16});
            if (hasVsr) rf.add(new int[]{83, 2, U16});
            if (hasStep) rf.add(new int[]{85, 2, U16});
            w.def(2, 20, rf.toArray(new int[0][]));

            double ps = 0;
            int pc = 0;
            for (int i = 0; i < hs.size(); i++) {
                Track.Frame h = hs.get(i);
                Map<Integer, Double> v = new HashMap<Integer, Double>();
                v.put(253, (double) fitSec(h.ts));
                v.put(5, Math.round(h.dist * 100) * 1.0);
                if (h.speed > 0) v.put(6, Math.round(h.speed * 1000) * 1.0);
                if (wantPower) {
                    double p = framePower(kg, h.speed, i, hs, tr, walk);
                    if (p > 0) {
                        v.put(7, Math.round(p) * 1.0);
                        ps += p;
                        pc++;
                        if (p > pMax) pMax = p;
                    }
                }
                if (hasGps) {
                    if (!Double.isNaN(h.lat)) v.put(0, Math.round(h.lat * SCALE) * 1.0);
                    if (!Double.isNaN(h.lng)) v.put(1, Math.round(h.lng * SCALE) * 1.0);
                }
                double a = tr.smAlt[i];
                if (hasAlt && a > 0 && a < 9000) v.put(2, Math.round((a + 500) * 5) * 1.0);
                if (hasHr && h.hr > 0 && h.hr < 260) v.put(3, Math.round(h.hr) * 1.0);
                if (hasCad && h.cad > 0 && h.cad < 260) v.put(4, Math.max(1, Math.round(h.cad / 2)) * 1.0);
                if (hasVamp && h.vamp > 0 && h.vamp < 500) v.put(39, Math.round(h.vamp * 10) * 1.0);
                if (hasStance && h.ct > 50 && h.ct < 1000) v.put(41, Math.round(h.ct * 10) * 1.0);
                if (hasVsr && h.vsr > 0 && h.vsr < 500) v.put(83, Math.round(h.vsr / 10 * 100) * 1.0);
                if (hasStep && h.stride > 30 && h.stride < 300) v.put(85, Math.round(h.stride * 100) * 1.0);
                w.data(2, v);
            }
            if (pc > 0) pAvg = ps / pc;
            ev = new HashMap<Integer, Double>();
            ev.put(253, (double) fitSec(end)); ev.put(0, 0.0); ev.put(1, 1.0);
            w.data(1, ev);
        } else {
            ev = new HashMap<Integer, Double>();
            ev.put(253, (double) fitSec(end)); ev.put(0, 0.0); ev.put(1, 1.0);
            w.data(1, ev);
        }

        // 计圈：每 1km 自动一圈，最后一圈为余数（佳明显示"自动计圈"）
        if (tr != null && !tr.harness.isEmpty() && r.dist > 100) {
            List<Track.Frame> lh = tr.harness;
            List<int[]> lf = new ArrayList<int[]>();
            lf.add(new int[]{253, 4, U32});
            lf.add(new int[]{254, 2, U16});
            lf.add(new int[]{2, 4, U32});
            lf.add(new int[]{7, 4, U32}); lf.add(new int[]{8, 4, U32}); lf.add(new int[]{9, 4, U32});
            lf.add(new int[]{13, 2, U16}); lf.add(new int[]{14, 2, U16});
            if (avgHr > 0) { lf.add(new int[]{15, 1, U8}); lf.add(new int[]{16, 1, U8}); }
            if (avgCad > 0) { lf.add(new int[]{17, 1, U8}); lf.add(new int[]{18, 1, U8}); }
            if (pAvg > 0) { lf.add(new int[]{19, 2, U16}); lf.add(new int[]{20, 2, U16}); }
            lf.add(new int[]{21, 2, U16}); lf.add(new int[]{22, 2, U16});
            lf.add(new int[]{24, 1, ENUM}); lf.add(new int[]{25, 1, ENUM}); lf.add(new int[]{39, 1, ENUM});
            if (vamp > 0) lf.add(new int[]{77, 2, U16});
            if (ct > 0) lf.add(new int[]{79, 2, U16});
            if (vsr > 0) lf.add(new int[]{118, 2, U16});
            if (stride > 0) lf.add(new int[]{120, 2, U16});
            w.def(6, 19, lf.toArray(new int[0][]));

            int n = lh.size(), si = 0, li = 0;
            double cut = 1000.0;
            for (int i = 0; i < n; i++) {
                double d = lh.get(i).dist;
                if (i != n - 1 && d < cut) continue;
                long t0 = lh.get(si).ts, t1 = lh.get(i).ts;
                if (t1 <= t0) t1 = t0 + 1;
                double d0 = (si == 0) ? 0 : lh.get(si - 1).dist;
                double dL = lh.get(i).dist - d0;
                if (dL < 0) dL = 0;
                double vMaxL = 0;
                int hrSum = 0, hrN = 0, hrMaxL = 0, cadSum = 0, cadN = 0, cadMaxL = 0;
                double pSum = 0, pMaxL = 0; int pN = 0;
                double ascL = 0, descL = 0;
                double vpSum = 0, ctSum = 0, vsSum = 0, stSum = 0;
                int vpN = 0, ctN = 0, vsN = 0, stN = 0;
                for (int k = si; k <= i; k++) {
                    Track.Frame h = lh.get(k);
                    if (h.speed > 0) { if (h.speed > vMaxL) vMaxL = h.speed; }
                    int hrV = (int) Math.round(h.hr);
                    if (hrV > 0 && hrV < 260) { hrSum += hrV; hrN++; if (hrV > hrMaxL) hrMaxL = hrV; }
                    if (h.cad > 0 && h.cad < 260) {
                        int c = Math.max(1, (int) Math.round(h.cad / 2));
                        cadSum += c; cadN++; if (c > cadMaxL) cadMaxL = c;
                    }
                    if (wantPower) {
                        double p = framePower(kg, h.speed, k, lh, tr, walk);
                        if (p > 0) { pSum += p; pN++; if (p > pMaxL) pMaxL = p; }
                    }
                    if (k > si && tr.smAlt != null && tr.smAlt.length > k) {
                        double a0 = tr.smAlt[k - 1], a1 = tr.smAlt[k];
                        if (a0 > 0 && a1 > 0) { double dd = a1 - a0; if (dd > 0) ascL += dd; else descL -= dd; }
                    }
                    if (h.vamp > 0 && h.vamp < 500) { vpSum += h.vamp; vpN++; }
                    if (h.ct > 50 && h.ct < 1000) { ctSum += h.ct; ctN++; }
                    if (h.vsr > 0 && h.vsr < 500) { vsSum += h.vsr; vsN++; }
                    if (h.stride > 30 && h.stride < 300) { stSum += h.stride; stN++; }
                }
                double sec = t1 - t0;
                Map<Integer, Double> lv = new HashMap<Integer, Double>();
                lv.put(253, (double) fitSec(t1));
                lv.put(254, (double) li);
                lv.put(2, (double) fitSec(t0));
                lv.put(7, Math.round(sec * 1000) * 1.0);
                lv.put(8, Math.round(sec * 1000) * 1.0);
                lv.put(9, Math.round(dL * 100) * 1.0);
                lv.put(13, Math.round((sec > 0 ? dL / sec : 0) * 1000) * 1.0);
                lv.put(14, Math.round(vMaxL * 1000) * 1.0);
                if (avgHr > 0) {
                    lv.put(15, hrN > 0 ? (double) (hrSum / hrN) : 255.0);
                    lv.put(16, hrN > 0 ? (double) hrMaxL : 255.0);
                }
                if (avgCad > 0) {
                    lv.put(17, cadN > 0 ? (double) (cadSum / cadN) : 255.0);
                    lv.put(18, cadN > 0 ? (double) cadMaxL : 255.0);
                }
                if (pAvg > 0) {
                    lv.put(19, pN > 0 ? Math.round(pSum / pN) * 1.0 : 0.0);
                    lv.put(20, Math.round(pMaxL) * 1.0);
                }
                lv.put(21, Math.round(ascL) * 1.0);
                lv.put(22, Math.round(descL) * 1.0);
                lv.put(24, 2.0);
                lv.put(25, (double) sport);
                lv.put(39, 0.0);
                if (vamp > 0) lv.put(77, vpN > 0 ? Math.round(vpSum / vpN * 10) * 1.0 : 0.0);
                if (ct > 0) lv.put(79, ctN > 0 ? Math.round(ctSum / ctN * 10) * 1.0 : 0.0);
                if (vsr > 0) lv.put(118, vsN > 0 ? Math.round(vsSum / vsN / 10 * 100) * 1.0 : 0.0);
                if (stride > 0) lv.put(120, stN > 0 ? Math.round(stSum / stN * 100) * 1.0 : 0.0);
                w.data(6, lv);
                li++;
                si = i + 1;
                while (cut <= lh.get(i).dist) cut += 1000.0;
            }
        }

        List<int[]> sf = new ArrayList<int[]>();
        sf.add(new int[]{2, 4, U32}); sf.add(new int[]{5, 1, ENUM}); sf.add(new int[]{6, 1, ENUM});
        sf.add(new int[]{7, 4, U32}); sf.add(new int[]{8, 4, U32}); sf.add(new int[]{9, 4, U32});
        sf.add(new int[]{10, 4, U32});
        sf.add(new int[]{11, 2, U16}); sf.add(new int[]{14, 2, U16}); sf.add(new int[]{15, 2, U16});
        sf.add(new int[]{16, 1, U8}); sf.add(new int[]{17, 1, U8}); sf.add(new int[]{18, 1, U8});
        sf.add(new int[]{19, 1, U8});
        if (ascent > 0) sf.add(new int[]{22, 2, U16});
        if (descent > 0) sf.add(new int[]{23, 2, U16});
        if (vamp > 0) sf.add(new int[]{89, 2, U16});
        if (ct > 0) sf.add(new int[]{91, 2, U16});
        if (vsr > 0) sf.add(new int[]{132, 2, U16});
        if (stride > 0) sf.add(new int[]{134, 2, U16});
        if (pAvg > 0) { sf.add(new int[]{20, 2, U16}); sf.add(new int[]{21, 2, U16}); }
        w.def(3, 18, sf.toArray(new int[0][]));

        Map<Integer, Double> sv = new HashMap<Integer, Double>();
        sv.put(2, (double) fitSec(start));
        sv.put(5, (double) sport);
        sv.put(6, 0.0);
        sv.put(7, Math.round(elapsed * 1000) * 1.0);
        sv.put(8, Math.round(dur * 1000) * 1.0);
        sv.put(9, dist * 100);
        if (steps > 0) sv.put(10, (double) steps);
        if (cal > 0) sv.put(11, (double) cal);
        sv.put(14, Math.round(avgSpeed * 1000) * 1.0);
        sv.put(15, Math.round(maxSpeed * 1000) * 1.0);
        if (avgHr > 0) sv.put(16, (double) avgHr);
        if (maxHr > 0) sv.put(17, (double) maxHr);
        if (avgCad > 0) sv.put(18, (double) avgCad);
        if (maxCad > 0) sv.put(19, (double) maxCad);
        if (ascent > 0) sv.put(22, (double) ascent);
        if (descent > 0) sv.put(23, (double) descent);
        if (vamp > 0) sv.put(89, Math.round(vamp * 100) * 1.0);
        if (ct > 0) sv.put(91, Math.round(ct * 10) * 1.0);
        if (vsr > 0) sv.put(132, Math.round(vsr * 100) * 1.0);
        if (stride > 0) sv.put(134, Math.round(stride * 100) * 1.0);
        if (pAvg > 0) { sv.put(20, Math.round(pAvg) * 1.0); sv.put(21, Math.round(pMax) * 1.0); }
        w.data(3, sv);

        w.def(4, 34, new int[][]{{253, 4, U32}, {0, 4, U32}, {1, 2, U16},
                {2, 1, ENUM}, {3, 1, ENUM}, {4, 1, ENUM}});
        Map<Integer, Double> av = new HashMap<Integer, Double>();
        av.put(253, (double) fitSec(end));
        av.put(0, Math.round(dur * 1000) * 1.0);
        av.put(1, 1.0); av.put(2, 0.0); av.put(3, 26.0); av.put(4, 1.0);
        w.data(4, av);

        return w.build();
    }

    /** 单帧功率：体重 + 速度 + 坡度（用平滑后的海拔差分） */
    private static double framePower(double kg, double v, int i, List<Track.Frame> hs, Track tr,
                                     boolean walk) {
        if (kg <= 0 || v <= 0.2) return 0;
        double grade = 0;
        if (i > 0 && tr.smAlt != null && tr.smAlt.length > i) {
            double a0 = tr.smAlt[i - 1], a1 = tr.smAlt[i];
            double d0 = hs.get(i - 1).dist, d1 = hs.get(i).dist;
            if (a1 > 0 && a0 > 0 && d1 > d0) {
                grade = (a1 - a0) / (d1 - d0);
                if (grade > 0.25) grade = 0.25;
                if (grade < -0.25) grade = -0.25;
            }
        }
        double cr = walk ? Power.WALK_CR : Power.RUN_CR;
        double p = kg * v * 9.81 * (cr + grade);
        return p > 0 ? p : 0;
    }

    /** 文件名：2026-11-11_2004_户外跑步_12.5km.fit */
    public static String fileName(Run r) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(r.start * 1000);
        String label = r.label().replace("/", "");
        return String.format("%04d-%02d-%02d_%02d%02d_%s_%.1fkm.fit",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE), label, r.dist / 1000.0);
    }
}
