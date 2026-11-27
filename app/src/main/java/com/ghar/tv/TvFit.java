package com.ghar.tv;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

/**
 * Same look on every TV. TVs report all sorts of screen densities and many have the text size
 * turned up in their own settings, so a screen that fits one TV overflows on another. On a TV,
 * every screen of the app is laid out as if the TV were exactly 960 × 540 (in dp) with normal
 * text size, then drawn at the TV's real resolution — a 32" HD TV and a 55" 4K TV look alike.
 * Phones are left as they are.
 */
public final class TvFit {

    /** The canvas every TV screen is designed for. */
    public static final float WIDTH_DP = 960f, HEIGHT_DP = 540f;

    private TvFit() {
    }

    /** Wraps an activity's base context (call from attachBaseContext). */
    public static Context wrap(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (!apply(base, config)) return base;
        return base.createConfigurationContext(config);
    }

    /** Fits the given configuration to the TV; false on a phone or tablet. */
    static boolean apply(Context base, Configuration config) {
        if (!Device.isTv(base)) return false;
        DisplayMetrics real = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) base.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            d.getRealMetrics(real);
        } catch (Exception e) {
            return false;
        }
        int w = Math.max(real.widthPixels, real.heightPixels);
        int h = Math.min(real.widthPixels, real.heightPixels);
        if (w <= 0 || h <= 0) return false;
        float density = Math.min(w / WIDTH_DP, h / HEIGHT_DP);
        config.densityDpi = Math.max(80, Math.round(density * DisplayMetrics.DENSITY_DEFAULT));
        config.fontScale = 1f;
        float used = config.densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;
        config.screenWidthDp = Math.round(w / used);
        config.screenHeightDp = Math.round(h / used);
        config.smallestScreenWidthDp = config.screenHeightDp;
        return true;
    }
}
