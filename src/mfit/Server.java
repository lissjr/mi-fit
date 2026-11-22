package com.hanxing.mifit;

/**
 * 小米运动健康的私有数据位置。
 * 每台手机的账号目录、地区目录、甚至库文件名都不一样（databases/<账号ID>/<地区>/fitness_data），
 * 所以一律现场去找；找不到就返回空串，绝不拿某一台机器的路径硬套。
 */
public class Server {
    public static final String PKG = "com.mi.health";
    /** 只用于诊断日志，不参与取数 */
    public static final String DB_DIR = "/data/data/" + PKG + "/databases";

    private static String cachedDb;
    private static String cachedTrk;

    /** 库文件绝对路径；找不到返回 "" */
    public static synchronized String dbPrivate(RootShell sh) {
        if (sh == null || !sh.ready()) return "";
        if (cachedDb != null && sh.size(cachedDb) > 0) return cachedDb;
        cachedDb = null;

        String dd = "/databases";
        String[] probes = new String[]{
                "ls -1t /data/data/" + PKG + dd + "/*/*/fitness_data 2>/dev/null",
                "ls -1t /data/user/0/" + PKG + dd + "/*/*/fitness_data 2>/dev/null",
                "ls -1t /data/user/*/" + PKG + dd + "/*/*/fitness_data 2>/dev/null",
                "ls -1t /data/data/" + PKG + dd + "/*/fitness_data 2>/dev/null",
                "ls -1t /data/user/0/" + PKG + dd + "/*/fitness_data 2>/dev/null",
                "ls -1t /data/user/*/" + PKG + dd + "/*/fitness_data 2>/dev/null",
                "find /data/data/" + PKG + dd + " -maxdepth 4 -name fitness_data 2>/dev/null",
                "find /data/user/0/" + PKG + dd + " -maxdepth 4 -name fitness_data 2>/dev/null",
                "find /data/user/*/" + PKG + dd + " -maxdepth 4 -name fitness_data 2>/dev/null",
        };
        String best = null;
        long bestSize = 0;
        String alt = null;
        long altSize = 0;
        for (String c : probes) {
            String r = sh.raw(c);
            for (String line : r.split("\n")) {
                String s = line.trim();
                if (!s.startsWith("/")) continue;
                if (s.indexOf("\u0001") >= 0) continue;
                if (s.indexOf("/" + PKG + "/") < 0) continue;
                String name = s.substring(s.lastIndexOf('/') + 1);
                // 只认库文件本体：同目录里还有 -wal / -shm / journal，以及 MMKV 的 fitness_data_vore，
                // 这些都不是 SQLite，拿了会解析失败
                boolean exact = "fitness_data".equals(name);
                boolean dbLike = "fitness_data.db".equals(name);
                if (!exact && !dbLike) continue;
                long sz = sh.size(s);
                if (sz <= 8) continue;
                // 一台机上可能同时存在多个账号目录（换过账号，或小米健康自己新建的空库），
                // 取最大的那个才是真库
                if (exact) { if (sz > bestSize) { best = s; bestSize = sz; } }
                else if (sz > altSize) { alt = s; altSize = sz; }
            }
        }
        cachedDb = best != null ? best : alt;
        android.util.Log.i("mfit", "db pick=" + cachedDb + " size="
                + (best != null ? bestSize : altSize));
        return cachedDb == null ? "" : cachedDb;
    }

    /** 逐点轨迹根目录（下面按时间戳放 *_record / *_gps）；找不到返回 "" */
    public static synchronized String trkPrivate(RootShell sh) {
        if (sh == null || !sh.ready()) return "";
        if (cachedTrk != null) return cachedTrk;
        String[] probes = new String[]{
                "ls -1t /data/data/" + PKG + "/files/fitness 2>/dev/null",
                "ls -1t /data/data/" + PKG + "/files/*/fitness 2>/dev/null",
                "ls -1t /data/user/0/" + PKG + "/files/fitness 2>/dev/null",
                "ls -1t /data/user/0/" + PKG + "/files/*/fitness 2>/dev/null",
                "ls -1t /data/user/*/" + PKG + "/files/fitness 2>/dev/null",
                "find /data/data/" + PKG + "/files -maxdepth 4 -type d -name fitness 2>/dev/null",
                "find /data/user/0/" + PKG + "/files -maxdepth 4 -type d -name fitness 2>/dev/null",
                "find /data/user/*/" + PKG + "/files -maxdepth 4 -type d -name fitness 2>/dev/null",
        };
        for (String c : probes) {
            String r = sh.raw(c);
            for (String line : r.split("\n")) {
                String s = line.trim();
                if (!s.startsWith("/")) continue;
                if (!"fitness".equals(s.substring(s.lastIndexOf('/') + 1))) continue;
                if (!sh.raw("test -d '" + s + "' && echo :d:").contains(":d:")) continue;
                cachedTrk = s;
                return s;
            }
        }
        return "";
    }

    /** 给用户看的一句话诊断，不把 dd / ls 原文怼到界面上 */
    public static synchronized String why(RootShell sh) {
        if (sh == null || !sh.ready()) {
            String f = RootShell.fail();
            if ("auth".equals(f)) return "root 授权框还没点：在 Magisk / KernelSU 的弹窗里点“允许”，再重进一次 App";
            if ("deny".equals(f)) return "root 被拒：到 KernelSU / Magisk 里把这个 App 改成“允许”（刚装上或换了手机会要重新授权一次）";
            if ("nosu".equals(f)) return "这台手机没找到 su：确认已经 root，并在 root 管理器里给这个 App 授权";
            return "root 没起来：到 root 管理器（Magisk / KernelSU）里给本 App 授权，已授权就重启手机再试";
        }
        if (!sh.pkgVisible()) return "root 拿到了，但看不到小米运动健康的数据目录：重启手机再打开一次；还不行就到 root 管理器里关掉对本 App 的挂载隔离";
        String pkg = sh.raw("ls -d /data/data/" + PKG + " /data/user/*/" + PKG + " 2>&1");
        if (pkg.contains("Permission denied")) return "权限不足：到 root 管理器给本 App 授权";
        if (!pkg.contains("/" + PKG)) return "这台手机没装小米运动健康";
        String dbs = sh.raw("ls " + DB_DIR + " 2>&1");
        if (dbs.contains("Permission denied")) return "权限不足：到 root 管理器给本 App 授权";
        return "小米运动健康还没在本机登录同步过，先在 App 里打开一次并同步";
    }

    public static synchronized void reset() { cachedDb = null; cachedTrk = null; }
}
