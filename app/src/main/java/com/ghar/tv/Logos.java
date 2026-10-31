package com.ghar.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads channel logos in the background, with memory and disk caches. */
final class Logos {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final LruCache<String, Bitmap> MEMORY = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private Logos() {
    }

    /** @param placeholder optional view (e.g. initials) hidden once the logo is showing */
    static void load(Context ctx, ImageView view, String url, android.view.View placeholder) {
        view.setTag(url);
        if (placeholder != null) placeholder.setVisibility(android.view.View.VISIBLE);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = MEMORY.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            if (placeholder != null) placeholder.setVisibility(android.view.View.GONE);
            return;
        }
        view.setImageDrawable(null);
        File dir = new File(ctx.getCacheDir(), "logos");
        POOL.execute(() -> {
            if (!url.equals(view.getTag())) return; // view was reused for another channel
            Bitmap b = fetch(dir, url);
            if (b == null) return;
            MEMORY.put(url, b);
            view.post(() -> {
                if (url.equals(view.getTag())) {
                    view.setImageBitmap(b);
                    if (placeholder != null) placeholder.setVisibility(android.view.View.GONE);
                }
            });
        });
    }

    /** Load a logo into the caches ahead of time (no view). */
    static void prefetch(Context ctx, String url) {
        if (url == null || url.isEmpty() || MEMORY.get(url) != null) return;
        File dir = new File(ctx.getCacheDir(), "logos");
        POOL.execute(() -> {
            if (MEMORY.get(url) != null) return;
            Bitmap b = fetch(dir, url);
            if (b != null) MEMORY.put(url, b);
        });
    }

    private static Bitmap fetch(File dir, String url) {
        try {
            File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
            if (!f.exists()) {
                byte[] data = Net.get(url, null, null, 8000, 2 * 1024 * 1024).data;
                if (!dir.exists() && !dir.mkdirs()) return decode(data);
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            }
            return decodeFile(f);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    private static Bitmap decodeFile(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
    }

    private static int sample(int w, int h) {
        int s = 1;
        while (w / (s * 2) >= 240 && h / (s * 2) >= 135) s *= 2;
        return s;
    }
}
