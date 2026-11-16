package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

/**
 * 真·液态玻璃容器（仿 iOS 26 Liquid Glass）：
 *
 * 1. 每帧（限 30fps）把 rootView 里位于自己身后的内容抓成半分辨率位图；
 * 2. GPU / 自写 box blur 糊掉，得到真正的"透过玻璃看到背景"而不是半透明白块；
 * 3. 叠饱和度、白色染色、顶部镜面高光、斜向 sheen、Fresnel 边缘亮环、内侧反光；
 * 4. 自己的 children（图标、文字）最后清晰画在玻璃之上。
 *
 * 参考实现思路来自 Android 侧的 Liquid Glass 复刻（backdrop capture + 哈希去抖 + RenderEffect blur）。
 */
public class LiquidGlass extends FrameLayout {

    private static final float CAPTURE_SCALE = 0.30f;   // 抓取分辨率，够糊就行
    private static final int CAPTURE_MIN_MS = 12;       // 抓取节流（滚动中也要跟着更新）
    private static volatile long freezeUntil = 0;       // 列表滚动期间先别抓，避免掉帧

    /** 内容在滚动时暂停抓取，滚动停下后再补一帧 */
    public static void freeze(long ms) {
        freezeUntil = SystemClock.uptimeMillis() + ms;
    }

    private final Paint shotPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rf = new RectF();
    private final int[] loc = new int[2];

    private float radiusPx = 0;
    private float blurDp = 16f;
    private int tintTop = 0x9AFFFFFF;
    private int tintBottom = 0x82F3F8FF;
    private int avgColor = 0xFFF4F8FD;

    private Bitmap shot;
    private Canvas shotCanvas;
    private Bitmap mat;            // 画好的玻璃材质缓存
    private Canvas matCanvas;
    private boolean matDirty = true;
    private long shownHash = -1;
    private long lastCapture = 0;
    private boolean capturing = false;
    private boolean ready = false;
    private boolean resumePosted = false;

    // ---- API 33+ 用 AGSL 做真正的边缘折射（参考 Kyant0/AndroidLiquidGlass 的做法）----
    private static final String AGSL =
            "uniform shader content;\n" +
            "uniform float2 sz;\n" +
            "uniform float radius;\n" +
            "uniform float thick;\n" +
            "uniform float strength;\n" +
            "uniform float disp;\n" +
            "float sdRound(float2 p, float2 b, float r) {\n" +
            "    float2 q = abs(p) - b + r;\n" +
            "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "    float2 c = sz * 0.5;\n" +
            "    float2 lp = p - c;\n" +
            "    float d = sdRound(lp, c, radius);\n" +
            "    float dx = sdRound(lp + float2(0.7, 0.0), c, radius) - d;\n" +
            "    float dy = sdRound(lp + float2(0.0, 0.7), c, radius) - d;\n" +
            "    float2 n = normalize(float2(dx, dy) + float2(1e-6, 1e-6));\n" +
            "    float rim = clamp(1.0 + d / thick, 0.0, 1.0);\n" +
            "    float bend = rim * rim * strength;\n" +
            "    float2 uv = p + n * bend;\n" +
            "    half4 col = content.eval(uv);\n" +
            "    col.r = content.eval(uv + n * bend * disp).r;\n" +
            "    col.b = content.eval(uv - n * bend * disp).b;\n" +
            "    return col;\n" +
            "}\n";

    private android.graphics.RenderNode node;
    private android.graphics.RuntimeShader shader;
    private boolean agslOk = false;

    private final ViewTreeObserver.OnPreDrawListener pre = new ViewTreeObserver.OnPreDrawListener() {
        public boolean onPreDraw() {
            tickCapture();
            return true;
        }
    };

    public LiquidGlass(Context c) {
        super(c);
        setWillNotDraw(false);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(1.32f);
        shotPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        rimPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStyle(Paint.Style.STROKE);
        // 阴影轮廓必须是圆角，否则 elevation 会画成方形灰影，圆角外像没裁干净
        setOutlineProvider(new android.view.ViewOutlineProvider() {
            public void getOutline(View v, android.graphics.Outline o) {
                int w = v.getWidth(), h = v.getHeight();
                if (w <= 0 || h <= 0) {
                    o.setEmpty();
                    return;
                }
                float r = radiusPx > 0 ? Math.min(radiusPx, Math.min(w, h) / 2f) : 0;
                if (r > 0) o.setRoundRect(0, 0, w, h, r);
                else o.setRect(0, 0, w, h);
                o.setAlpha(0.92f);
            }
        });
    }

    /** 圆角半径（dp），胶囊传高度一半 */
    public void setRadiusDp(float dp) {
        radiusPx = Ui.dp(getContext(), dp);
        matsReady = false;
        matDirty = true;
        invalidateOutline();
        invalidate();
    }

    /** 模糊强度（dp） */
    public void setBlurDp(float dp) {
        blurDp = dp;
    }

    /** 玻璃染色：上亮下略透 */
    public void setTint(int top, int bottom) {
        tintTop = top;
        tintBottom = bottom;
        matsReady = false;
        matDirty = true;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                shader = new android.graphics.RuntimeShader(AGSL);
                node = new android.graphics.RenderNode("liquid-glass");
                node.setRenderEffect(android.graphics.RenderEffect
                        .createRuntimeShaderEffect(shader, "content"));
                agslOk = true;
                android.util.Log.i("mfit", "LiquidGlass AGSL ready");
            } catch (Throwable t) {
                android.util.Log.i("mfit", "AGSL fail: " + t);
                agslOk = false;
            }
        }
        getViewTreeObserver().addOnPreDrawListener(pre);
    }

    @Override
    protected void onDetachedFromWindow() {
        try { getViewTreeObserver().removeOnPreDrawListener(pre); } catch (Exception e) { }
        super.onDetachedFromWindow();
    }

    @Override
    public void draw(Canvas canvas) {
        if (capturing) return;          // 抓背景时跳过自己，否则会把自己画进自己的背景
        super.draw(canvas);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawGlass(canvas);
        super.dispatchDraw(canvas);
    }

    // ---------------- 背景抓取 ----------------

    private void tickCapture() {
        if (!isShown() || getWidth() <= 0 || getHeight() <= 0) return;
        long now = SystemClock.uptimeMillis();
        if (now < freezeUntil) {
            // 滚动中冻结：只排一次补帧，不要在每帧 preDraw 里重复投递
            if (!resumePosted) {
                resumePosted = true;
                postDelayed(new Runnable() {
                    public void run() {
                        resumePosted = false;
                        invalidate();
                    }
                }, Math.max(120, freezeUntil - now + 40));
            }
            return;
        }
        if (now - lastCapture < CAPTURE_MIN_MS) return;
        lastCapture = now;
        capture();
    }

    private void capture() {
        View root = getRootView();
        if (root == null) return;
        getLocationInWindow(loc);
        int bw = Math.max(1, (int) (getWidth() * CAPTURE_SCALE));
        int bh = Math.max(1, (int) (getHeight() * CAPTURE_SCALE));
        if (shot == null || shot.getWidth() != bw || shot.getHeight() != bh) {
            shot = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            shotCanvas = new Canvas(shot);
        }
        shot.eraseColor(0);
        shotCanvas.save();
        shotCanvas.scale(CAPTURE_SCALE, CAPTURE_SCALE);
        shotCanvas.translate(-loc[0], -loc[1]);
        // 只光栅化玻璃周围一圈，整页重绘太贵
        float pad = Ui.dp(getContext(), 28);
        shotCanvas.clipRect(loc[0] - pad, loc[1] - pad,
                loc[0] + getWidth() + pad, loc[1] + getHeight() + pad);
        capturing = true;
        try {
            root.draw(shotCanvas);
        } catch (Throwable ignored) {
        } finally {
            capturing = false;
        }
        shotCanvas.restore();

        if (Build.VERSION.SDK_INT >= 31) Blur31.blur(shot, blurDp * getResources().getDisplayMetrics().density);
        else BoxBlur.blur(shot, 3);

        long h = hash(shot);
        if (h != shownHash) {
            shownHash = h;
            ready = true;
            absorb(shot);
            matDirty = true;
            recordNode();
            postInvalidateOnAnimation();
        }
    }

    /** 把模糊后的背景喂给 RenderNode，交给 AGSL 在边缘做折射 */
    private void recordNode() {
        if (!agslOk || node == null || shot == null) return;
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // node 只渲半分辨率，绘制时放大 2 倍：背景本来就是糊的，但 GPU 采样量降到 1/4
        int hw = Math.max(1, w / 2), hh = Math.max(1, h / 2);
        try {
            node.setPosition(0, 0, hw, hh);
            Canvas rec = node.beginRecording(hw, hh);
            rec.drawBitmap(shot, null, new RectF(0, 0, hw, hh), shotPaint);
            node.endRecording();
        } catch (Throwable ignored) { }
    }

    /** 吸一点背景平均色，玻璃才不像一块死白的板 */
    private void absorb(Bitmap b) {
        int w = b.getWidth(), ht = b.getHeight();
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int y = 0; y < ht; y += 14) {
            for (int x = 0; x < w; x += 14) {
                int p = b.getPixel(x, y);
                if ((p >>> 24) == 0) continue;      // clip 之外没抓到的地方跳过
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                bl += p & 0xFF;
                n++;
            }
        }
        if (n > 0) avgColor = Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** 6×5 采样哈希：背景没变就不重画 */
    private long hash(Bitmap b) {
        if (b == null) return -1;
        long h = 0x9E3779B97F4A7C15L;
        int w = b.getWidth(), ht = b.getHeight();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 6; x++) {
                int px = b.getPixel(Math.min(w - 1, (int) ((x + 0.5f) * w / 6f)),
                        Math.min(ht - 1, (int) ((y + 0.5f) * ht / 5f)));
                h = h * 31 + px;
            }
        }
        return h;
    }

    // ---------------- 材质绘制 ----------------

    // 尺寸/圆角不变时，这些路径与渐变都缓存，避免每帧重算
    private final Path ringPath = new Path();
    private final RectF fro = new RectF();     // 内圈 Fresnel
    private final RectF halo = new RectF();    // 外缘 halo
    private Shader shTint, shGloss, shSheen, shThick, shSpot, shInner, shRim;
    private boolean matsReady = false;

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        matsReady = false;
        matDirty = true;
        invalidateOutline();
    }

    private void buildMats(int w, int h, float r) {
        rf.set(0, 0, w, h);
        clip.reset();
        if (r > 0) clip.addRoundRect(rf, r, r, Path.Direction.CW);
        else clip.addRect(rf, Path.Direction.CW);

        shTint = new LinearGradient(0, 0, 0, h, new int[]{tintTop, tintBottom},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        shGloss = new LinearGradient(0, 0, w * 0.55f, h * 0.85f,
                new int[]{0x7AFFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP);
        shSheen = new LinearGradient(0, h * 0.05f, w, h * 0.55f,
                new int[]{0x00FFFFFF, 0x26FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        shThick = new LinearGradient(0, h * 0.62f, 0, h,
                new int[]{0x00FFFFFF, 0x0A0B1E3A},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        shSpot = new RadialGradient(w * 0.30f, -h * 0.06f, Math.max(w * 0.55f, h * 0.5f),
                new int[]{0x4DFFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        shInner = new LinearGradient(0, 0, 0, h,
                new int[]{0x66FFFFFF, 0x22FFFFFF, 0x66FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        shRim = new LinearGradient(0, 0, 0, h,
                new int[]{0xFFFFFFFF, 0x99FFFFFF, 0x33FFFFFF},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);

        float inset = Ui.dp(getContext(), 1.6f);
        fro.set(rf);
        fro.inset(inset, inset);
        float hInset = Ui.dp(getContext(), 0.7f);
        halo.set(rf);
        halo.inset(-hInset, -hInset);

        ringPath.reset();
        float ringInset = Ui.dp(getContext(), 6f);
        if (w > ringInset * 2 && h > ringInset * 2) {
            Path outerP = new Path();
            outerP.addRoundRect(rf, r, r, Path.Direction.CW);
            RectF inRf = new RectF(rf);
            inRf.inset(ringInset, ringInset);
            Path inP = new Path();
            inP.addRoundRect(inRf, Math.max(0, r - ringInset), Math.max(0, r - ringInset),
                    Path.Direction.CW);
            outerP.op(inP, Path.Op.DIFFERENCE);
            ringPath.addPath(outerP);
        }
        matsReady = true;
    }

    private void drawGlass(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // 材质只在背景变化时重画一次，其余帧直接贴缓存图，滚动才不掉帧
        if (mat == null || mat.getWidth() != w || mat.getHeight() != h) {
            mat = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            matCanvas = new Canvas(mat);
            matsReady = false;
            matDirty = true;
        }
        if (matDirty) {
            renderMat(matCanvas);
            matDirty = false;
        }
        // API 33+：背景交给 AGSL 做边缘折射，材质（染色/高光/边）再叠在上面
        if (agslOk && shader != null && node != null && shot != null && ready) {
            float r = radiusPx > 0 ? Math.min(radiusPx, Math.min(w, h) / 2f) : 0;
            int hw = Math.max(1, w / 2), hh = Math.max(1, h / 2);
            shader.setFloatUniform("sz", (float) hw, (float) hh);
            shader.setFloatUniform("radius", r / 2f);
            shader.setFloatUniform("thick", Ui.dp(getContext(), 13) / 2f);
            shader.setFloatUniform("strength", Ui.dp(getContext(), 4.4f) / 2f);
            shader.setFloatUniform("disp", 0.07f);
            canvas.save();
            Path cp = new Path();
            if (r > 0) cp.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
            else cp.addRect(0, 0, w, h, Path.Direction.CW);
            canvas.clipPath(cp);
            canvas.scale(w / (float) hw, h / (float) hh);
            canvas.drawRenderNode(node);
            canvas.restore();
        }
        canvas.drawBitmap(mat, 0, 0, null);
    }

    private void renderMat(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        mat.eraseColor(0);
        float r = radiusPx > 0 ? Math.min(radiusPx, Math.min(w, h) / 2f) : 0;
        if (!matsReady) buildMats(w, h, r);

        canvas.save();
        canvas.clipPath(clip);

        // 1) 透过来的模糊背景（AGSL 路径下背景由 shader 提供，这里不再画）
        boolean agsl = agslOk && node != null;
        if (!agsl) {
            if (shot != null && ready) {
                canvas.drawBitmap(shot, null, rf, shotPaint);
            } else {
                canvas.drawColor(0xFFF3F6FA);
            }

            // 1.5) 边缘透镜折射：背景放大一点后只铺在边缘环里，做出边缘扭曲感
            if (shot != null && ready && !ringPath.isEmpty()) {
                Path tmp = ringPath;   // 复用缓存的环，不再每帧 Path.op
                canvas.save();
                canvas.clipPath(tmp);
                Matrix m = new Matrix();
                m.setScale((w / (float) shot.getWidth()) * 1.30f,
                        (h / (float) shot.getHeight()) * 1.30f, w / 2f, h / 2f);
                canvas.drawBitmap(shot, m, shotPaint);
                canvas.restore();
            }
        }

        // 2) 玻璃染色：先吸一层背景平均色，再上白，玻璃才是"有色的透"而不是白板
        avgPaint.setColor(avgColor);
        avgPaint.setAlpha(0x22);
        canvas.drawRect(rf, avgPaint);
        tintPaint.setShader(shTint);
        canvas.drawRect(rf, tintPaint);

        // 3) 斜向镜面高光 + 4) sheen + 4.5) 底缘厚度 + 4.6) 角上那道光
        glossPaint.setShader(shGloss);
        canvas.drawRect(rf, glossPaint);
        glossPaint.setShader(shSheen);
        canvas.drawRect(rf, glossPaint);
        glossPaint.setShader(shThick);
        canvas.drawRect(rf, glossPaint);
        glossPaint.setShader(shSpot);
        canvas.drawRect(rf, glossPaint);

        // 5) 内侧 Fresnel 亮线
        innerPaint.setStrokeWidth(Ui.dp(getContext(), 1.2f));
        innerPaint.setShader(shInner);
        if (r > 0) {
            float ir = Math.max(0, r - Ui.dp(getContext(), 1.6f));
            canvas.drawRoundRect(fro, ir, ir, innerPaint);
        } else {
            canvas.drawRect(fro, innerPaint);
        }

        canvas.restore();

        // 6) 外缘：上亮下暗。描边内缩半个线宽，否则线条有一半落在圆角外侧，
        //    被父容器裁掉后会留下一圈灰边（看起来像没裁干净）
        float sw = Ui.dp(getContext(), 1.3f);
        rimPaint.setStrokeWidth(sw);
        rimPaint.setShader(shRim);
        RectF rr = new RectF(rf);
        rr.inset(sw / 2f, sw / 2f);
        if (r > 0) {
            float ir = Math.max(0, r - sw / 2f);
            canvas.drawRoundRect(rr, ir, ir, rimPaint);
        } else {
            canvas.drawRect(rr, rimPaint);
        }
    }

    // ---------------- 模糊 ----------------

    /** API 31+ 用 RenderEffect 在 GPU 上糊 */
    static class Blur31 {
        static void blur(Bitmap b, float radiusPx) {
            if (radiusPx <= 0) return;
            try {
                android.graphics.RenderNode node = new android.graphics.RenderNode("glass-bg");
                int w = b.getWidth(), h = b.getHeight();
                node.setPosition(0, 0, w, h);
                android.graphics.RecordingCanvas rc = node.beginRecording(w, h);
                rc.drawBitmap(b, 0, 0, null);
                node.endRecording();
                node.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        radiusPx, radiusPx, Shader.TileMode.CLAMP));
                android.graphics.Canvas c = new android.graphics.Canvas(b);
                c.drawRenderNode(node);
                node.discardDisplayList();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 低版本自己糊：三趟 box blur，半分辨率图够快 */
    static class BoxBlur {
        static void blur(Bitmap b, int passes) {
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            int[] tmp = new int[w * h];
            for (int p = 0; p < passes; p++) {
                blurRow(px, tmp, w, h);
                blurCol(tmp, px, w, h);
            }
            b.setPixels(px, 0, w, 0, 0, w, h);
        }

        private static void blurRow(int[] in, int[] out, int w, int h) {
            for (int y = 0; y < h; y++) {
                int base = y * w;
                for (int x = 0; x < w; x++) {
                    int r = 0, g = 0, bl = 0, n = 0;
                    for (int k = -2; k <= 2; k++) {
                        int i = x + k;
                        if (i < 0 || i >= w) continue;
                        int c = in[base + i];
                        r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; bl += c & 0xFF; n++;
                    }
                    out[base + x] = Color.argb(0xFF, r / n, g / n, bl / n);
                }
            }
        }

        private static void blurCol(int[] in, int[] out, int w, int h) {
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    int r = 0, g = 0, bl = 0, n = 0;
                    for (int k = -2; k <= 2; k++) {
                        int i = y + k;
                        if (i < 0 || i >= h) continue;
                        int c = in[i * w + x];
                        r += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; bl += c & 0xFF; n++;
                    }
                    out[y * w + x] = Color.argb(0xFF, r / n, g / n, bl / n);
                }
            }
        }
    }
}
