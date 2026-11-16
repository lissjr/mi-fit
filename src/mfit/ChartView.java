package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;

/** 单条曲线：点一下选中、长按拖动连续扫点，和网页版一样联动所有图 */
public class ChartView extends View {
    public interface OnCursor {
        void onCursor(int idx);
    }

    private double[] vals;
    private double[] mt;          // 横轴：运动计时（秒）
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCur = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String unit = "";
    private int color = 0xFF2F7CF6;
    private int cursor = -1;
    private OnCursor listener;
    private boolean paceMode;     // 配速：越小越快，画的时候翻转

    private final int padL, padR, padT, padB;

    public ChartView(Context c, double[] vals, double[] mt, String unit, int color) {
        super(c);
        this.vals = vals;
        this.mt = mt;
        this.unit = unit;
        this.color = color;
        pLine.setColor(color);
        pLine.setStrokeWidth(dp(2));
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pDot.setColor(color);
        pDot.setStyle(Paint.Style.FILL);
        pCur.setColor(0xFF1D2129);
        pCur.setStrokeWidth(dp(1));
        pTxt.setColor(0xFF86909C);
        pTxt.setTextSize(dp(11.2f));
        pTxt.setTypeface(Font.normal(c));
        pGrid.setColor(0xFFEDEFF3);
        pGrid.setStrokeWidth(dp(1));
        pBubble.setColor(0xF21D2129);
        padL = dp(34);
        padR = dp(10);
        padT = dp(15);
        padB = dp(20);
    }

    public void setPaceMode(boolean b) { paceMode = b; }

    public void setCursor(int idx) {
        if (idx == cursor) return;
        cursor = idx;
        invalidate();
    }

    public void setOnCursor(OnCursor l) { listener = l; }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        setMeasuredDimension(width, dp(150));
    }

    private double vmin = 0, vmax = 0;

    private void ranges() {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        int n = 0;
        for (double v : vals) if (v > 0) { lo = Math.min(lo, v); hi = Math.max(hi, v); n++; }
        if (n == 0) { lo = 0; hi = 1; }
        if (hi - lo < 1e-6) { hi = lo + 1; }
        // 留一点上下空间
        double pad = (hi - lo) * 0.08;
        vmin = lo - pad;
        vmax = hi + pad;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (vals == null || vals.length < 2) {
            pTxt.setTextSize(dp(12.3f));
            pTxt.setColor(0xFFA8AFBB);
            c.drawText("这一场没有可绘制的逐点数据", padL, h / 2f, pTxt);
            return;
        }
        ranges();
        int n = vals.length;
        double tmax = mt[n - 1] > 0 ? mt[n - 1] : n;
        float plotW = w - padL - padR;
        float plotH = h - padT - padB;

        // 网格 + 纵轴标签
        pTxt.setTextSize(dp(11.2f));
        pTxt.setColor(0xFF86909C);
        for (int i = 0; i <= 3; i++) {
            float y = padT + plotH * i / 3f;
            c.drawLine(padL, y, w - padR, y, pGrid);
            double v = vmax - (vmax - vmin) * i / 3.0;
            String lab = fmt(v);
            c.drawText(lab, dp(2), y + dp(4), pTxt);
        }

        // 横轴：分钟
        pTxt.setColor(0xFFA8AFBB);
        int mins = (int) (tmax / 60);
        for (int m = 0; m <= 3; m++) {
            float x = padL + plotW * m / 3f;
            int mm = (int) Math.round(mins * m / 3.0);
            String lab = mm + (m == 3 ? "分" : "");
            float tw = pTxt.measureText(lab);
            c.drawText(lab, Math.min(x, w - padR - tw), h - dp(5), pTxt);
        }

        // 折线（每段独立，遇到 0 断开）
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < n; i++) {
            double v = vals[i];
            if (v <= 0) { started = false; continue; }
            float x = padL + plotW * (float) (mt[i] / tmax);
            float y = padT + plotH * (float) ((vmax - v) / (vmax - vmin));
            if (!started) { path.moveTo(x, y); started = true; }
            else path.lineTo(x, y);
        }
        c.drawPath(path, pLine);

        // 游标
        if (cursor >= 0 && cursor < n) {
            double v = vals[cursor];
            float x = padL + plotW * (float) (mt[cursor] / tmax);
            c.drawLine(x, padT, x, padT + plotH, pCur);
            String label;
            if (v > 0) {
                label = fmt(v) + (unit.length() > 0 ? " " + unit : "");
                float y = padT + plotH * (float) ((vmax - v) / (vmax - vmin));
                pDot.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, dp(3.5f), pDot);
            } else {
                label = "—";
            }
            pTxt.setTextSize(dp(11.8f));
            float tw = pTxt.measureText(label);
            float bx = Math.min(Math.max(x, padL + tw / 2 + dp(6)), w - padR - tw / 2 - dp(6));
            float by = padT + dp(4);
            c.drawRoundRect(bx - tw / 2 - dp(6), by, bx + tw / 2 + dp(6), by + dp(17), dp(5), dp(5), pBubble);
            pTxt.setColor(Color.WHITE);
            c.drawText(label, bx - tw / 2, by + dp(12), pTxt);
            pTxt.setColor(0xFF86909C);
        }
    }

    private String fmt(double v) {
        if (unit.equals("bpm") || unit.equals("步/分") || unit.equals("W") || unit.equals("ms")
                || unit.equals("cm") || unit.equals("m") || unit.equals("%"))
            return String.format("%.0f", v);
        if (unit.equals("km/h")) return String.format("%.1f", v);
        if (unit.equals("/km")) {
            int s = (int) Math.round(v);
            return (s / 60) + "'" + String.format("%02d", s % 60);
        }
        return String.format("%.0f", v);
    }

    private void pick(float x) {
        int n = vals.length;
        double tmax = mt[n - 1] > 0 ? mt[n - 1] : n;
        float plotW = getWidth() - padL - padR;
        double t = (x - padL) / plotW * tmax;
        int best = 0;
        for (int i = 1; i < n; i++) if (Math.abs(mt[i] - t) < Math.abs(mt[best] - t)) best = i;
        if (listener != null) listener.onCursor(best);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 从按下这一刻起就不许父容器（ScrollView）抢事件，
                // 否则手指稍微上下偏一点就被判成翻页，曲线拖动直接断掉
                getParent().requestDisallowInterceptTouchEvent(true);
                pick(e.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                pick(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return true;
        }
    }
}
