package com.ghar.tv;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * Ghar TV speaks Nepali by default (made for the family, grandparents included), whatever
 * language the TV itself is set to. Settings › भाषा / Language switches to English and back.
 */
final class Lang {

    static final String NEPALI = "ne", ENGLISH = "en";
    private static final String PREFS = "ghar_lang";
    private static final String KEY = "ui_lang";

    private Lang() {
    }

    static String current(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, NEPALI);
    }

    static void set(Context c, String lang) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).commit();
    }

    static boolean isNepali(Context c) {
        return NEPALI.equals(current(c));
    }

    /** Every screen's base context, in the chosen language. */
    static Context wrap(Context base) {
        Locale locale = new Locale(current(base));
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= 24) config.setLocales(new android.os.LocaleList(locale));
        else config.setLocale(locale);
        return base.createConfigurationContext(config);
    }
}
