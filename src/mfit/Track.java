package com.hanxing.mifit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 逐点轨迹解码 + 分析（心率/速度/配速/海拔曲线、累计距离、地图轨迹）。
 * 与网页版 fitcore.js 的 analyze() 口径完全一致。
 */
public class Track {

    public static class Frame {
        long ts;
        int idx;
        double hr, cad, pace, alt, cal, steps, stride, ct, vamp, vsr, speed;
        double lat = Double.NaN, lng = Double.NaN;
        double dist;                      // 累计距离（米）
        Seg seg;
    }

    public static class Seg {
        long resume;
        double dist, dur, alt0;
        double count;
        boolean gpsOnly;
        List<double[]> pts = new ArrayList<double[]>(); // hr,cad,alt,spd
    }

    public static class Gpt {
        long ts;
        double lat, lng, spd, alt;
    }

    public int recProto, recVer, gpsVer;
    public long startTs;
    public List<Frame> harness = new ArrayList<Frame>();
    public double[] smAlt = new double[0];
    public double totalDist;
    public int timer;
    public int elapsed;

    // 曲线（抽样后）
    public double[] t, mtArr, hr, pace, alt, cad, spd, stride, ct, vamp, vsr;
    // 地图轨迹：[lat, lng, speed(m/s), dist(米)]
    public List<double[]> track = new ArrayList<double[]>();

    // 汇总（逐点算出来的，用于和官方汇总对照）
    public double avgHr, maxHr, avgCad, maxCad, ascent, descent, maxSpeed, avgSpeed;

    public boolean selfTest = false;   // 有曲线数据

    private static double u8(byte[] b, int o) { return b[o] & 0xFF; }
    private static long u32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }
    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8); }
    private static float f32(byte[] b, int o) {
        return Float.intBitsToFloat((int) u32(b, o));
    }

    // ---------- record ----------
    private static final Object[][] V9 = {
            {new String[]{"newCalories", "newSteps"}, 1, new int[][]{{4, 4}, {0, 4}}},
            {new String[]{"heartRate"}, 1, null},
            {new String[]{"altitude"}, 4, null},
            {new String[]{"newDistance"}, 2, null},
            {new String[]{"stride"}, 1, null},
            {new String[]{"impactForce", "contactTime", "flightTime"}, 4, new int[][]{{26, 6}, {13, 13}, {0, 13}}},
            {new String[]{"contactRatio"}, 1, null},
            {new String[]{"cadence"}, 1, null},
            {new String[]{"pace"}, 2, null},
            {new String[]{"equalIntensityPace"}, 2, null},
            {new String[]{"runningPower"}, 2, null},
            {new String[]{"verticalStrideRatio"}, 2, null},
            {new String[]{"verticalAmplitude"}, 2, null}
    };

    private static List<Seg> decodeRecord(byte[] buf, Track out) {
        List<Seg> segs = new ArrayList<Seg>();
        if (buf == null || buf.length < 12) return segs;
        out.startTs = u32(buf, 0);
        out.recVer = (int) u8(buf, 5);
        out.recProto = (int) u8(buf, 6);
        int n = buf.length;

        if (out.recProto == 23) {
            int o = 12;
            while (n - o >= 23) {
                Seg s = new Seg();
                s.alt0 = f32(buf, o);
                long count = u32(buf, o + 4);
                s.resume = u32(buf, o + 8);
                s.dist = u32(buf, o + 13);
                s.dur = u32(buf, o + 17);
                o += 23;
                if (count <= 0 || count > 40000 || o + count * 13 > n + 1) break;
                for (int p = 0; p < count; p++) {
                    int hr = (int) u8(buf, o + 1);
                    int spd10 = (int) u8(buf, o + 8);
                    int alt = u16(buf, o + 2);
                    s.pts.add(new double[]{
                            (hr >= 30 && hr <= 230) ? hr : 0,
                            0,
                            alt,
                            spd10 > 0 ? spd10 / 36.0 : 0});
                    o += 13;
                }
                segs.add(s);
            }
            return segs;
        }
        if (out.recProto != 22) return segs;

        int vlen = 7, off = 8 + vlen;
        int[] validity = new int[vlen];
        for (int i = 0; i < vlen; i++) validity[i] = (int) u8(buf, 8 + i);

        List<Object[]> present = new ArrayList<Object[]>();
        for (int k = 0; k < V9.length; k++) {
            String[] names = (String[]) V9[k][0];
            int size = (Integer) V9[k][1];
            int[][] bits = (int[][]) V9[k][2];
            int nib = ((k % 2 == 0) ? (validity[k >> 1] >> 4) : validity[k >> 1]) & 0x0F;
            if ((nib & 8) != 0) {
                boolean[] valid = new boolean[names.length];
                for (int b = 0; b < names.length; b++) valid[b] = (nib & (4 >> b)) != 0;
                present.add(new Object[]{names, size, bits, valid});
            }
        }

        int o = off;
        while (n - o >= 27) {
            Seg s = new Seg();
            s.alt0 = f32(buf, o);
            long count = u32(buf, o + 4);
            s.resume = u32(buf, o + 8);
            s.dist = u32(buf, o + 13);
            s.dur = u32(buf, o + 17);
            o += 27;
            for (int p = 0; p < count; p++) {
                double hr = 0, cad = 0, alt = 0, stride = 0, ct = 0, vamp = 0, vsr = 0, cal = 0, steps = 0;
                for (Object[] f : present) {
                    String[] nm = (String[]) f[0];
                    int sz = (Integer) f[1];
                    int[][] bt = (int[][]) f[2];
                    boolean[] vd = (boolean[]) f[3];
                    long val = sz == 1 ? (long) u8(buf, o) : (sz == 2 ? (long) u16(buf, o)
                            : (sz == 4 ? u32(buf, o) : 0L));
                    o += sz;
                    for (int w = 0; w < nm.length; w++) {
                        if (!vd[w]) continue;
                        double v = (bt == null || bt[w] == null) ? val
                                : (val >>> bt[w][0]) & ((1L << bt[w][1]) - 1);
                        switch (nm[w]) {
                            case "heartRate": hr = v; break;
                            case "cadence": cad = v; break;
                            case "altitude": alt = v; break;
                            case "stride": stride = v; break;
                            case "contactTime": ct = v; break;
                            case "verticalAmplitude": vamp = v; break;
                            case "verticalStrideRatio": vsr = v; break;
                            case "newCalories": cal = v; break;
                            case "newSteps": steps = v; break;
                            default: break;
                        }
                    }
                }
                s.pts.add(new double[]{hr, cad, alt, 0, stride, ct, vamp, vsr, cal, steps});
            }
            segs.add(s);
        }
        return segs;
    }

    private static List<Gpt> decodeGps(byte[] buf, Track out) {
        List<Gpt> pts = new ArrayList<Gpt>();
        if (buf == null || buf.length < 9) return pts;
        out.gpsVer = (int) u8(buf, 5);
        int o;
        if (out.gpsVer == 3) {
            o = 9;
            int n3 = (buf.length - o) / 26;
            for (int i = 0; i < n3; i++) {
                int p = o + i * 26;
                Gpt g = new Gpt();
                g.ts = u32(buf, p);
                g.lng = f32(buf, p + 4);
                g.lat = f32(buf, p + 8);
                int sas = u16(buf, p + 16);
                g.alt = f32(buf, p + 18);
                if (!(g.lng > -180 && g.lng < 180 && g.lat > -90 && g.lat < 90)) continue;
                g.spd = ((sas & 0xFFF0) >> 4) / 10.0;
                pts.add(g);
            }
            return pts;
        }
        if (out.gpsVer == 5) {
            o = 10;
            long cnt = u32(buf, o);
            int start = o + 4;
            int avail = (buf.length - start) / 27;
            int lim = (int) Math.min(cnt, avail);
            for (int j = 0; j < lim; j++) {
                int q = start + j * 27;
                Gpt g = new Gpt();
                g.ts = u32(buf, q);
                g.lng = f32(buf, q + 4);
                g.lat = f32(buf, q + 8);
                g.spd = f32(buf, q + 12);
                if (!(g.lng > -180 && g.lng < 180 && g.lat > -90 && g.lat < 90)) continue;
                pts.add(g);
            }
        }
        return pts;
    }

    private static double hav(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }

    private static double median(List<Double> vals) {
        if (vals.isEmpty()) return 0;
        Double[] a = vals.toArray(new Double[0]);
        Arrays.sort(a);
        int m = a.length >> 1;
        return (a.length % 2 == 1) ? a[m] : (a[m - 1] + a[m]) / 2;
    }

    /** 解码并分析；recBuf/gpsBuf 为空时返回 null */
    public static Track analyze(byte[] recBuf, byte[] gpsBuf) {
        if (recBuf == null && gpsBuf == null) return null;
        Track out = new Track();
        List<Seg> segs = decodeRecord(recBuf, out);
        List<Gpt> gpts = decodeGps(gpsBuf, out);

        // 帧
        List<Frame> frames = new ArrayList<Frame>();
        for (Seg s : segs) {
            for (int i = 0; i < s.pts.size(); i++) {
                double[] p = s.pts.get(i);
                Frame f = new Frame();
                f.ts = s.resume + i;
                f.seg = s;
                f.idx = i;
                f.hr = p[0];
                f.cad = p[1];
                f.alt = p[2] / 10.0;
                f.speed = p[3];
                if (p.length > 4) {
                    f.stride = p[4]; f.ct = p[5]; f.vamp = p[6]; f.vsr = p[7];
                    f.cal = p[8]; f.steps = p[9];
                }
                frames.add(f);
            }
        }
        java.util.Collections.sort(frames, new java.util.Comparator<Frame>() {
            public int compare(Frame a, Frame b) { return Long.compare(a.ts, b.ts); }
        });

        // GPS 时间序列 + 累计距离
        long[] gts = new long[gpts.size()];
        double[] cum = new double[gpts.size()];
        if (!gpts.isEmpty()) {
            double c = 0;
            Gpt prev = gpts.get(0);
            gts[0] = prev.ts;
            cum[0] = 0;
            for (int i = 1; i < gpts.size(); i++) {
                double d = hav(prev.lat, prev.lng, gpts.get(i).lat, gpts.get(i).lng);
                if (d > 50) d = 0;
                c += d;
                cum[i] = c;
                prev = gpts.get(i);
                gts[i] = gpts.get(i).ts;
            }
        }

        if (frames.isEmpty() && !gpts.isEmpty()) {
            Seg sg = new Seg();
            sg.count = gpts.size();
            sg.dist = cum[cum.length - 1];
            sg.dur = gpts.size();
            sg.resume = gpts.get(0).ts;
            sg.alt0 = gpts.get(0).alt;
            sg.gpsOnly = true;
            for (int gg = 0; gg < gpts.size(); gg++) {
                Frame f = new Frame();
                f.ts = gpts.get(gg).ts;
                f.seg = sg;
                f.idx = gg;
                f.alt = gpts.get(gg).alt;
                frames.add(f);
            }
            segs = new ArrayList<Seg>();
            segs.add(sg);
        }

        boolean bikeSpeed = (out.recProto == 23);
        for (Frame f : frames) {
            Gpt g = gpsAt(gpts, gts, cum, f.ts);
            double speed;
            if (bikeSpeed && f.speed > 0.3) speed = f.speed;
            else if (g != null && g.spd > 0.2) speed = g.spd;
            else if (f.pace >= 100 && f.pace <= 3000) speed = 1000 / f.pace;
            else speed = 0;
            if (speed > 15) speed = 0;
            f.speed = speed;
            if (g != null) { f.lat = g.lat; f.lng = g.lng; }
        }

        // 按段分配距离（速度加权）
        List<Seg> order = new ArrayList<Seg>();
        List<List<Frame>> groups = new ArrayList<List<Frame>>();
        for (Frame h : frames) {
            int i = order.indexOf(h.seg);
            if (i < 0) { order.add(h.seg); groups.add(new ArrayList<Frame>()); i = order.size() - 1; }
            groups.get(i).add(h);
        }
        double acc = 0;
        for (int si = 0; si < order.size(); si++) {
            List<Frame> rows = groups.get(si);
            double total = order.get(si).dist;
            double wsum = 0;
            double[] w = new double[rows.size()];
            for (int i = 0; i < rows.size(); i++) { w[i] = Math.max(rows.get(i).speed, 0.05); wsum += w[i]; }
            if (wsum == 0) wsum = 1;
            double a = 0;
            for (int i = 0; i < rows.size(); i++) {
                a += total * w[i] / wsum;
                rows.get(i).dist = Math.round((acc + Math.min(a, total)) * 10) / 10.0;
            }
            acc += total;
        }
        out.totalDist = acc;
        out.harness = frames;
        out.timer = frames.size();
        if (!frames.isEmpty()) {
            out.elapsed = (int) (frames.get(frames.size() - 1).ts - out.startTs + 1);
        }

        // 海拔平滑
        int K = 29;
        List<Double> valid = new ArrayList<Double>();
        for (Frame h : frames) if (h.alt > 0 && h.alt < 9000) valid.add(h.alt);
        double fill = median(valid);
        double[] seq = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            double a = frames.get(i).alt;
            seq[i] = (a > 0 && a < 9000) ? a : fill;
        }
        out.smAlt = new double[seq.length];
        for (int i = 0; i < seq.length; i++) {
            int lo = Math.max(0, i - K), hi = Math.min(seq.length, i + K + 1);
            double sum = 0;
            for (int j = lo; j < hi; j++) sum += seq[j];
            out.smAlt[i] = sum / (hi - lo);
        }
        double asc = 0, des = 0;
        Double prevA = null;
        for (double a : out.smAlt) {
            if (prevA != null) {
                double d = a - prevA;
                if (d > 0) asc += d; else des -= d;
            }
            prevA = a;
        }
        out.ascent = asc;
        out.descent = des;

        // 汇总
        List<Double> hrs = new ArrayList<Double>(), cads = new ArrayList<Double>(), spds = new ArrayList<Double>();
        for (Frame h : frames) {
            if (h.hr > 0 && h.hr < 260) hrs.add(h.hr);
            if (h.cad > 0 && h.cad < 260) cads.add(h.cad);
            if (h.speed > 0) spds.add(h.speed);
        }
        out.avgHr = avg(hrs);
        out.maxHr = max(hrs);
        out.avgCad = avg(cads);
        out.maxCad = max(cads);
        out.maxSpeed = max(spds);
        out.avgSpeed = out.timer > 0 ? out.totalDist / out.timer : 0;

        // 曲线抽样（最多约 300 点）
        int step = Math.max(1, (int) Math.floor(frames.size() / 300.0));
        int cnt = (frames.size() + step - 1) / step;
        out.t = new double[cnt]; out.mtArr = new double[cnt]; out.hr = new double[cnt];
        out.pace = new double[cnt]; out.alt = new double[cnt]; out.cad = new double[cnt];
        out.spd = new double[cnt]; out.stride = new double[cnt]; out.ct = new double[cnt];
        out.vamp = new double[cnt]; out.vsr = new double[cnt];
        for (int k = 0, i = 0; k < frames.size() && i < cnt; k += step, i++) {
            Frame h = frames.get(k);
            out.t[i] = h.ts > 1e9 ? h.ts : 0;
            out.mtArr[i] = k;
            out.hr[i] = h.hr;
            double pv = h.pace > 0 ? h.pace : (h.speed > 0 ? Math.round(1000 / h.speed) : 0);
            out.pace[i] = (pv >= 180 && pv <= 1200) ? pv : 0;
            out.alt[i] = Math.round(out.smAlt[k] * 10) / 10.0;
            out.cad[i] = h.cad;
            out.stride[i] = h.stride;
            out.ct[i] = h.ct;
            out.vamp[i] = h.vamp > 0 ? Math.round(h.vamp) / 10.0 : 0;
            out.vsr[i] = h.vsr > 0 ? Math.round(h.vsr) / 10.0 : 0;
            out.spd[i] = Math.round(h.speed * 36) / 10.0;
        }
        // 速度 5 点中值滤波
        double[] raw = out.spd.clone();
        for (int i = 0; i < raw.length; i++) {
            if (!(raw[i] > 0)) continue;
            List<Double> win = new ArrayList<Double>();
            for (int j = Math.max(0, i - 2); j <= Math.min(raw.length - 1, i + 2); j++)
                if (raw[j] > 0) win.add(raw[j]);
            if (win.size() >= 3) {
                java.util.Collections.sort(win);
                out.spd[i] = Math.round(win.get(win.size() / 2) * 10) / 10.0;
            }
        }

        // 地图轨迹（最多 2000 点）
        if (!frames.isEmpty()) {
            int tstep = Math.max(1, (int) Math.ceil(frames.size() / 2000.0));
            for (int i = 0; i < frames.size(); i += tstep) {
                Frame h = frames.get(i);
                if (Double.isNaN(h.lat) || Double.isNaN(h.lng)) continue;
                out.track.add(new double[]{h.lat, h.lng, h.speed, h.dist});
            }
        }
        out.selfTest = cnt > 0;
        return out;
    }

    private static Gpt gpsAt(List<Gpt> pts, long[] gts, double[] cum, long ts) {
        if (pts.isEmpty()) return null;
        int lo = 0, hi = gts.length;
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if (gts[mid] < ts) lo = mid + 1; else hi = mid;
        }
        List<Integer> cand = new ArrayList<Integer>();
        if (lo - 1 >= 0) cand.add(lo - 1);
        if (lo < gts.length) cand.add(lo);
        if (lo + 1 < gts.length) cand.add(lo + 1);
        if (cand.isEmpty()) return null;
        int best = cand.get(0);
        for (int j : cand) if (Math.abs(gts[j] - ts) < Math.abs(gts[best] - ts)) best = j;
        if (Math.abs(gts[best] - ts) > 3) return null;
        return pts.get(best);
    }

    private static double avg(List<Double> v) {
        if (v.isEmpty()) return 0;
        double s = 0;
        for (double x : v) s += x;
        return s / v.size();
    }

    private static double max(List<Double> v) {
        double m = 0;
        for (double x : v) if (x > m) m = x;
        return m;
    }
}
