package com.elsnime;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Foreground service that keeps downloads running when the app is backgrounded
 * (or swiped away from recents) and mirrors their progress in a persistent
 * notification.
 *
 * <p>It does not own the download queue — the process-wide {@link Downloader}
 * (created by MainActivity's Backend, registered via {@link #attach}) does. The
 * service just attaches an extra event listener to it, keeps the process alive
 * with {@link #startForeground} while any task is active, and posts a
 * completion/failure notification before stopping itself once the queue drains.
 * The notification shows the current episode, a determinate progress bar
 * (percent + bytes), and a Cancel action; tapping it opens the app.
 *
 * <p>Started with {@link Context#startForegroundService} (API 26+) from the
 * user's download action, type {@code dataSync} (with the matching
 * FOREGROUND_SERVICE_DATA_SYNC permission) for API 34+. If the system kills
 * the service it is not restarted: the in-memory task queue died with the
 * process, so there is nothing to resume.
 */
public final class DownloadService extends Service {
    private static final String CHANNEL_ID = "downloads";
    private static final String CHANNEL_NAME = "Downloads";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_START = "com.elsnime.download.START";
    private static final String ACTION_CANCEL = "com.elsnime.download.CANCEL";
    private static final String EXTRA_KEY = "key";

    /** The app's single Downloader, registered by MainActivity.Backend. */
    private static volatile Downloader downloader;

    private NotificationManager nm;
    private Downloader.Listener notifier;

    static void attach(Downloader d) { downloader = d; }

    /** Start the foreground service (a no-op when it is already running). */
    static void start(Context ctx) {
        Intent i = new Intent(ctx, DownloadService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            // IMPORTANCE_LOW: no sound/vibration — it's just a progress bar.
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Download progress");
            nm.createNotificationChannel(ch);
        }
        notifier = this::onDownloadEvent;
        if (downloader != null) downloader.addListener(notifier);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        // The process was recreated for this service alone (the app/Backend died
        // with it): the task queue is gone, so there is nothing to keep alive.
        if (downloader == null) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            // Notification Cancel action → cancel that episode's task.
            downloader.cancel(intent.getStringExtra(EXTRA_KEY));
            return START_NOT_STICKY;
        }
        foreground(buildNotification("Preparing download…", true, 0, null, null));
        // Close the race where a very fast download finished before this
        // listener was attached (its terminal event would be missed forever):
        // if nothing is active now, nothing will ever emit a terminal event.
        if (downloader.activeCount() == 0) stopSelfAndNotify(null);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (downloader != null && notifier != null) downloader.removeListener(notifier);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Runs on the downloader's worker thread; NotificationManager is thread-safe. */
    private void onDownloadEvent(JSONObject ev) {
        String state = ev.optString("state");
        String title = titleOf(ev);
        String key = ev.optString("anime_id") + "|" + ev.optString("episode");
        switch (state) {
            case "done":
            case "error":
            case "cancelled": {
                // Terminal event: stop when this was the last queued task (the
                // emitting task is still in the map here, so <=1 = queue empty).
                if (downloader == null || downloader.activeCount() <= 1) {
                    String finish;
                    if ("done".equals(state)) finish = "Download complete";
                    else if ("cancelled".equals(state)) finish = null;
                    else finish = "Download failed: " + ev.optString("error");
                    stopSelfAndNotify(finish);
                }
                return;
            }
            case "downloading": {
                int total = ev.optInt("segmentsTotal", 0);
                int done = ev.optInt("segmentsDone", 0);
                long bytes = ev.optLong("bytesDone", 0);
                if (total > 0) {
                    int pct = (int) (100.0 * done / total);
                    foreground(buildNotification(pct + "% · " + formatBytes(bytes), false, pct, key, title));
                } else {
                    foreground(buildNotification(formatBytes(bytes), true, 0, key, title));
                }
                return;
            }
            case "remuxing":
                foreground(buildNotification("Converting to MP4…", true, 0, key, title));
                return;
            default: // resolving and anything else
                foreground(buildNotification("Resolving stream…", true, 0, key, title));
        }
    }

    private void foreground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, n);
    }

    /** Drop the foreground notification and stop; optionally leave a transient
     *  completion/failure notification behind first. */
    private void stopSelfAndNotify(String finalText) {
        if (finalText != null && nm != null) {
            Notification n = builder()
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Elsnime")
                .setContentText(finalText)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .build();
            nm.notify(NOTIFICATION_ID + 1, n);
        }
        stopForeground(true);
        stopSelf();
    }

    private Notification buildNotification(String text, boolean indeterminate, int pct, String key, String title) {
        Notification.Builder b = builder()
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title != null ? title : "Elsnime")
            .setContentText(text)
            .setOnlyAlertOnce(true)   // don't re-alert on every progress tick
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openAppPendingIntent());
        if (indeterminate) b.setProgress(0, 0, true);
        else b.setProgress(100, Math.max(0, Math.min(100, pct)), false);
        if (key != null) b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent(key));
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        return b.build();
    }

    private Notification.Builder builder() {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
    }

    private static String titleOf(JSONObject ev) {
        String anime = ev.optString("anime_title");
        String ep = ev.optString("episode");
        return (anime.isEmpty() ? "Elsnime" : anime) + (ep.isEmpty() ? "" : " - Episode " + ep);
    }

    private PendingIntent openAppPendingIntent() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent cancelPendingIntent(String key) {
        // Distinct request codes per task: PendingIntent matches on component +
        // action only (extras are ignored), so a shared code would cancel the
        // wrong episode.
        Intent cancel = new Intent(this, DownloadService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_KEY, key);
        return PendingIntent.getService(this, key.hashCode(), cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024.0);
    }
}
