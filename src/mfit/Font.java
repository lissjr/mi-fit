package com.hanxing.mifit;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;

/** 全局字体：内嵌 MiSans（Medium / Bold），取不到时退回系统字体 */
public final class Font {
    private static Typeface medium, bold;
    private static boolean loaded;

    private static synchronized void load(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            AssetManager am = c.getApplicationContext().getAssets();
            medium = Typeface.createFromAsset(am, "fonts/MiSans-Medium.ttf");
            bold = Typeface.createFromAsset(am, "fonts/MiSans-Bold.ttf");
        } catch (Throwable t) {
            medium = null;
            bold = null;
        }
        if (medium == null) medium = Typeface.DEFAULT;
        if (bold == null) bold = Typeface.DEFAULT_BOLD;
    }

    public static Typeface of(Context c, boolean isBold) {
        load(c);
        return isBold ? bold : medium;
    }

    /** 普通字重（MiSans Medium） */
    public static Typeface normal(Context c) {
        return of(c, false);
    }

    /** 加粗（MiSans Bold） */
    public static Typeface heavy(Context c) {
        return of(c, true);
    }
}
