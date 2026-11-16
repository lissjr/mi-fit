package com.hanxing.mifit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读 SQLite 解析：需要哪一页就让 root shell 把那一页写到 cache 再读（不整库载入）。
 * 算法与网页版 sqlite.js 一致。
 */
public class Db {
    private final Map<Long, byte[]> cache = new HashMap<Long, byte[]>();
    private final Map<String, Object[]> tableCache = new HashMap<String, Object[]>();
    private byte[] pre;        // 预读的尾部大块
    private long preFrom;      // 块起始页号
    private int prePages;
    /** 实际发出的 dd 次数，用来观察 IO 开销 */
    public int ioCount;
    public int preHits;
    private final RootShell sh;
    private final String path;
    private int pageSize, usable;

    public Db(RootShell sh, String path) { this.sh = sh; this.path = path; }

    public void open() throws Exception {
        byte[] hdr = read(0, 100);
        if (hdr == null) throw new Exception("读不到数据库");
        String magic = new String(new byte[]{hdr[0], hdr[1], hdr[2], hdr[3]}, "US-ASCII");
        if (!"SQLi".equals(magic)) throw new Exception("不是 SQLite 文件");
        pageSize = (hdr[16] << 8) | hdr[17];
        if (pageSize == 1) pageSize = 65536;
        int reserved = hdr[20] & 0xff;
        usable = pageSize - reserved;
        preloadTail(1800);
    }

    /** 预读文件末尾若干页到内存：几百次单页 dd 的活，变成一次大块读 */
    public void preloadTail(int pages) {
        try {
            long size = sh.size(path);
            if (size <= 0 || pageSize <= 0) return;
            long total = size / pageSize;
            if (total < 4) return;
            int n = (int) Math.min(pages, total - 2);
            long from = total - n + 1;
            byte[] b = sh.slice(path, (from - 1) * (long) pageSize, n * pageSize);
            if (b != null && b.length >= (int) ((n - 1) * (long) pageSize)) {
                pre = b;
                preFrom = from;
                prePages = n;
                android.util.Log.i("mfit", "preload from=" + from + " pages=" + n
                        + " bytes=" + b.length + " total=" + (size / 1024 / 1024) + "MB");
            } else {
                android.util.Log.w("mfit", "preload short n=" + n + " got=" + (b == null ? -1 : b.length));
            }
        } catch (Exception e) {
            android.util.Log.w("mfit", "preload fail " + e);
        }
    }

    /** 预读块里取一段；取不到返回 null */
    private byte[] fromPreload(long off, int len) {
        if (pre == null) return null;
        long pg = off / pageSize + 1;
        long baseL = (pg - preFrom) * (long) pageSize + (off % pageSize);
        if (pg < preFrom || baseL < 0 || baseL + len > pre.length) return null;
        byte[] out = new byte[len];
        System.arraycopy(pre, (int) baseL, out, 0, len);
        return out;
    }

    public int pageSize() { return pageSize; }

    /** 读一段字节（按页缓存） */
    public byte[] read(long off, int len) throws Exception {
        byte[] p = fromPreload(off, len);
        if (p != null) { preHits++; return p; }
        byte[] c = cache.get(off);
        if (c != null && c.length >= len) return c;
        ioCount++;
        byte[] out = sh.slice(path, off, len);
        if (out == null) throw new Exception("读取失败 @" + off);
        if (cache.size() > 3000) cache.clear();
        cache.put(off, out);
        return out;
    }

    // ---------- 基础类型 ----------
    private static int u16(byte[] b, int o) { return ((b[o] & 0xff) << 8) | (b[o + 1] & 0xff); }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    /** varint：大端 7 位累积，返回 [值, 字节数] */
    private static long[] varint(byte[] b, int off) {
        long v = 0;
        int i;
        for (i = 0; i < 9; i++) {
            int c = b[off + i] & 0xff;
            if (i == 8) { v = v * 256 + c; i++; break; }
            v = v * 128 + (c & 0x7f);
            if ((c & 0x80) == 0) { i++; break; }
        }
        return new long[]{v, i};
    }

    private static long beInt(byte[] b, int o, int n, boolean signed) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (b[o + i] & 0xff);
        if (signed && n < 8) {
            long bits = 1L << (n * 8 - 1);
            if (v >= bits) v -= (1L << (n * 8));
        }
        return v;
    }

    /** 解析一个 cell 的 payload 为值数组 */
    private static Object[] parseRecord(byte[] payload) {
        long[] h = varint(payload, 0);
        int hdrEnd = (int) h[0];
        int off = (int) h[1];
        List<Integer> types = new ArrayList<Integer>();
        while (off < hdrEnd) {
            long[] t = varint(payload, off);
            types.add((int) t[0]);
            off += (int) t[1];
        }
        Object[] vals = new Object[types.size()];
        int p = hdrEnd;
        for (int i = 0; i < types.size(); i++) {
            int t = types.get(i);
            if (t == 0) vals[i] = null;
            else if (t >= 1 && t <= 6) {
                int n = new int[]{1, 2, 3, 4, 6, 8}[t - 1];
                vals[i] = Long.valueOf(beInt(payload, p, n, true));
                p += n;
            } else if (t == 7) {
                long bits = 0;
                for (int k = 0; k < 8; k++) bits = (bits << 8) | (payload[p + k] & 0xffL);
                vals[i] = Double.valueOf(Double.longBitsToDouble(bits));
                p += 8;
            } else if (t == 8) vals[i] = Long.valueOf(0);
            else if (t == 9) vals[i] = Long.valueOf(1);
            else {
                int len = (t % 2 == 0) ? (t - 12) / 2 : (t - 13) / 2;
                if (t % 2 == 0) {
                    byte[] raw = new byte[len];
                    System.arraycopy(payload, p, raw, 0, len);
                    vals[i] = raw;
                } else {
                    vals[i] = new String(payload, p, len, java.nio.charset.Charset.forName("UTF-8"));
                }
                p += len;
            }
        }
        return vals;
    }

    /** 读取一个 cell 的 payload，处理溢出页 */
    private byte[] readPayload(byte[] cell, int off, boolean isLeafTablePage) throws Exception {
        long[] plen = varint(cell, off);
        off += (int) plen[1];
        long[] rowid = varint(cell, off);
        off += (int) rowid[1];
        int len = (int) plen[0];
        int maxLocal = usable - 35;
        int minLocal = ((usable - 12) * 32 / 255) - 23;
        if (len <= maxLocal) {
            byte[] out = new byte[len];
            System.arraycopy(cell, off, out, 0, Math.min(len, cell.length - off));
            return out;
        }
        int local = minLocal + ((len - minLocal) % (usable - 4));
        byte[] out = new byte[len];
        int copy = Math.min(local, Math.max(0, cell.length - off));
        System.arraycopy(cell, off, out, 0, copy);
        long next = u32(cell, off + local);
        int filled = local;
        while (next != 0 && filled < len) {
            byte[] pg = read((next - 1) * pageSize, pageSize);
            next = u32(pg, 0);
            int n = Math.min(usable - 4, len - filled);
            System.arraycopy(pg, 4, out, filled, n);
            filled += n;
        }
        return out;
    }

    private void walk(long pgno, RowSink sink) throws Exception {
        byte[] page = read((pgno - 1) * pageSize, pageSize);
        int ho = (pgno == 1) ? 100 : 0;
        int type = page[ho] & 0xff;
        int nCell = u16(page, ho + 3);
        int ptrStart = (type == 0x05) ? ho + 12 : ho + 8;
        if (type == 0x05) {
            List<Long> kids = new ArrayList<Long>();
            for (int i = 0; i < nCell; i++) {
                int cellOff = u16(page, ptrStart + i * 2);
                kids.add(u32(page, cellOff));
            }
            kids.add(u32(page, ho + 8));
            for (Long k : kids) walk(k, sink);
        } else if (type == 0x0d) {
            for (int i = 0; i < nCell; i++) {
                int cellOff = u16(page, ptrStart + i * 2);
                sink.row(parseRecord(readPayload(page, cellOff, true)));
            }
        } else {
            throw new Exception("未知页类型 " + type + " @" + pgno);
        }
    }

    public interface RowSink { void row(Object[] vals); }

    public void readTable(long rootPage, RowSink sink) throws Exception {
        walk(rootPage, sink);
    }

    /** CREATE TABLE 里的列名 */
    public static List<String> parseCols(String sql) {
        List<String> out = new ArrayList<String>();
        if (sql == null) return out;
        int a = sql.indexOf('('), b = sql.lastIndexOf(')');
        if (a < 0 || b < a) return out;
        String body = sql.substring(a + 1, b);
        List<String> parts = new ArrayList<String>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : body.toCharArray()) {
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (ch == ',' && depth == 0) { parts.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        parts.add(cur.toString());
        for (String s : parts) {
            String t = s.trim();
            if (t.startsWith("PRIMARY") || t.startsWith("UNIQUE") || t.startsWith("CONSTRAINT")
                    || t.startsWith("CHECK") || t.startsWith("FOREIGN")) continue;
            String name = null;
            int i = 0;
            char c0 = t.length() > 0 ? t.charAt(0) : ' ';
            if (c0 == '`' || c0 == '"' || c0 == '[') {
                int end = t.indexOf(c0 == '[' ? ']' : c0, 1);
                if (end > 1) name = t.substring(1, end);
            } else {
                while (i < t.length()) {
                    char c = t.charAt(i);
                    if (Character.isLetterOrDigit(c) || c == '_') i++;
                    else break;
                }
                if (i > 0) name = t.substring(0, i);
            }
            if (name != null) out.add(name);
        }
        return out;
    }

    /** 找到某张表：返回 {rootPage, sql}（结果缓存，避免每张表都重扫 sqlite_master） */
    public Object[] findTable(String name) throws Exception {
        Object[] hit = tableCache.get(name);
        if (hit != null) return hit;
        final Object[][] holder = new Object[1][];
        readTable(1, new RowSink() {
            public void row(Object[] v) {
                if (v.length > 4 && name.equals(v[1])) {
                    holder[0] = new Object[]{((Long) v[3]).longValue(), v[4]};
                }
            }
        });
        if (holder[0] != null) tableCache.put(name, holder[0]);
        return holder[0];
    }

    /**
     * 只取表尾部最新的若干行（B-tree 最右子树，只读 3~8 个页）。
     * 沿最右路径下到叶子，再按父页给出的兄弟叶子页号一次性 dd 读回，
     * 只解析确实属于本表的页 —— 不会把相邻其它表的页读进来。
     */
    public List<Map<String, Object>> readTail(String table, int limit) throws Exception {
        Object[] t = findTable(table);
        if (t == null) throw new Exception("找不到表 " + table);
        long root = ((Long) t[0]).longValue();
        List<String> cols = parseCols((String) t[1]);

        long leaf = 0;
        long pg = root;
        int io0 = ioCount;
        byte[] parentPage = null;
        int parentHo = 0;
        byte[] lastLeaf = null;
        int lastLeafHo = 0;
        for (int guard = 0; guard < 12; guard++) {
            int ho = (pg == 1) ? 100 : 0;
            byte[] page = read((pg - 1) * pageSize, pageSize);
            if ((page[ho] & 0xff) != 0x05) {
                lastLeaf = page; lastLeafHo = ho;
                break;
            }
            parentPage = page; parentHo = ho;
            long kid = u32(page, ho + 8);
            if (kid <= 0) break;
            pg = kid;
        }
        leaf = pg;

        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        if (lastLeaf != null) collectLeaf(lastLeaf, lastLeafHo, cols, rows, limit - rows.size());
        if (rows.size() < limit && parentPage != null) {
            int nCell = u16(parentPage, parentHo + 3);
            int ptrStart = parentHo + 12;
            // 父页里按从右到左列出子叶页（第一个就是刚读过的 lastLeaf，跳过）
            List<Long> kids = new ArrayList<Long>();
            for (int i = nCell - 1; i >= 0; i--) {
                int cellOff = u16(parentPage, ptrStart + i * 2);
                long kid = u32(parentPage, cellOff);
                if (kid <= 0 || kid == leaf) continue;
                kids.add(kid);
            }
            long minPg = Long.MAX_VALUE, maxPg = 0;
            for (long k : kids) {
                minPg = Math.min(minPg, k);
                maxPg = Math.max(maxPg, k);
            }
            // 阶段一：从最新的一页往左逐页取（页大多在预读块里，几乎不花 dd）
            // 连续 3 页都没有新行就认为到头了，别为了凑 limit 把全表翻一遍
            int empty = 0;
            int idx = 0;
            for (; idx < kids.size() && rows.size() < limit; idx++) {
                long kid = kids.get(idx);
                int ho = (kid == 1L) ? 100 : 0;
                byte[] page;
                try {
                    page = read((kid - 1) * pageSize, pageSize);
                } catch (Exception e) {
                    if (++empty >= 3) { idx++; break; }
                    continue;
                }
                int before = rows.size();
                if (page != null && page.length > ho && (page[ho] & 0xff) == 0x0d) {
                    collectLeaf(page, ho, cols, rows, limit - rows.size());
                }
                if (rows.size() == before) {
                    if (++empty >= 3) { idx++; break; }
                } else {
                    empty = 0;
                }
            }

            // 阶段二：还差很多行（说明数据散在全库），按 2048 页一个窗口成段读
            if (rows.size() < limit && idx < kids.size()) {
                final int WIN = 2048;
                List<long[]> wins = new ArrayList<long[]>();
                long wStart = -1, wEnd = -1;
                for (int i = idx; i < kids.size(); i++) {
                    long k = kids.get(i);
                    if (wEnd < 0) { wStart = wEnd = k; }
                    else if (wEnd - k + 1 <= WIN) { wStart = k; }
                    else { wins.add(new long[]{wStart, wEnd}); wStart = wEnd = k; }
                }
                if (wEnd >= 0) wins.add(new long[]{wStart, wEnd});

                for (long[] w : wins) {
                    if (rows.size() >= limit) break;
                    byte[] block = null;
                    try {
                        block = sh.slice(path, (w[0] - 1) * (long) pageSize,
                                (int) ((w[1] - w[0] + 1) * pageSize));
                    } catch (Exception ignored) { }
                    if (block == null) continue;
                    for (int i = idx; i < kids.size(); i++) {
                        if (rows.size() >= limit) break;
                        long k = kids.get(i);
                        if (k < w[0] || k > w[1]) continue;
                        int base = (int) ((k - w[0]) * pageSize);
                        if (base < 0 || base + 16 >= block.length) continue;
                        int ho = (k == 1L) ? 100 : 0;
                        if ((block[base + ho] & 0xff) != 0x0d) continue;
                        byte[] one = new byte[pageSize];
                        System.arraycopy(block, base, one, 0, Math.min(pageSize, block.length - base));
                        collectLeaf(one, ho, cols, rows, limit - rows.size());
                    }
                }
            }
        }
        android.util.Log.i("mfit", "tail " + table + " dd=" + (ioCount - io0)
                + " pre=" + preHits + " rows=" + rows.size() + "/" + limit + " leafPg=" + leaf);
        return rows;
    }

    private void collectLeaf(byte[] page, int ho, List<String> cols,
                             List<Map<String, Object>> out, int want) throws Exception {
        if (page == null || want <= 0) return;
        int nCell = u16(page, ho + 3);
        int ptrStart = ho + 8;
        for (int i = nCell - 1; i >= 0 && out.size() < want; i--) {
            int cellOff = u16(page, ptrStart + i * 2);
            Object[] vals = parseRecord(readPayload(page, cellOff, true));
            Map<String, Object> m = new HashMap<String, Object>();
            for (int k = 0; k < cols.size() && k < vals.length; k++) m.put(cols.get(k), vals[k]);
            out.add(m);
        }
    }

    public List<Map<String, Object>> readTableObjects(String table) throws Exception {
        Object[] t = findTable(table);
        if (t == null) throw new Exception("找不到表 " + table);
        long root = ((Long) t[0]).longValue();
        List<String> cols = parseCols((String) t[1]);
        final List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
        // 先只把内部页走一遍收集叶子页号，再按窗口成段读 —— 整表顺序读比一页一条命令快十倍
        List<Long> leaves = new ArrayList<Long>();
        collectLeaves(root, leaves, 0);
        final int WIN = 1024;
        int i = 0;
        while (i < leaves.size()) {
            long from = leaves.get(i);
            long to = from;
            int j = i;
            while (j + 1 < leaves.size() && leaves.get(j + 1) - from + 1 <= WIN) {
                j++;
                to = Math.max(to, leaves.get(j));
            }
            int cnt = j - i + 1;
            byte[] block = null;
            if (cnt > 2) {
                try {
                    block = sh.slice(path, (from - 1) * (long) pageSize,
                            (int) ((to - from + 1) * pageSize));
                } catch (Exception ignored) { }
            }
            for (int k = i; k <= j; k++) {
                long pg = leaves.get(k);
                int ho = (pg == 1L) ? 100 : 0;
                if (block != null && block.length > 0) {
                    int base = (int) ((pg - from) * pageSize);
                    if (base >= 0 && base + 16 < block.length) {
                        if ((block[base + ho] & 0xff) == 0x0d) {
                            byte[] one = new byte[pageSize];
                            System.arraycopy(block, base, one, 0, Math.min(pageSize, block.length - base));
                            collectLeaf(one, ho, cols, rows, Integer.MAX_VALUE);
                        }
                        continue;
                    }
                }
                byte[] page = read((pg - 1) * pageSize, pageSize);
                if (page != null && page.length > ho && (page[ho] & 0xff) == 0x0d) {
                    collectLeaf(page, ho, cols, rows, Integer.MAX_VALUE);
                }
            }
            i = j + 1;
        }
        return rows;
    }

    /** 只沿内部页往下走，收集所有叶子页号 */
    private void collectLeaves(long pg, List<Long> out, int depth) throws Exception {
        if (depth > 12 || pg <= 0) return;
        int ho = (pg == 1) ? 100 : 0;
        byte[] page = read((pg - 1) * pageSize, pageSize);
        if (page == null || page.length <= ho) return;
        int type = page[ho] & 0xff;
        if (type != 0x05) {
            out.add(pg);
            return;
        }
        int nCell = u16(page, ho + 3);
        int ptrStart = ho + 12;
        for (int i = 0; i < nCell; i++) {
            int cellOff = u16(page, ptrStart + i * 2);
            long kid = u32(page, cellOff);
            if (kid > 0) collectLeaves(kid, out, depth + 1);
        }
    }
}
