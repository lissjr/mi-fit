package com.hanxing.mifit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewParent;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 高德瓦片底图 + 按配速/速度连续着色的轨迹 + 公里点。
 * 缩放用连续 zoom（小数级）+ 拉伸已缓存瓦片：双指捏合时画面连续跟随手指，
 * 不会一级一级跳，也不会每级都重新下载瓦片；松手后偏差超过半级才换更清晰的瓦片级别。
 */
public class MapView extends View {

    // 每个点：{gcjLat, gcjLng, spd(m/s), dist(m), nx, ny}  nx/ny 是墨卡托归一化坐标(0..1)
    private final List<double[]> pts = new ArrayList<double[]>();
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTile = new Paint();
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    private float zoom = 16f;        // 连续缩放级别（可带小数）
    private int drawZi = 16;         // 当前绘制用的瓦片级别（整数）
    private double cnx = 0.5, cny = 0.5;   // 视野中心（墨卡托归一化坐标）
    private float lastX, lastY;
    private boolean ready = false;
    private boolean fitted = false;
    private boolean scaling = false;

    private final LruCache<String, Bitmap> tiles = new LruCache<String, Bitmap>(128);
    private final Set<String> pending = new HashSet<String>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean bySpeed;
    private double vmin = 0, vmax = 1;
    private ScaleGestureDetector sgd;

    private static final int ZMIN = 9, ZMAX = 19;

    // 佳明 Connect 的配速色带（慢→快）：紫 / 蓝 / 青 / 绿 / 黄 / 橙 / 红
    private static final int[] RAMP = {
            0xFF6B3FD4, 0xFF2B6BE0, 0xFF149FC7, 0xFF2AA45C,
            0xFFC9B62A, 0xFFDE7526, 0xFFC22A22};

    public MapView(Context c) {
        super(c);
        setBackgroundColor(0xFFEEF1F5);
        pLine.setStrokeWidth(Ui.dp(c, 3));
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pTxt.setTextSize(Ui.dp(c, 11.2f));
        pTxt.setTypeface(Font.heavy(c));
        pTxt.setColor(Color.WHITE);
        pMark.setStyle(Paint.Style.FILL);
        pTile.setColor(0xFFE6EAF0);

        // 双指：直接按手指的实时比例改 zoom，焦点固定在两指中间
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                scaling = true;
                float f = d.getScaleFactor();
                if (f <= 0) return true;
                final float nz = (float) clampZ(zoom + Math.log(f) / Math.log(2));
                scaleAround(d.getFocusX(), d.getFocusY(), nz);
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                scaling = false;
                settle();
            }
        });
    }

    public void setTrack(List<double[]> t, boolean speedMode) {
        pts.clear();
        bySpeed = speedMode;
        if (t != null) {
            for (double[] p : t) {
                if (!(p[0] > 1 && p[1] > 1)) continue;
                double[] g = wgs2gcj(p[0], p[1]);
                double[] n = merc(g[0], g[1]);
                pts.add(new double[]{g[0], g[1], p[2], p[3], n[0], n[1]});
            }
        }
        // 用 5%~95% 分位当色带范围（和佳明一样自动适配，避免个别极值把整条染成一个色）
        double[] vs = new double[pts.size()];
        int n = 0;
        for (double[] p : pts) {
            double v = value(p[2]);
            if (v > 0) vs[n++] = v;
        }
        double lo = 0, hi = 1;
        if (n > 0) {
            java.util.Arrays.sort(vs, 0, n);
            lo = vs[(int) (n * 0.05)];
            hi = vs[Math.min(n - 1, (int) (n * 0.95))];
            if (hi <= lo) hi = lo + 1;
        }
        vmin = lo; vmax = hi;
        ready = !pts.isEmpty();
        fitted = false;
        if (ready && getWidth() > 0) fit();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (ready && !fitted) fit();
    }

    /** 按钮缩放：250ms 平滑过渡，焦点在屏幕中心 */
    public void zoomBy(final int delta) {
        final float from = zoom;
        final float to = (float) clampZ(zoom + delta);
        if (Math.abs(to - from) < 0.01f) return;
        ValueAnimator va = ValueAnimator.ofFloat(from, to);
        va.setDuration(220);
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float v = (Float) a.getAnimatedValue();
                scaling = true;
                scaleAround(getWidth() / 2f, getHeight() / 2f, v);
                if (a.getAnimatedFraction() >= 1f) {
                    scaling = false;
                    settle();
                }
            }
        });
        va.start();
    }

    public void reset() {
        if (ready) { fit(); postInvalidateOnAnimation(); }
    }

    private static double clampZ(double z) {
        return Math.max(ZMIN, Math.min(ZMAX, z));
    }

    /** zoom 变化到 nz，且屏幕上 (fx,fy) 处的地图内容保持不动 */
    private void scaleAround(float fx, float fy, float nz) {
        if (nz == zoom) return;
        double s1 = 256 * Math.pow(2, zoom);
        double ox = fx - getWidth() / 2.0, oy = fy - getHeight() / 2.0;
        double nx = cnx + ox / s1, ny = cny + oy / s1;
        zoom = nz;
        double s2 = 256 * Math.pow(2, zoom);
        cnx = nx - ox / s2;
        cny = ny - oy / s2;
        postInvalidateOnAnimation();
    }

    /** 松手/动画结束后：只有和当前瓦片级别差过半级才换级别重新拉图 */
    private void settle() {
        int want = (int) Math.round(zoom);
        want = (int) clampZ(want);
        if (Math.abs(zoom - drawZi) >= 0.55f && want != drawZi) {
            drawZi = want;
            postInvalidateOnAnimation();
        }
    }

    private double value(double spd) {
        if (spd <= 0.3) return 0;
        return bySpeed ? spd * 3.6 : 1000.0 / spd;
    }

    /** f 为 0(慢)→1(快) 的连续插值，输出佳明式渐变色 */
    private int colorOf(double v) {
        if (v <= 0) return 0xCCBFC5CE;
        double f = (vmax <= vmin) ? 0.5 : (v - vmin) / (vmax - vmin);
        if (!bySpeed) f = 1 - f;          // 配速数值越小越快
        return ramp(f);
    }

    private static int ramp(double f) {
        if (f <= 0) return RAMP[0];
        if (f >= 1) return RAMP[RAMP.length - 1];
        double x = f * (RAMP.length - 1);
        int i = (int) Math.floor(x);
        double t = x - i;
        int a = RAMP[i], b = RAMP[i + 1];
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void fit() {
        double mnLat = 90, mxLat = -90, mnLng = 180, mxLng = -180;
        for (double[] p : pts) {
            mnLat = Math.min(mnLat, p[0]); mxLat = Math.max(mxLat, p[0]);
            mnLng = Math.min(mnLng, p[1]); mxLng = Math.max(mxLng, p[1]);
        }
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        double[] a = merc(mxLat, mnLng);
        double[] b = merc(mnLat, mxLng);
        double zz = 17;
        for (zz = 17; zz >= ZMIN; zz -= 0.05) {
            double s = 256 * Math.pow(2, zz);
            double tw = (b[0] - a[0]) * s, th = (b[1] - a[1]) * s;
            if (tw <= w - 30 && th <= h - 30) break;
        }
        zoom = (float) clampZ(zz);
        drawZi = (int) clampZ(Math.round(zoom));
        cnx = (a[0] + b[0]) / 2;
        cny = (a[1] + b[1]) / 2;
        fitted = true;

    }

    /** 经纬度 → 墨卡托归一化坐标（只跟点有关，与缩放级别无关） */
    private static double[] merc(double lat, double lng) {
        double x = (lng + 180) / 360.0;
        double s = Math.sin(Math.toRadians(lat));
        double y = 0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI);
        return new double[]{x, y};
    }

    private float[] screenOf(double[] p) {
        double s = 256 * Math.pow(2, zoom);
        int w = getWidth(), h = getHeight();
        return new float[]{
                (float) ((p[4] - cnx) * s + w / 2.0),
                (float) ((p[5] - cny) * s + h / 2.0)};
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!ready) {
            pTxt.setColor(0xFFA8AFBB);
            pTxt.setTextSize(Ui.dp(getContext(), 13.4f));
            c.drawText("这一场没有 GPS 轨迹", 20, getHeight() / 2f, pTxt);
            return;
        }
        int w = getWidth(), h = getHeight();
        double s = 256 * Math.pow(2, zoom);

        double ts = 256 * Math.pow(2, zoom - drawZi);      // 每个瓦片在屏幕上的边长
        double n0x = cnx - (w / 2.0) / s, n1x = cnx + (w / 2.0) / s;
        double n0y = cny - (h / 2.0) / s, n1y = cny + (h / 2.0) / s;
        int scaleN = 1 << drawZi;

        int tx0 = (int) Math.floor(n0x * scaleN), tx1 = (int) Math.floor(n1x * scaleN);
        int ty0 = (int) Math.floor(n0y * scaleN), ty1 = (int) Math.floor(n1y * scaleN);
        int budget = 10;
        for (int tx = tx0; tx <= tx1; tx++) {
            for (int ty = ty0; ty <= ty1; ty++) {
                if (ty < 0 || ty >= scaleN || tx < 0 || tx >= scaleN) continue;
                String key = drawZi + "/" + tx + "/" + ty;
                float x = (float) ((tx / (double) scaleN - cnx) * s + w / 2.0);
                float y = (float) ((ty / (double) scaleN - cny) * s + h / 2.0);
                Bitmap bm = tiles.get(key);
                if (bm != null) {
                    dst.set(x, y, x + (float) ts + 0.5f, y + (float) ts + 0.5f);
                    c.drawBitmap(bm, null, dst, pBmp);
                } else {
                    c.drawRect(x, y, x + (float) ts, y + (float) ts, pTile);
                    // 缩放中先拉当前级别，保证松手立刻有图
                    if (budget > 0 && !pending.contains(key)) { fetch(key, drawZi, tx, ty); budget--; }
                }
            }
        }
        if (budget > 0 && !scaling) {
            // 顺手预热相邻级别，后续微调缩放时不必再等下载
            int near = drawZi + (zoom > drawZi ? 1 : -1);
            if (near <= ZMAX && near >= ZMIN) {
                int n2 = 1 << near;
                int ax0 = (int) Math.floor(n0x * n2), ax1 = (int) Math.floor(n1x * n2);
                int ay0 = (int) Math.floor(n0y * n2), ay1 = (int) Math.floor(n1y * n2);
                int cnt = 0;
                for (int tx = ax0; tx <= ax1 && cnt < 4; tx++) {
                    for (int ty = ay0; ty <= ay1 && cnt < 4; ty++) {
                        if (ty < 0 || ty >= n2 || tx < 0 || tx >= n2) continue;
                        String key = near + "/" + tx + "/" + ty;
                        if (tiles.get(key) == null && !pending.contains(key)) {
                            fetch(key, near, tx, ty);
                            cnt++;
                        }
                    }
                }
            }
        }

        // 轨迹：按屏幕像素抽稀，避免几千段逐帧重复绘制
        float[] prev = null;
        double[] prevP = null;
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            float[] sp = screenOf(p);
            if (prev != null) {
                pLine.setColor(colorOf(value(prevP[2])));
                c.drawLine(prev[0], prev[1], sp[0], sp[1], pLine);
            }
            prev = sp;
            prevP = p;
        }

        // 公里点 + 起终点
        int lastKm = 0;
        for (int i = 0; i < pts.size(); i++) {
            int km = (int) Math.floor(pts.get(i)[3] / 1000.0);
            if (km < 1 || km <= lastKm) continue;
            lastKm = km;
            float[] sp = screenOf(pts.get(i));
            pMark.setColor(0xFFFFFFFF);
            c.drawCircle(sp[0], sp[1], Ui.dp(getContext(), 7), pMark);
            pTxt.setColor(0xFF20242B);
            String t = km + "";
            float tw = pTxt.measureText(t);
            c.drawText(t, sp[0] - tw / 2, sp[1] + Ui.dp(getContext(), 4), pTxt);
        }
        if (pts.size() > 1) {
            double[] a = pts.get(0), b = pts.get(pts.size() - 1);
            float[] s0 = screenOf(a), s1 = screenOf(b);
            pMark.setColor(0xFF2ECC71);
            c.drawCircle(s0[0], s0[1], Ui.dp(getContext(), 6), pMark);
            pMark.setColor(0xFFE74C3C);
            c.drawCircle(s1[0], s1[1], Ui.dp(getContext(), 6), pMark);
            pTxt.setColor(Color.WHITE);
            c.drawText("起", s0[0] + Ui.dp(getContext(), 8), s0[1] + Ui.dp(getContext(), 4), pTxt);
            c.drawText("终", s1[0] + Ui.dp(getContext(), 8), s1[1] + Ui.dp(getContext(), 4), pTxt);
        }
    }

    private void fetch(final String key, final int zz, final int tx, final int ty) {
        pending.add(key);
        POOL.execute(new Runnable() {
            public void run() {
                Bitmap bm = null;
                try {
                    int sub = (Math.abs(tx + ty) % 4) + 1;
                    String u = "https://webrd0" + sub + ".is.autonavi.com/appmaptile?lang=zh_cn"
                            + "&size=1&scale=1&style=8&x=" + tx + "&y=" + ty + "&z=" + zz;
                    HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(6000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                    InputStream in = conn.getInputStream();
                    bm = BitmapFactory.decodeStream(in);
                    in.close();
                    conn.disconnect();
                } catch (Exception ignored) {
                }
                final Bitmap fb = bm;
                ui.post(new Runnable() {
                    public void run() {
                        pending.remove(key);
                        if (fb != null) {
                            tiles.put(key, fb);
                            postInvalidateOnAnimation();
                        }
                    }
                });
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (sgd != null && e.getPointerCount() > 1) {
            sgd.onTouchEvent(e);
            return true;                      // 双指期间交给缩放，不做平移
        }
        if (sgd != null && sgd.isInProgress()) {
            sgd.onTouchEvent(e);
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX();
                lastY = e.getY();
                ViewParent p = getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);   // 别让外层 ScrollView 抢手势
                return true;
            case MotionEvent.ACTION_MOVE:
                double s = 256 * Math.pow(2, zoom);
                cnx -= (e.getX() - lastX) / s;
                cny -= (e.getY() - lastY) / s;
                lastX = e.getX();
                lastY = e.getY();
                postInvalidateOnAnimation();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                ViewParent pp = getParent();
                if (pp != null) pp.requestDisallowInterceptTouchEvent(false);
                settle();
                return true;
            default:
                return true;
        }
    }

    // ---------- 坐标转换 ----------
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private static boolean outOfChina(double lat, double lng) {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double tfLat(double x, double y) {
        double r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        r += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        r += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        return r;
    }

    private static double tfLng(double x, double y) {
        double r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        r += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        r += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
        return r;
    }

    public static double[] wgs2gcj(double lat, double lng) {
        if (outOfChina(lat, lng)) return new double[]{lat, lng};
        double dLat = tfLat(lng - 105.0, lat - 35.0);
        double dLng = tfLng(lng - 105.0, lat - 35.0);
        double rad = Math.toRadians(lat);
        double magic = 1 - EE * Math.sin(rad) * Math.sin(rad);
        double sq = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sq) * Math.PI);
        dLng = (dLng * 180.0) / (A / sq * Math.cos(rad) * Math.PI);
        return new double[]{lat + dLat, lng + dLng};
    }
}
