package com.hanxing.mifit;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * 沉浸式窗口：内容铺到状态栏/导航栏下面，顶栏与底栏各自吸收 insets，
 * 状态栏区域显示顶栏同色（白），图标自动转深色。
 * 兼容 MIUI/HyperOS、HarmonyOS/EMUI、ColorOS/OriginOS、One UI 等：
 * 只依赖标准 WindowInsets，另对老 ROM 忽略 setStatusBarColor 的情况加 TRANSLUCENT_STATUS 兜底。
 */
public class Edge {

    public static void setup(final Activity a, final View root, final View header, final View bottomBar) {
        setup(a, root, header, bottomBar, null);
    }

    /** header2 是二级页的顶栏（比如「全部记录」列表页），同样吸收状态栏高度 */
    public static void setup(final Activity a, final View root, final View header,
                             final View bottomBar, final View header2) {
        final Window w = a.getWindow();

        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(mask, mask);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            // 关掉系统的半透明遮罩，否则状态栏/导航栏会发灰
            w.setStatusBarContrastEnforced(false);
            w.setNavigationBarContrastEnforced(false);
        }
        // 少数老 ROM（EMUI 8 / 部分 ColorOS）忽略 setStatusBarColor，补一个 translucent 标志
        w.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);

        // 液态玻璃：API31+ 让窗口背后内容模糊，半透明的顶栏/底栏才有玻璃质感
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                w.setBackgroundBlurRadius((int) (24 * a.getResources().getDisplayMetrics().density));
            } catch (Throwable ignored) {
            }
        }

        final int hL = header.getPaddingLeft(), hT = header.getPaddingTop();
        final int hR = header.getPaddingRight(), hB = header.getPaddingBottom();
        final int bL = bottomBar.getPaddingLeft(), bT = bottomBar.getPaddingTop();
        final int bR = bottomBar.getPaddingRight(), bB = bottomBar.getPaddingBottom();
        final int sT = header2 == null ? 0 : header2.getPaddingTop();
        final int sB = header2 == null ? 0 : header2.getPaddingBottom();

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = insets.getInsets(WindowInsets.Type.statusBars()
                            | WindowInsets.Type.displayCutout()).top;
                    // 只用 navigationBars：手势导航下这里是 0，内容就能一直铺到屏幕最底，
                    // 小白条直接悬浮在底栏上（这才是"沉浸小白条"）；三键导航时才留高度。
                    bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                if (top < 0) top = 0;
                if (bottom < 0) bottom = 0;
                header.setPadding(hL, hT + top, hR, hB);
                bottomBar.setPadding(bL, bT, bR, bB + bottom);
                if (header2 != null) header2.setPadding(header2.getPaddingLeft(), sT + top,
                        header2.getPaddingRight(), sB);
                return insets;
            }
        });
        root.requestApplyInsets();
        // 部分 ROM 首次 insets 不稳定，强制再走一次
        root.post(new Runnable() {
            public void run() { root.requestApplyInsets(); }
        });
    }
}
