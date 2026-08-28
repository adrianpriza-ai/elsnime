package com.elsnime;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Background receiver that checks followed anime for new episodes and shows
 * a notification when new ones are found. Scheduled by MainActivity on boot
 * and app open, runs every ~12 hours via AlarmManager.
 */
public class EpisodeCheckReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "episode_updates";
    private static final String CHANNEL_NAME = "New Episodes";
    private static final int NOTIFICATION_ID = 7701;

    @Override
    public void onReceive(Context context, Intent intent) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> checkEpisodes(context));
        exec.shutdown();
    }

    /** Query /api/check-followed via the native scraper (no WebView needed). */
    private void checkEpisodes(Context context) {
        try {
            MainActivity.HistoryDb db = new MainActivity.HistoryDb(context.getApplicationContext());
            AniDbScraper scraper = new AniDbScraper();
            scraper.setCache(new AniDbScraper.CacheStore() {
                public String get(String key) { return db.cacheGet(key); }
                public void put(String key, String value, long ttl) { db.cachePut(key, value, ttl); }
                public void clear() { db.cacheClear(); }
                public void clearPrefix(String p) { db.cacheClearPrefix(p); }
            });

            JSONArray follows = db.followed();
            StringBuilder titles = new StringBuilder();
            int totalNew = 0;

            for (int i = 0; i < follows.length(); i++) {
                JSONObject f = follows.optJSONObject(i);
                if (f == null) continue;
                String aid = f.optString("anime_id");
                // Entries saved with an AniList-only ID (al-*) have no AniDB
                // entry, so episode checks cannot run — skip them silently.
                if (aid == null || aid.startsWith("al-")) continue;
                String lastEps = f.optString("last_known_eps", "0");
                try {
                    JSONArray eps = scraper.episodes(aid, "sub");
                    int currentCount = eps.length();
                    int prev = 0;
                    try { prev = Integer.parseInt(lastEps); } catch (Exception ignored) {}
                    if (currentCount > prev) {
                        int newEps = currentCount - prev;
                        totalNew += newEps;
                        if (titles.length() > 0) titles.append(", ");
                        titles.append(f.optString("anime_title")).append(" (+").append(newEps).append(")");
                    }
                    db.updateFollowEps(aid, String.valueOf(currentCount));
                } catch (Exception ignored) {}
            }

            if (totalNew > 0) {
                showNotification(context, totalNew, titles.toString());
            }
        } catch (Exception ignored) {}
    }

    private void showNotification(Context context, int count, String details) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notifications for new anime episodes");
            nm.createNotificationChannel(ch);
        }

        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = count + " new episode" + (count > 1 ? "s" : "");
        String body = details.length() > 120 ? details.substring(0, 117) + "..." : details;

        android.app.Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new android.app.Notification.Builder(context, CHANNEL_ID);
        } else {
            builder = new android.app.Notification.Builder(context);
        }
        builder.setSmallIcon(R.mipmap.ic_launcher)
               .setContentTitle(title)
               .setContentText(body)
               .setAutoCancel(true)
               .setContentIntent(pi)
               .setStyle(new android.app.Notification.BigTextStyle().bigText(body));

        nm.notify(NOTIFICATION_ID, builder.build());
    }
}
