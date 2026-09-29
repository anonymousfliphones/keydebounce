package com.anonymousfliphones.keydebounce;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Optional, off by default: a silent, clearable notification when Start at boot starts
 * the filter ("Starting…", then "on" or "didn't start"). One notification, updated in place.
 */
final class BootNotifier {
    private static final String PREFS = "settings";
    private static final String ENABLED = "notify_boot_start";
    private static final String CHANNEL = "boot_start";
    private static final int ID = 1;

    private BootNotifier() {
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(ENABLED, on).apply();
    }

    static void starting(Context c, boolean startingRoot) {
        post(c, c.getString(R.string.notify_starting_title),
                c.getString(startingRoot ? R.string.notify_starting_root_text : R.string.notify_starting_text));
    }

    static void result(Context c, boolean ok, String reason) {
        if (ok) {
            post(c, c.getString(R.string.notify_on_title), c.getString(R.string.notify_on_text));
        } else {
            post(c, c.getString(R.string.notify_failed_title), c.getString(R.string.notify_failed_text, reason));
        }
    }

    private static void post(Context c, String title, String text) {
        if (!enabled(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        // Low importance: shows in the status bar and the list, without a sound.
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                c.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(c, 3, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOngoing(false)
                .build();
        nm.notify(ID, n);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
