package com.ghar.tv;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * If the app ever closes unexpectedly: remember why (a report you can share) and which channels were
 * opening at that moment, so on the next start those channels wait for a tap instead of starting by
 * themselves. That stops a bad stream from closing the app again and again.
 */
final class CrashGuard {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    /** Channels being opened right now (the one on screen and the one getting ready). */
    private static final Set<String> OPENING = Collections.synchronizedSet(new LinkedHashSet<String>());
    private static volatile boolean installed;

    private CrashGuard() {
    }

    static void install(Context c) {
        if (installed) return;
        installed = true;
        Context app = c.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                write(app, "crash", error, new ArrayList<>(OPENING));
                Set<String> bad = new HashSet<>(Store.crashChannels(app));
                bad.addAll(OPENING);
                Store.setCrashChannels(app, bad);
            } catch (Throwable ignored) {
                // never make it worse
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void opening(String url, boolean on) {
        if (url == null) return;
        if (on) OPENING.add(url);
        else OPENING.remove(url);
    }

    static void clearOpening() {
        OPENING.clear();
    }

    /** A problem that was caught (the app kept running): kept in the report too. */
    static void note(Context c, Throwable t) {
        try {
            write(c.getApplicationContext(), "caught", t, new ArrayList<>(OPENING));
        } catch (Throwable ignored) {
            // fine
        }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "crash-report.txt");
    }

    private static void write(Context c, String kind, Throwable t, List<String> urls) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Ghar TV " + kind + " at " + new java.util.Date());
        try {
            pw.println("Version " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName
                    + ", Android " + Build.VERSION.RELEASE + ", " + Build.MANUFACTURER + " " + Build.MODEL);
        } catch (Exception ignored) {
            // fine
        }
        for (String u : urls) pw.println("Opening: " + u);
        t.printStackTrace(pw);
        pw.flush();
        File f = file(c);
        // a real crash replaces the report; caught problems are added (up to ~40 KB)
        boolean append = !"crash".equals(kind) && f.exists() && f.length() < 40_000;
        try (FileOutputStream out = new FileOutputStream(f, append)) {
            out.write((sw.toString() + "\n").getBytes(UTF8));
        }
        if ("crash".equals(kind)) Store.setCrashPending(c, true);
    }

    /** The report text, or null when there is none. */
    static String report(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 60_000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(b);
                return n <= 0 ? null : new String(b, 0, n, UTF8);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
