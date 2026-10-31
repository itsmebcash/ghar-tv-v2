package com.ghar.tv;

import android.os.Handler;
import android.os.Looper;

/**
 * Phone lists: one tap = watch, two quick taps on the same channel = favourite.
 * The single tap waits ~¼ s to make sure it isn't the first half of a double tap.
 * On TV (remote) there is no double tap, so OK acts immediately.
 */
final class Taps {

    private static final long DOUBLE_TAP_MS = 280;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final boolean immediate;
    private int lastPosition = -1;
    private long lastTime;
    private Runnable pending;

    Taps(boolean immediate) {
        this.immediate = immediate;
    }

    void tap(int position, Runnable single, Runnable twice) {
        if (immediate) {
            single.run();
            return;
        }
        long now = System.currentTimeMillis();
        if (position == lastPosition && now - lastTime < DOUBLE_TAP_MS && pending != null) {
            handler.removeCallbacks(pending);
            pending = null;
            lastPosition = -1;
            twice.run();
            return;
        }
        if (pending != null) handler.removeCallbacks(pending);
        lastPosition = position;
        lastTime = now;
        pending = () -> {
            pending = null;
            single.run();
        };
        handler.postDelayed(pending, DOUBLE_TAP_MS);
    }

    void cancel() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }
}
