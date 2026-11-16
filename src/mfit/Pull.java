package com.hanxing.mifit;

import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;
import android.widget.Toast;

/**
 * 下拉刷新：不引入 androidx，直接挂在 ScrollView 的触摸上。
 * 只有滚到顶再往下拽才触发，正常滚动不受影响（监听返回 false，事件照旧交给 ScrollView）。
 */
public class Pull {
    public static void attach(final ScrollView sv, final String tip, final Runnable onRefresh) {
        final float[] y0 = new float[]{0f};
        final boolean[] armed = new boolean[]{false};
        final boolean[] fired = new boolean[]{false};
        final float th = 24f * sv.getResources().getDisplayMetrics().density;
        sv.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        y0[0] = e.getY();
                        armed[0] = false;
                        fired[0] = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (!armed[0] && sv.getScrollY() <= 0 && e.getY() - y0[0] > th) armed[0] = true;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (armed[0] && !fired[0]) {
                            fired[0] = true;
                            Toast.makeText(sv.getContext(), tip, Toast.LENGTH_SHORT).show();
                            if (onRefresh != null) onRefresh.run();
                        }
                        armed[0] = false;
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }
}
