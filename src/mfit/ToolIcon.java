package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** 悬浮操作条上的图标按钮：上面图标，下面一行小字 */
public class ToolIcon extends View {

    public static final int SELECT = 0;
    public static final int UPLOAD = 1;
    public static final int EXPORT = 2;

    private final int type;
    private final String label;
    private boolean active = false;
    private boolean pressed = false;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF box = new RectF();

    public ToolIcon(Context c, int type, String label) {
        super(c);
        this.type = type;
        this.label = label;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        fill.setStyle(Paint.Style.FILL);
        textPaint.setTextSize(Ui.dp(c, 14.5f) / 1.35f);
        textPaint.setTypeface(Font.heavy(c));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(0xFF9AA4B2);
    }

    public void setActive(boolean on) {
        if (active == on) return;
        active = on;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            pressed = true;
            setAlpha(0.62f);
            invalidate();
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            pressed = false;
            setAlpha(1f);
            invalidate();
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float ctx = getResources().getDisplayMetrics().density;
        float labelZone = 15 * ctx;          // 底部文字占位
        float iconBox = Math.min(w, h - labelZone);
        float s = Math.min(Ui.dp(getContext(), 21), iconBox * 0.80f);
        float cx = w / 2f, cy = labelZone / 2f + iconBox / 2f;

        if (active) {
            fill.setColor(Ui.PRIMARY);
            c.drawCircle(cx, cy, iconBox * 0.42f, fill);
        }

        int ink = active ? 0xFFFFFFFF : 0xFF39424F;
        float sw = Ui.dp(getContext(), 1.7f);

        if (type == SELECT) {
            // 勾选框：方框 + 勾，已全选时方框实心白勾
            float b = s / 2f;
            box.set(cx - b, cy - b, cx + b, cy + b);
            float rr = Ui.dp(getContext(), 3f);
            if (active) {
                fill.setColor(0xFFFFFFFF);
                fill.setAlpha(90);
                c.drawRoundRect(box, rr, rr, fill);
                stroke.setColor(0xFFFFFFFF);
            } else {
                stroke.setColor(ink);
            }
            stroke.setStrokeWidth(sw);
            c.drawRoundRect(box, rr, rr, stroke);

            stroke.setColor(active ? 0xFFFFFFFF : 0xFFFA6A1E);
            stroke.setStrokeWidth(sw * 1.15f);
            path.reset();
            path.moveTo(cx - b * 0.62f, cy + b * 0.02f);
            path.lineTo(cx - b * 0.16f, cy + b * 0.46f);
            path.lineTo(cx + b * 0.66f, cy - b * 0.42f);
            c.drawPath(path, stroke);
        } else {
            // 上传 / 导出：箭头 + 底部承托线
            boolean up = type == UPLOAD;
            stroke.setColor(ink);
            stroke.setStrokeWidth(sw);
            float top = cy - s * 0.42f, bottom = cy + s * 0.24f;
            path.reset();
            path.moveTo(cx, up ? bottom : top);
            path.lineTo(cx, up ? top : bottom);
            c.drawPath(path, stroke);

            float head = s * 0.30f;
            path.reset();
            if (up) {
                path.moveTo(cx - head, top + head);
                path.lineTo(cx, top);
                path.lineTo(cx + head, top + head);
            } else {
                path.moveTo(cx - head, bottom - head);
                path.lineTo(cx, bottom);
                path.lineTo(cx + head, bottom - head);
            }
            c.drawPath(path, stroke);

            path.reset();
            float baseY = cy + s * 0.48f;
            path.moveTo(cx - s * 0.42f, baseY);
            path.lineTo(cx + s * 0.42f, baseY);
            c.drawPath(path, stroke);
        }

        // 图标下的一行文字
        if (label != null) {
            textPaint.setColor(active ? Ui.PRIMARY : 0xFF8A94A6);
            c.drawText(label, cx, h - 3 * ctx, textPaint);
        }
    }
}
