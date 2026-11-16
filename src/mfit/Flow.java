package com.hanxing.mifit;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 会换行的小标签容器（模拟网页的 flex-wrap） */
public class Flow extends ViewGroup {
    private final int gap;

    public Flow(Context c, float gapDp) {
        super(c);
        gap = Ui.dp(c, gapDp);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int maxW = MeasureSpec.getSize(wSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            measureChild(ch, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
            if (x > 0 && x + cw > maxW) { x = 0; y += lineH + gap; lineH = 0; }
            x += cw + gap;
            lineH = Math.max(lineH, chh);
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec),
                y + lineH + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxW = r - l - getPaddingLeft() - getPaddingRight();
        int x = getPaddingLeft(), y = getPaddingTop(), lineH = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getVisibility() == GONE) continue;
            int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
            if (x > getPaddingLeft() && x - getPaddingLeft() + cw > maxW) {
                x = getPaddingLeft(); y += lineH + gap; lineH = 0;
            }
            ch.layout(x, y, x + cw, y + chh);
            x += cw + gap;
            lineH = Math.max(lineH, chh);
        }
    }
}
