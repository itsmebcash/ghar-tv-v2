package com.ghar.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Keeps the channel check going after you leave Ghar TV (for YouTube, or the home screen), like
 * Ghar TV Mobile does. Before, the check stopped 3 seconds after leaving and ran with only 2
 * threads while watching, so the TV checked far fewer channels than the phone. Gentle (6 at
 * once) while you're elsewhere, and it stops by itself when every channel has been checked.
 */
public class ScanService extends Service implements Repo.Listener {

    private static final String CHANNEL = "scan";
    private static final int ID = 31;
    private static volatile boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Repo repo;
    private PowerManager.WakeLock wake;
    private long lastUpdate;
    private long idleSince;

    /** Start while Ghar TV is on screen (Android doesn't allow it from the background). */
    static void start(Context c) {
        if (running) return;
        try {
            Intent i = new Intent(c, ScanService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {
            // not allowed right now: the check runs while the app is open
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        goForeground(getString(R.string.scan_notif_starting));
        repo = Repo.get(this);
        repo.addListener(this);
        repo.backgroundStarted();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ghartv:scan");
            wake.setReferenceCounted(false);
            wake.acquire(3 * 60 * 60 * 1000L); // at most 3 hours
        }
        main.postDelayed(this::check, 5000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onRepoChanged() {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < 3000) return;
        lastUpdate = now;
        check();
    }

    private void check() {
        if (repo == null) return;
        if (repo.loaded && !repo.scanning) {
            // The check also pauses for a moment when it restarts with another speed: stop only
            // when it has really finished (still idle a few seconds later).
            long now = System.currentTimeMillis();
            if (idleSince == 0) idleSince = now;
            if (now - idleSince >= 6000) {
                stopSelf();
            } else {
                main.removeCallbacks(this::check);
                main.postDelayed(this::check, 6500);
            }
            return;
        }
        idleSince = 0;
        java.text.NumberFormat nf = java.text.NumberFormat.getIntegerInstance();
        String text = repo.toCheck > 0
                ? getString(R.string.scan_notif_progress, nf.format(repo.checked.get()), nf.format(repo.toCheck),
                nf.format(repo.working.size()))
                : getString(R.string.scan_notif_starting);
        goForeground(text);
    }

    @SuppressWarnings("deprecation")
    private void goForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.scan_notif_title),
                        NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        Notification n = b.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(getString(R.string.scan_notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(ID, n);
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        if (repo != null) {
            repo.removeListener(this);
            repo.backgroundStopped();
        }
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
