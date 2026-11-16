package com.hanxing.mifit;

import java.util.HashMap;
import java.util.Map;

/** 实时扫描手机上的逐点轨迹文件（每次打开都重扫，刚跑完的场次立刻可见） */
public class TrackIdx {
    public static class File2 {
        public long ts;
        public int proto;
        public String recordPath;   // 绝对路径，交给 root shell 读
        public String gpsPath;
    }

    public static Map<Long, File2> scan(RootShell sh) {
        Map<Long, File2> out = new HashMap<Long, File2>();
        String r = sh.find(Server.TRK_PRIVATE + " -type f -path '*/sport/*' -name '*_record'");
        if (r == null) return out;
        String prefix = Server.TRK_PRIVATE + "/";
        for (String line : r.split("\n")) {
            line = line.trim();
            if (!line.endsWith("_record") || line.contains("/daily/")) continue;
            String name = line.substring(line.lastIndexOf('/') + 1);
            String[] p = name.split("_");
            if (p.length < 3) continue;
            long ts;
            int proto;
            try { ts = Long.parseLong(p[0]); proto = Integer.parseInt(p[1]); } catch (Exception e) { continue; }
            if (proto != 22 && proto != 23) continue;
            File2 f = new File2();
            f.ts = ts;
            f.proto = proto;
            f.recordPath = line.startsWith(prefix) ? line : prefix + line;
            String base = f.recordPath.substring(0, f.recordPath.length() - "_record".length());
            f.gpsPath = base + "_gps";
            out.put(ts, f);
        }
        return out;
    }

    /** 文件名时间戳与库里 start_time 可能差几秒，30 秒内匹配 */
    public static File2 match(Map<Long, File2> idx, long start) {
        File2 exact = idx.get(start);
        if (exact != null) return exact;
        File2 best = null;
        long bestD = Long.MAX_VALUE;
        for (Map.Entry<Long, File2> e : idx.entrySet()) {
            long d = Math.abs(e.getKey() - start);
            if (d < bestD) { bestD = d; best = e.getValue(); }
        }
        return bestD <= 30 ? best : null;
    }
}
