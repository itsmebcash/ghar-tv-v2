package com.ghar.tv;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;

/** TV (remote control) or phone/tablet (touch)? The same APK adapts to both. */
final class Device {

    private Device() {
    }

    /** Is there a working internet connection right now? */
    @SuppressWarnings("deprecation")
    static boolean isOnline(Context c) {
        android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true; // can't tell: assume online and let the stream decide
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }
}
