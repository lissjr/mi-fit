package com.hanxing.mifit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;

/**
 * 三页容器兼下拉刷新。
 * 在 dispatchTouchEvent 层旁观触摸：页面在顶部、手指往下拽够长、抬手时触发刷新。
 * 不消费事件，正常滚动完全不受影响；图表内的拖动也不会被拦。
 * 下拉过程中顶部浮出一个圆形箭头，越过阈值后翻转 180°，松手转圈，结束后收起。
 */
public class PullRoot extends FrameLayout {

    private ScrollView cur;
    private Runnable onRefresh;
    private String tip = "正在同步最新数据…";
    private float y0;
    private boolean armed;
    private boolean fired;
    private final float th;

    private final RefreshDot dot;
    private ValueAnimator spinAnim;

    public PullRoot(Context c) {
        super(c);
        th = 26f * getResources().getDisplayMetrics().density;
        dot = new RefreshDot(c);
        LayoutParams lp = new LayoutParams(Ui.dp(c, 38), Ui.dp(c, 38));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = 0;
        addView(dot, lp);
        dot.setTranslationY(-Ui.dp(c, 60));
        dot.setAlpha(0f);
    }

    public void setPage(ScrollView s) { cur = s; }

    public void setAction(String tip, Runnable r) {
        this.tip = tip;
        this.onRefresh = r;
    }

    /** 把箭头提到最上层（三页加进来之后调用） */
    public void lift() { dot.bringToFront(); }

    /** 停在状态栏下方，避开挖孔摄像头 */
    private float upY() { return Ui.dp(getContext(), 58); }

    private float hideY() { return -Ui.dp(getContext(), 60); }

    /** 数据回来了，箭头立刻收起（不用等固定时长） */
    public void stop() {
        if (spinAnim == null && !fired) return;
        postDelayed(new Runnable() {
            public void run() {
                if (spinAnim != null) { spinAnim.cancel(); spinAnim = null; }
                fired = false;
                dot.animate().translationY(hideY()).alpha(0f).setDuration(240).start();
            }
        }, 120);
    }

    private void follow(float dy) {
        float p = Math.min(1f, dy / (th * 1.7f));
        dot.setAlpha(p);
        dot.setTranslationY(hideY() + (upY() - hideY()) * p);
        dot.setAngle(p * 180f);
    }

    private void spin() {
        if (spinAnim != null) spinAnim.cancel();
        spinAnim = ValueAnimator.ofFloat(0f, 360f);
        spinAnim.setDuration(760);
        spinAnim.setRepeatCount(ValueAnimator.INFINITE);
        spinAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                dot.setAngle((Float) a.getAnimatedValue());
            }
        });
        spinAnim.start();
    }

    private void retract(long delay) {
        postDelayed(new Runnable() {
            public void run() {
                if (spinAnim != null) { spinAnim.cancel(); spinAnim = null; }
                dot.animate().translationY(hideY()).alpha(0f).setDuration(240).start();
            }
        }, delay);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                y0 = e.getY();
                armed = false;
                fired = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (spinAnim == null && cur != null && cur.getScrollY() <= 0) {
                    float dy = e.getY() - y0;
                    if (dy > 0) {
                        if (dy > th) armed = true;
                        follow(dy);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (armed && !fired) {
                    fired = true;
                    dot.setAlpha(1f);
                    dot.setTranslationY(upY());
                    spin();
                    if (onRefresh != null) onRefresh.run();
                    retract(1400);
                } else if (spinAnim == null) {
                    dot.animate().translationY(hideY()).alpha(0f).setDuration(200).start();
                }
                armed = false;
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(e);
    }
}
