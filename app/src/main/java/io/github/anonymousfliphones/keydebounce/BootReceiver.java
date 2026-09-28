package io.github.anonymousfliphones.keydebounce;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Root mode's "Start at boot". Two minutes after a restart it starts checking, every
 * 30 seconds and without su, whether root is active, and starts the daemon through su
 * (kd.sh rootstart) once root has been active on two checks in a row. The XP3800 root
 * is off after every restart until the root app turns it on, and su while it's off
 * crashes the phone; the second check keeps su away from a root app that is still
 * turning root on. Never asks for root when the permanent install is present: with
 * its policy loaded, root requests crashed the phone and caused reboot loops.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "settings";
    private static final String START_AT_BOOT = "start_at_boot";
    private static final String ROOT_SEEN = "boot_root_seen";
    private static final String WAITING_LOGGED = "boot_waiting_logged";
    private static final String ACTION_CHECK = "io.github.anonymousfliphones.keydebounce.BOOT_CHECK";
    private static final long FIRST_CHECK_MS = 2 * 60 * 1000;
    private static final long RECHECK_MS = 30 * 1000;

    static boolean startAtBoot(Context c) {
        return prefs(c).getBoolean(START_AT_BOOT, false);
    }

    static void setStartAtBoot(Context c, boolean on) {
        prefs(c).edit().putBoolean(START_AT_BOOT, on).apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (!startAtBoot(app)) return;
            prefs(app).edit().putBoolean(ROOT_SEEN, false).putBoolean(WAITING_LOGGED, false).apply();
            log(app, false, "Restarted. Checking for root in 2 minutes.");
            schedule(app, FIRST_CHECK_MS);
        } else if (ACTION_CHECK.equals(action)) {
            check(app);
        }
    }

    private void check(Context app) {
        if (!startAtBoot(app)) {
            log(app, true, "Start at boot was turned off. Stopped checking.");
            return;
        }
        if (PhoneStatus.installTraceOnPhone()) {
            log(app, true, "Skipped: the fix is installed, and root requests with it installed crash the phone.");
            return;
        }
        if (PhoneStatus.countKeypads() >= 2) {
            log(app, true, "The filter is already running. Stopped checking.");
            return;
        }
        SharedPreferences p = prefs(app);
        if (RootShell.rootInactive(app)) {
            if (!p.getBoolean(WAITING_LOGGED, false)) {
                log(app, true, "Root isn't active yet. Open the root app; the filter starts about a minute after root is on. Checking every 30 seconds.");
            }
            p.edit().putBoolean(ROOT_SEEN, false).putBoolean(WAITING_LOGGED, true).apply();
            schedule(app, RECHECK_MS);
            return;
        }
        if (!p.getBoolean(ROOT_SEEN, false)) {
            p.edit().putBoolean(ROOT_SEEN, true).apply();
            log(app, true, "Root is active. Starting after one more check in 30 seconds.");
            schedule(app, RECHECK_MS);
            return;
        }
        PendingResult pending = goAsync();
        new Thread(() -> {
            StringBuilder out = new StringBuilder("Starting the filter:\n");
            try {
                File dir = RootShell.unpack(app);
                int code = RootShell.run(app, dir, RootActivity.ROOT_START, line -> out.append(line).append('\n'));
                out.append("exit ").append(code);
            } catch (IOException e) {
                out.append("Couldn't unpack the app's files: ").append(e.getMessage());
            } finally {
                log(app, true, out.toString());
                pending.finish();
            }
        }).start();
    }

    private static void schedule(Context c, long delayMs) {
        Intent i = new Intent(c, BootReceiver.class).setAction(ACTION_CHECK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        // Not a wakeup alarm: while root stays off this repeats, and it shouldn't keep
        // waking the phone. It fires once the phone is awake, e.g. to open the root app.
        am.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, pi);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Android/data/<package>/files/boot-start.log, readable with adb pull. New file at each restart. */
    private static void log(Context c, boolean append, String text) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        try (OutputStream out = new FileOutputStream(new File(dir, "boot-start.log"), append)) {
            out.write((time + " " + text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Nothing else to report it to at boot.
        }
    }
}
