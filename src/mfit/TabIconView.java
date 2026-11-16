package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 底栏图标：心形 / 跑者 / 人像，纯 Canvas 自绘，随选中态换色 */
public class TabIconView extends View {

    public static final int HEALTH = 0, SPORT = 1, ME = 2;

    private final int kind;
    private boolean active;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();

    public TabIconView(Context c, int kind) {
        super(c);
        this.kind = kind;
    }

    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h);
        float cx = w / 2f, cy = h / 2f;
        p.reset();
        p.setAntiAlias(true);
        p.setColor(active ? 0xFFFF7A2F : 0xFF96A2B4);
        p.setStrokeWidth(dp(1.9f));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);

        if (kind == HEALTH) {
            path.reset();
            float top = cy - s * 0.30f, bottom = cy + s * 0.32f;
            path.moveTo(cx, bottom);
            path.cubicTo(cx - s * 0.62f, cy + s * 0.02f, cx - s * 0.34f, top - s * 0.40f, cx, cy - s * 0.12f);
            path.cubicTo(cx + s * 0.34f, top - s * 0.40f, cx + s * 0.62f, cy + s * 0.02f, cx, bottom);
            cv.drawPath(path, p);
        } else if (kind == SPORT) {
            // 跑者：头 + 躯干 + 前后摆动的手脚
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(cx + s * 0.16f, cy - s * 0.30f, s * 0.11f, p);
            p.setStyle(Paint.Style.STROKE);
            // 躯干：肩到髋
            cv.drawLine(cx + s * 0.10f, cy - s * 0.14f, cx - s * 0.06f, cy + s * 0.10f, p);
            // 前腿、后腿
            cv.drawLine(cx - s * 0.06f, cy + s * 0.10f, cx + s * 0.14f, cy + s * 0.26f, p);
            cv.drawLine(cx + s * 0.14f, cy + s * 0.26f, cx + s * 0.28f, cy + s * 0.36f, p);
            cv.drawLine(cx - s * 0.06f, cy + s * 0.10f, cx - s * 0.26f, cy + s * 0.22f, p);
            cv.drawLine(cx - s * 0.26f, cy + s * 0.22f, cx - s * 0.34f, cy + s * 0.38f, p);
            // 手臂
            cv.drawLine(cx + s * 0.10f, cy - s * 0.12f, cx + s * 0.30f, cy - s * 0.02f, p);
            cv.drawLine(cx + s * 0.10f, cy - s * 0.12f, cx - s * 0.14f, cy - s * 0.16f, p);
        } else {
            // 人像：头 + 肩弧
            p.setStyle(Paint.Style.STROKE);
            cv.drawCircle(cx, cy - s * 0.20f, s * 0.15f, p);
            oval.set(cx - s * 0.30f, cy + s * 0.02f, cx + s * 0.30f, cy + s * 0.62f);
            cv.drawArc(oval, 180, 180, false, p);
        }
    }
}
