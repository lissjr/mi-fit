package com.hanxing.mifit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 与网页版一致的配色，外加一层液态玻璃材质 */
public class Ui {
    public static final int BG = 0xFFEEF1F6;
    public static final int CARD = 0xF7FFFFFF;
    public static final int LINE = 0xFFECEFF3;
    public static final int TXT = 0xFF1D2129;
    public static final int SUB = 0xFF86909C;
    public static final int MID = 0xFF4E5969;
    public static final int ORANGE = 0xFFD4380D;
    public static final int ORANGE_BG = 0xFFFFF2E8;
    public static final int ORANGE_BORDER = 0xFFFF9A4D;
    public static final int PRIMARY = 0xFFFF7A2F;
    public static final int PRIMARY_DARK = 0xFFF05A18;
    public static final int CHIP_BG = 0xFFF2F4F7;
    public static final int GPS_BG = 0xFFE8F3FF;
    public static final int GPS_TXT = 0xFF1668DC;
    public static final int GLASS_HI = 0x40FFFFFF;   // 玻璃高光边
    public static final int GLASS_LO = 0x14000000;   // 玻璃暗边
    /** 全局字号缩放（在 tv()/field() 里统一生效） */
    public static final float FS = 1.12f;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(Context c, int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radius));
        if (stroke != 0) d.setStroke(dp(c, 1), stroke);
        return d;
    }

    /** 液态玻璃卡片：iOS 26 那种有厚度、边缘反光的玻璃片 */
    public static android.graphics.drawable.Drawable glass(Context c, int border, float radius) {
        return Glass.card(dp(c, radius), border == ORANGE_BORDER);
    }

    /** 玻璃面板（顶栏 / 底栏） */
    public static android.graphics.drawable.Drawable glassBar(Context c, boolean top) {
        return Glass.bar(top);
    }

    /** 玻璃圆钮（佳明入口） */
    public static android.graphics.drawable.Drawable glassCircle(Context c, boolean on) {
        return Glass.circle(on);
    }

    /** 页面底纹：冷调渐变 + 两团柔光，让上面的半透明玻璃有东西可透 */
    public static android.graphics.drawable.Drawable backdrop(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFEDF2FB, 0xFFDDE7F7, 0xFFF2E8DF});

        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.RADIAL_GRADIENT);
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setColors(new int[]{0x3DFFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        halo.setGradientRadius(dp(c, 520));

        GradientDrawable warm = new GradientDrawable();
        warm.setShape(GradientDrawable.RADIAL_GRADIENT);
        warm.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        warm.setColors(new int[]{0x26FFD2AE, 0x00FFD2AE});
        warm.setGradientRadius(dp(c, 700));

        // 贴着底栏的一团暖光：底栏是玻璃，后面得有东西可透，否则糊完就是一块白板
        GradientDrawable foot = new GradientDrawable();
        foot.setShape(GradientDrawable.RADIAL_GRADIENT);
        foot.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        foot.setColors(new int[]{0x5CFFD3AC, 0x1FFFC79A, 0x00FFC79A});
        foot.setGradientRadius(dp(c, 300));

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{base, warm, foot, halo});
        ld.setLayerInset(1, 0, dp(c, 900), 0, -dp(c, 260));
        ld.setLayerInset(2, 0, dp(c, 640), 0, -dp(c, 300));
        ld.setLayerInset(3, 0, -dp(c, 180), 0, dp(c, 700));
        return ld;
    }

    public static TextView tv(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * FS);
        t.setTextColor(color);
        t.setTypeface(Font.of(c, bold));
        t.setFontFeatureSettings("tnum");   // 数字等宽，数值对齐更整齐
        return t;
    }

    public static TextView pill(Context c, String s, boolean on) {
        TextView t = tv(c, s, 13, on ? ORANGE : MID, on);
        t.setBackground(on ? round(c, ORANGE_BG, ORANGE_BORDER, 999) : round(c, 0xF8FFFFFF, 0xFFE3E8F0, 999));
        t.setPadding(dp(c, 13), dp(c, 6), dp(c, 13), dp(c, 6));
        return t;
    }

    public static TextView btn(Context c, String s, boolean primary) {
        TextView t = tv(c, s, 13.5f, primary ? Color.WHITE : TXT, primary);
        if (primary) {
            GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xFFFF9A55, PRIMARY, PRIMARY_DARK});
            d.setCornerRadius(dp(c, 12));
            d.setStroke(dp(c, 1), 0x40FFFFFF);
            t.setBackground(d);
        } else {
            t.setBackground(round(c, 0xFCFFFFFF, 0xFFDCE2EB, 11));
        }
        // 统一尺寸：同高、同最小宽、同字号，避免各处按钮大小不一
        t.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        t.setGravity(android.view.Gravity.CENTER);
        t.setMinHeight(dp(c, 42));
        t.setMinimumWidth(dp(c, 92));
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView chip(Context c, String s, boolean hot) {
        TextView t = tv(c, s, 11, hot ? ORANGE : MID, false);
        t.setBackground(round(c, hot ? ORANGE_BG : CHIP_BG, 0, 6));
        t.setPadding(dp(c, 6), dp(c, 3), dp(c, 6), dp(c, 3));
        return t;
    }

    public static TextView tag(Context c, String s, boolean gps) {
        TextView t = tv(c, s, 10.5f, gps ? GPS_TXT : SUB, false);
        t.setBackground(round(c, gps ? GPS_BG : CHIP_BG, 0, 4));
        t.setPadding(dp(c, 5), dp(c, 1), dp(c, 5), dp(c, 1));
        return t;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f))));
        return v;
    }

    public static View space(Context c, float h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    // ---------------- 统一风格的玻璃对话框 ----------------

    /** 把内容装进一块居中、圆角、带投影的玻璃面板，窗口背景透明 */
    public static android.app.Dialog panel(Context c, View body) {
        final android.app.Dialog d = new android.app.Dialog(c, android.R.style.Theme_Translucent_NoTitleBar);
        android.widget.FrameLayout host = new android.widget.FrameLayout(c);
        host.setPadding(dp(c, 30), 0, dp(c, 30), 0);
        host.setBackgroundColor(0x59000000);          // 自己铺一层遮罩，状态栏也一起压暗，不会突然变黑

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass(c, 0, 24));
        card.setElevation(0);
        card.setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 16));
        card.addView(body);

        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER;
        host.addView(card, lp);
        d.setContentView(host);

        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                // 真·背景模糊：直接把 Activity 后面的内容糊在弹窗下面
                try {
                    w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                    w.setBackgroundBlurRadius(dp(c, 15));
                    host.setBackgroundColor(0x33000000);
                } catch (Throwable ignored) {
                    host.setBackgroundColor(0x59000000);
                }
            }
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (android.os.Build.VERSION.SDK_INT >= 30
                    && w.getInsetsController() != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                w.getInsetsController().setSystemBarsAppearance(mask, mask);
            }
        }
        return d;
    }

    public static TextView dlgTitle(Context c, String s) {
        TextView t = tv(c, s, 16.5f, TXT, true);
        t.setPadding(0, 0, 0, dp(c, 2));
        return t;
    }

    public static TextView dlgBody(Context c, String s) {
        TextView t = tv(c, s, 13, MID, false);
        t.setLineSpacing(dp(c, 2.5f), 1f);
        t.setPadding(0, dp(c, 6), 0, 0);
        return t;
    }

    /** 对话框里的输入框：圆角白底 + 细描边，跟卡片同一套材质 */
    public static android.widget.EditText field(Context c, String hint) {
        android.widget.EditText t = new android.widget.EditText(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14 * FS);
        t.setTextColor(TXT);
        t.setTypeface(Font.normal(c));
        t.setHint(hint);
        t.setHintTextColor(SUB);
        t.setSingleLine(true);
        t.setBackground(round(c, 0xF7FFFFFF, 0xFFE1E6EE, 11));
        t.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        return t;
    }

    /** 右对齐的按钮行：取消（次）+ 确定（主） */
    public static LinearLayout btnRow(Context c, String cancel, String ok,
                                      final Runnable onCancel, final Runnable onOk) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.END);
        row.setPadding(0, dp(c, 14), 0, 0);
        if (cancel != null) {
            TextView b = btn(c, cancel, false);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (onCancel != null) onCancel.run(); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(c, 10);
            row.addView(b, lp);
        }
        if (ok != null) {
            TextView b = btn(c, ok, true);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { if (onOk != null) onOk.run(); }
            });
            row.addView(b);
        }
        return row;
    }

    public interface ToggleListener { void onToggle(boolean on); }

    /** 分区标题前的橙色小圆点 */
    public static View dot(Context c) {
        View v = new View(c);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(PRIMARY);
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 5), dp(c, 5));
        lp.rightMargin = dp(c, 7);
        v.setLayoutParams(lp);
        return v;
    }

    /** 小胶囊开关：44×26dp，开启时橙色 */
    public static Toggle toggle(Context c, boolean init, ToggleListener l) {
        return new Toggle(c, init, l);
    }

    public static class Toggle extends View {
        private boolean on;
        private final ToggleListener l;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        public Toggle(Context c, boolean init, ToggleListener l) {
            super(c);
            this.on = init;
            this.l = l;
            setClickable(true);
        }

        public boolean isOn() { return on; }

        public void setOn(boolean v) {
            if (on == v) return;
            on = v;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float rad = h / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? PRIMARY : 0xFFE3E8F0);
            r.set(0, 0, w, h);
            cv.drawRoundRect(r, rad, rad, p);
            float rr = rad - dp(getContext(), 3);
            p.setColor(0xFFFFFFFF);
            float cx = on ? (w - rad) : rad;
            cv.drawCircle(cx, h / 2f, rr, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                performClick();
                on = !on;
                invalidate();
                if (l != null) l.onToggle(on);
            }
            return true;
        }

        @Override
        public boolean performClick() { return super.performClick(); }
    }
}
