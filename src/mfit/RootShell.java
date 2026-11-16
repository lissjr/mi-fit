package com.hanxing.mifit;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.io.Writer;

/**
 * 常驻 root shell：用 su 起一个 sh，通过 stdin/stdout 下指令，
 * 让 root 把需要的字节片段写到 App 自己的 cache 里，App 再直接读。
 * 不用 httpd、不用端口、不复制整库。
 */
public class RootShell {
    private Process p;
    private Writer in;
    private BufferedReader out;
    private File tmp;
    private boolean ok = false;

    /** 清掉历史遗留的库页中转文件（旧版读完没删，里面是原始隐私数据） */
    public static void cleanLeftovers(Context ctx) {
        File d = new File(ctx.getFilesDir(), "cache");
        for (String n : new String[]{"slice.bin", "pages.bin", "cat.bin"}) {
            File f = new File(d, n);
            if (f.exists()) f.delete();
        }
        // 早期版本在这里放过两个指向小米运动健康库的软链接，现在走 root 直读，不需要了
        File web = new File(ctx.getFilesDir(), "web");
        if (web.exists()) {
            File[] kids = web.listFiles();
            if (kids != null) for (File k : kids) k.delete();
            web.delete();
        }
    }

    public synchronized boolean start(Context ctx) {
        if (ok) return true;
        try {
            tmp = new File(ctx.getFilesDir(), "cache");
            if (!tmp.exists()) tmp.mkdirs();
        } catch (Exception e) {
            return false;
        }
        // App 进程的挂载命名空间里看不到小米运动健康的数据目录（被 Magisk 隔离），
        // 逐个尝试进入全局命名空间的启动方式，直到能读到目标目录。
        String[][] cands = new String[][]{
                {"su", "--mount-master", "-c", "sh"},
                {"su", "-c", "/system/bin/nsenter -t 1 -m -- sh"},
                {"su", "-c", "sh"}
        };
        for (String[] c : cands) {
            try {
                close();
                p = new ProcessBuilder(c).redirectErrorStream(false).start();
                in = new OutputStreamWriter(p.getOutputStream(), "UTF-8");
                out = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String probe = cmd("ls /data/data/com.mi.health >/dev/null 2>&1 && echo :ok:");
                if (probe != null && probe.contains(":ok:")) {
                    ok = true;
                    mode = android.text.TextUtils.join(" ", c);
                    return true;
                }
            } catch (Exception e) {
                // 试下一种
            }
        }
        close();
        ok = false;
        return false;
    }

    private String mode = "";
    public String mode() { return mode; }

    public boolean ready() { return ok; }

    /** 直接跑一条命令并返回输出（诊断用） */
    public synchronized String raw(String c) {
        String r = cmd(c);
        return r == null ? "(无输出)" : r.trim();
    }

    /** 发一条命令，等 :END: 标记，返回期间的输出 */
    private String cmd(String c) {
        try {
            in.write(c + "\n");
            in.write("echo :END:\n");
            in.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            int guard = 0;
            while ((line = out.readLine()) != null) {
                if (line.contains(":END:")) break;
                sb.append(line).append('\n');
                if (++guard > 4000) break;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 让 root 把 [off, off+len) 写到 cache/slice.bin，然后读回来 */
    public synchronized byte[] slice(String path, long off, int len) throws Exception {
        if (!ok) throw new Exception("root shell 未就绪");
        File f = new File(tmp, "slice.bin");
        long bs, skip, cnt;
        if (off % 4096 == 0 && len % 4096 == 0) { bs = 4096; skip = off / 4096; cnt = len / 4096; }
        else if (off % 512 == 0 && len % 512 == 0) { bs = 512; skip = off / 512; cnt = len / 512; }
        else { bs = 1; skip = off; cnt = len; }
        if (f.exists()) f.delete();
        String out = cmd("/system/bin/dd if='" + path + "' of='" + f.getAbsolutePath() + "' bs=" + bs +
                " skip=" + skip + " count=" + cnt + " 2>&1; echo rc=$?");
        if (!f.exists() || f.length() == 0) {
            throw new Exception("dd 失败 @" + off + " [" + String.valueOf(out).replace('\n', ' ') + "]");
        }
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        int got = 0;
        while (got < b.length) {
            int n = fis.read(b, got, b.length - got);
            if (n <= 0) break;
            got += n;
        }
        fis.close();
        f.delete();          // 中转文件里是库里的原始隐私数据，读完立刻删掉
        if (got < len) {
            byte[] shorter = new byte[got];
            System.arraycopy(b, 0, shorter, 0, got);
            return shorter;
        }
        return b;
    }

    /**
     * 一次 shell 往返读多个页（页号可以很分散）：命令里把它们依次 dd 追加到同一个文件，
     * 本地再按顺序切回。B-tree 里的叶子页经常东一块西一块，逐页一条命令会慢十几倍。
     */
    public synchronized byte[] pages(String path, long[] pgs, int pageSize) throws Exception {
        if (!ok) throw new Exception("root shell 未就绪");
        if (pgs == null || pgs.length == 0) return new byte[0];
        File f = new File(tmp, "pages.bin");
        if (f.exists()) f.delete();
        StringBuilder sb = new StringBuilder(64 * pgs.length + 32);
        sb.append(": > '").append(f.getAbsolutePath()).append("'");
        for (long pg : pgs) {
            if (pg <= 0) continue;
            sb.append("; /system/bin/dd if='").append(path).append("' bs=").append(pageSize)
              .append(" skip=").append(pg - 1).append(" count=1 >> '")
              .append(f.getAbsolutePath()).append("' 2>/dev/null");
        }
        cmd(sb.toString());
        if (!f.exists() || f.length() == 0) return new byte[0];
        byte[] b = new byte[(int) f.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        int got = 0;
        while (got < b.length) {
            int n = fis.read(b, got, b.length - got);
            if (n <= 0) break;
            got += n;
        }
        fis.close();
        f.delete();          // 中转文件里是库里的原始隐私数据，读完立刻删掉
        return b;
    }

    /** 整个小文件读回来（轨迹文件 10–90KB） */
    public synchronized byte[] cat(String path) {
        if (!ok) return null;
        try {
            File f = new File(tmp, "cat.bin");
            if (f.exists()) f.delete();
            cmd("cat '" + path + "' > '" + f.getAbsolutePath() + "' 2>/dev/null");
            if (!f.exists() || f.length() == 0) return null;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            int got = 0;
            while (got < b.length) {
                int n = fis.read(b, got, b.length - got);
                if (n <= 0) break;
                got += n;
            }
            fis.close();
            f.delete();      // 轨迹原文含经纬度，读完立刻删掉
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** 文件是否存在、多大 */
    public synchronized long size(String path) {
        if (!ok) return -1;
        String r = cmd("wc -c < '" + path + "' 2>/dev/null");
        if (r == null) return -1;
        r = r.trim();
        try { return Long.parseLong(r.split("\\s+")[0]); } catch (Exception e) { return -1; }
    }

    /** 列目录（find） */
    public synchronized String find(String args) {
        if (!ok) return "";
        String r = cmd("find " + args + " 2>/dev/null");
        return r == null ? "" : r;
    }

    public synchronized void stop() {
        close();
        ok = false;
    }

    private void close() {
        try { if (in != null) in.write("exit\n"); } catch (Exception ignored) { }
        try { if (in != null) in.flush(); } catch (Exception ignored) { }
        try { if (p != null) p.destroy(); } catch (Exception ignored) { }
        p = null; in = null; out = null;
    }
}
