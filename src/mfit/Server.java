package com.hanxing.mifit;

/** 小米运动健康的私有数据位置 */
public class Server {
    // 账号目录因人而异（databases/<账号ID>/cn/fitness_data），这里只作兜底
    public static final String DB_PRIVATE = "/data/data/com.mi.health/databases/2203130080/cn/fitness_data";
    public static final String TRK_PRIVATE = "/data/data/com.mi.health/files/fitness";

    private static String cachedDb;

    /** 现查当前账号的库文件：/data/data/com.mi.health/databases/<账号ID>/cn/fitness_data */
    public static synchronized String dbPrivate(RootShell sh) {
        if (cachedDb != null) return cachedDb;
        if (sh == null || !sh.ready()) return DB_PRIVATE;
        String r = sh.raw("ls -1t /data/data/com.mi.health/databases/*/cn/fitness_data 2>/dev/null");
        for (String line : r.split("\n")) {
            String s = line.trim();
            if (s.startsWith("/data/data/") && s.endsWith("/cn/fitness_data")) {
                cachedDb = s;
                return s;
            }
        }
        return DB_PRIVATE;
    }

    public static synchronized void reset() { cachedDb = null; }
}
