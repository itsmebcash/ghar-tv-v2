package com.ghar.tv;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/** TV (remote control) or phone/tablet (touch)? The same APK adapts to both. */
final class Device {

    private Device() {
    }

    static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
