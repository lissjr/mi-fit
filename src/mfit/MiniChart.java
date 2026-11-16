package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** 首页用的小曲线 / 柱状图：带左侧纵轴刻度，可带底部时间轴 */
public class MiniChart extends View {
    private final List<double[]> pts = new ArrayList<double[]>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint px = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color = 0xFF14A0C0;
    private boolean bar = false;
    private boolean axis = false;
    private double min = 0, max = 1;

    public MiniChart(Context c) {
        super(c);
        p.setStrokeWidth(Ui.dp(c, 1.6f));
        pt.setColor(0xFFA6AEBB);
        pt.setTextSize(Ui.dp(c, 10));
        pt.setTypeface(Font.normal(c));
        pt.setFontFeatureSettings("tnum");
        pt.setTextAlign(Paint.Align.RIGHT);
        px.setColor(0xFFA6AEBB);
        px.setTextSize(Ui.dp(c, 9.5f));
        px.setTypeface(Font.normal(c));
        px.setFontFeatureSettings("tnum");
    }

    public void setColor(int c) { color = c; invalidate(); }

    public void setBar(boolean b) { bar = b; invalidate(); }

    /** 底部时间轴：左 / 中 / 右三个刻度，跨度小用时刻、跨度大用日期 */
    public void setAxis(boolean b) { axis = b; invalidate(); }

    public void setData(List<double[]> list, double lo, double hi) {
        pts.clear();
        if (list != null) pts.addAll(list);
        min = lo;
        max = hi > lo ? hi : lo + 1;
        invalidate();
    }

    /** 刻度文字：跨度小的时候留一位小数 */
    private String tick(double v) {
        double span = max - min;
        if (span < 6) return String.format(java.util.Locale.US, "%.1f", v);
        return String.valueOf(Math.round(v));
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        int padL = Ui.dp(getContext(), 30);
        int x0 = padL, x1 = w - Ui.dp(getContext(), 4);
        int y0 = Ui.dp(getContext(), 8);
        boolean ax = axis && pts.size() > 1;
        int y1 = ax ? h - Ui.dp(getContext(), 20) : h - Ui.dp(getContext(), 8);
        if (w <= 0 || h <= 0 || pts.isEmpty()) return;

        // 网格：三条横线 + 左侧刻度值
        p.setColor(0x14000000);
        p.setStrokeWidth(1);
        for (int i = 0; i <= 2; i++) {
            int y = y0 + (y1 - y0) * i / 2;
            cv.drawLine(x0, y, x1, y, p);
            double v = max - (max - min) * i / 2.0;
            cv.drawText(tick(v), padL - Ui.dp(getContext(), 5), y + Ui.dp(getContext(), 3.4f), pt);
        }

        p.setColor(color);
        p.setStrokeWidth(Ui.dp(getContext(), 1.6f));
        double span = max - min;

        if (bar) {
            int n = pts.size();
            float bw = (float) (x1 - x0) / Math.max(1, n);
            for (int i = 0; i < n; i++) {
                double v = pts.get(i)[1];
                if (v <= 0) continue;
                int bh = (int) Math.round((v - min) / span * (y1 - y0));
                float cx = x0 + bw * i + bw * 0.18f;
                cv.drawRect(cx, y1 - bh, cx + bw * 0.64f, y1, p);
            }
        } else {
            Path path = new Path();
            Path fill = new Path();
            int n = pts.size();
            for (int i = 0; i < n; i++) {
                double v = pts.get(i)[1];
                float x = x0 + (x1 - x0) * (n == 1 ? 0.5f : (float) i / (n - 1));
                float y = (float) (y1 - (v - min) / span * (y1 - y0));
                if (i == 0) {
                    path.moveTo(x, y);
                    fill.moveTo(x, y1);
                    fill.lineTo(x, y);
                } else {
                    path.lineTo(x, y);
                    fill.lineTo(x, y);
                }
            }
            if (n > 1) {
                fill.lineTo(x1, y1);
                fill.close();
                p.setColor((color & 0x00FFFFFF) | 0x2A000000);
                p.setStyle(Paint.Style.FILL);
                cv.drawPath(fill, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(color);
                p.setStrokeWidth(Ui.dp(getContext(), 1.6f));
                cv.drawPath(path, p);
            }
        }
        if (ax) drawAxis(cv, x0, x1, h);
    }

    /** 底部三个时间刻度：取首 / 中 / 末三个点的真实时间，缺失日期也不会错位 */
    private void drawAxis(Canvas cv, int x0, int x1, int h) {
        int n = pts.size();
        double t0 = pts.get(0)[0];
        double tm = pts.get(n / 2)[0];
        double tN = pts.get(n - 1)[0];
        long spanSec = (long) Math.abs(tN - t0);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                spanSec > 20 * 3600L ? "MM-dd" : "HH:mm", java.util.Locale.CHINA);
        int ty = h - Ui.dp(getContext(), 5);
        px.setTextAlign(Paint.Align.LEFT);
        cv.drawText(f.format(new java.util.Date((long) t0 * 1000L)), x0, ty, px);
        px.setTextAlign(Paint.Align.CENTER);
        cv.drawText(f.format(new java.util.Date((long) tm * 1000L)),
                (x0 + x1) / 2f, ty, px);
        px.setTextAlign(Paint.Align.RIGHT);
        cv.drawText(f.format(new java.util.Date((long) tN * 1000L)), x1, ty, px);
    }
}
