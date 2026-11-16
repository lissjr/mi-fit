package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 下拉刷新的圆形箭头：白色圆底 + 橙色圆环 + 箭头，可整体旋转 */
public class RefreshDot extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private float angle = 0;

    public RefreshDot(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        bg.setColor(0xFFFFFFFF);
        bg.setShadowLayer(Ui.dp(c, 3), 0, Ui.dp(c, 1), 0x33000000);
        ring.setColor(0xFFF07A2F);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2.6f));
        ring.setStrokeCap(Paint.Cap.ROUND);
        head.setColor(0xFFF07A2F);
    }

    public void setAngle(float a) {
        angle = a;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy);
        float pad = Ui.dp(getContext(), 5);
        c.save();
        c.rotate(angle, cx, cy);
        c.drawCircle(cx, cy, r - Ui.dp(getContext(), 1.5f), bg);
        RectF rf = new RectF(cx - r + pad, cy - r + pad, cx + r - pad, cy + r - pad);
        // 圆环留一个缺口放箭头
        c.drawArc(rf, -100, 250, false, ring);
        // 箭头：指在圆环端点
        float rr = r - pad;
        float ax = cx + (float) Math.cos(Math.toRadians(-100)) * rr;
        float ay = cy + (float) Math.sin(Math.toRadians(-100)) * rr;
        float s = Ui.dp(getContext(), 4.2f);
        tri.reset();
        tri.moveTo(ax + s, ay - s * 1.2f);
        tri.lineTo(ax - s, ay);
        tri.lineTo(ax + s, ay + s * 1.2f);
        tri.close();
        c.drawPath(tri, head);
        c.restore();
    }
}
