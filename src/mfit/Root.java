package com.hanxing.mifit;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** 用 su 执行一条 shell 命令并取回输出 */
public class Root {
    public static class Res {
        public int code;
        public String out;
        public Res(int c, String o) { code = c; out = o; }
        public boolean ok() { return code == 0; }
    }

    public static Res run(String cmd) {
        return run(cmd, 60);
    }

    public static Res run(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] b = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (true) {
                if (in.available() > 0) {
                    int n = in.read(b);
                    if (n > 0) buf.write(b, 0, n);
                } else {
                    if (System.currentTimeMillis() > deadline) break;
                    try { Thread.sleep(30); } catch (InterruptedException e) { break; }
                    try { if (!p.isAlive() && in.available() == 0) break; } catch (Exception ignored) { }
                }
            }
            int code = 0;
            try { code = p.waitFor(); } catch (Exception ignored) { }
            return new Res(code, new String(buf.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            return new Res(-1, "su 不可用：" + e.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
    }

    /** 后台常驻进程（httpd），返回 Process 以便持有引用 */
    public static Process spawn(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            return pb.start();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isRooted() {
        Res r = run("id");
        return r.out != null && r.out.contains("uid=0");
    }
}
