package com.hanxing.mifit;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 液态玻璃材质（参考 iOS 26 Liquid Glass 的观感，用纯 Canvas 画出来）：
 * 半透明渐变底 + 顶部镜面高光带 + 底部内阴影 + 渐变描边，
 * 边缘上亮下暗，形成"有厚度、会反光"的玻璃片。
 */
public class Glass extends Drawable {

    private final float radius;
    private final int topColor, bottomColor;
    private final int edgeTop, edgeBottom;

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGloss = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path edgePath = new Path();
    private final RectF rect = new RectF();
    private int w, h;

    private Glass(float radiusPx, int topColor, int bottomColor, int edgeTop, int edgeBottom) {
        this.radius = radiusPx;
        this.topColor = topColor;
        this.bottomColor = bottomColor;
        this.edgeTop = edgeTop;
        this.edgeBottom = edgeBottom;
        pFill.setStyle(Paint.Style.FILL);
        pGloss.setStyle(Paint.Style.FILL);
        pEdge.setStyle(Paint.Style.STROKE);
        pEdge.setStrokeWidth(1.6f);
    }

    /** 玻璃卡片（圆角） */
    public static Glass card(float radiusPx, boolean highlight) {
        return new Glass(radiusPx,
                highlight ? 0xFDFFFFFF : 0xFCFEFFFF,
                highlight ? 0xF0FFFFFF : 0xF0F7FDFF,
                highlight ? 0xFFFF9A4D : 0xF2FFFFFF,
                highlight ? 0x66FF6A1A : 0x30000000);
    }

    /** 顶栏 / 底栏面板（方角） */
    public static Glass bar(boolean top) {
        return top ? new Glass(0f, 0xF2FFFFFF, 0xE6FFFFFF, 0x99FFFFFF, 0x1A000000)
                : new Glass(0f, 0xE8FFFFFF, 0xF4FFFFFF, 0x1A000000, 0x99FFFFFF);
    }

    /** 圆形玻璃钮 */
    public static Glass circle(boolean on) {
        return on
                ? new Glass(9999f, 0xFFFFB066, 0xFFF05A18, 0xCCFFFFFF, 0x40FF7A1F)
                : new Glass(9999f, 0xFAFFFFFF, 0xDEFFFFFF, 0x99FFFFFF, 0x22000000);
    }

    /** 悬浮胶囊（底栏）：圆角、更透，四周一圈冷色反光边，配合投影浮在内容之上 */
    public static Glass pill(float radiusPx) {
        return new Glass(radiusPx, 0xFBFFFFFF, 0xEFFFFFFF, 0xF2FFFFFF, 0x33000000);
    }

    @Override
    protected void onBoundsChange(android.graphics.Rect b) {
        w = b.width();
        h = b.height();
        rect.set(1, 1, w - 1, h - 1);
        path.reset();
        float r = Math.min(radius, Math.min(w, h) / 2f);
        path.addRoundRect(rect, r, r, Path.Direction.CW);

        // 描边路径内缩半个线宽：线条整条落在圆角里，外边不会被切出一道灰边
        RectF er = new RectF(rect);
        er.inset(0.8f, 0.8f);
        edgePath.reset();
        if (er.width() > 0 && er.height() > 0) {
            float er2 = Math.max(0, r - 0.8f);
            edgePath.addRoundRect(er, er2, er2, Path.Direction.CW);
        }

        pFill.setShader(new LinearGradient(0, 0, w * 0.35f, h,
                new int[]{topColor, mix(topColor, bottomColor), bottomColor},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        // 顶部反光：从顶边往下快速淡出，像光打在玻璃上
        pGloss.setShader(new LinearGradient(0, 0, 0, h * 0.42f,
                new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));

        pEdge.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{edgeTop, edgeBottom}, null, Shader.TileMode.CLAMP));
    }

    /** 两个色值按通道取中，避免直接相加溢出成怪色 */
    private static int mix(int a, int b) {
        int aa = (((a >>> 24) & 0xFF) + ((b >>> 24) & 0xFF)) / 2;
        int rr = (((a >> 16) & 0xFF) + ((b >> 16) & 0xFF)) / 2;
        int gg = (((a >> 8) & 0xFF) + ((b >> 8) & 0xFF)) / 2;
        int bb = ((a & 0xFF) + (b & 0xFF)) / 2;
        return (aa << 24) | (rr << 16) | (gg << 8) | bb;
    }

    @Override
    public void draw(Canvas c) {
        c.save();
        c.clipPath(path);
        c.drawPath(path, pFill);
        c.drawRect(rect, pGloss);                 // 顶部高光
        c.restore();
        c.drawPath(edgePath, pEdge);              // 渐变描边
    }

    @Override
    public void getOutline(Outline o) {
        if (w <= 0 || h <= 0) return;
        float r = Math.min(radius, Math.min(w, h) / 2f);
        if (r > 0) o.setRoundRect(0, 0, w, h, r);
        else o.setRect(0, 0, w, h);
        o.setAlpha(0.55f);
    }

    @Override
    public void setAlpha(int alpha) { }

    @Override
    public void setColorFilter(ColorFilter cf) { }

    @Override
    public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
