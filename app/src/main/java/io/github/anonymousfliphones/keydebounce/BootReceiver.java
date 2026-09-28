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
 * Root mode's "Start at boot": starts the daemon through su (kd.sh rootstart) after a
 * restart. With a root that is on at boot (Magisk) it starts right away.
 *
 * With Root Manager (the XP3800 root) it waits: the first su after each boot runs its
 * kernel exploit, which can crash the phone, and doing that at boot caused a reboot loop.
 * So it never starts root itself. From boot it checks every 30 seconds, without su,
 * whether something else has started root, and starts once root has been up for two
 * checks in a row; the second check keeps su away from an exploit still running.
 *
 * Never asks for root when the permanent install is present: with its policy loaded,
 * root requests crashed the phone and caused reboot loops.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "settings";
    private static final String START_AT_BOOT = "start_at_boot";
    private static final String ROOT_SEEN = "boot_root_seen";
    private static final String WAITING_LOGGED = "boot_waiting_logged";
    private static final String ACTION_CHECK = "io.github.anonymousfliphones.keydebounce.BOOT_CHECK";
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
            if (PhoneStatus.installTraceOnPhone()) {
                log(app, false, "Skipped: the fix is installed, and root requests with it installed crash the phone.");
            } else if (RootShell.isXp3RootInstalled(app)) {
                prefs(app).edit().putBoolean(ROOT_SEEN, false).putBoolean(WAITING_LOGGED, false).apply();
                log(app, false, "Restarted. XP3800 root: checking for root every 30 seconds.");
                check(app);
            } else {
                log(app, false, "Restarted.");
                startNow(app);
            }
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
        if (RootShell.rootNotStarted(app)) {
            if (!p.getBoolean(WAITING_LOGGED, false)) {
                log(app, true, "Root hasn't started since the restart. KeyDebounce doesn't start it at boot: the first su runs Root Manager's kernel exploit, which can crash the phone. Once something else has used su, the filter starts 30 to 60 seconds later. Checking every 30 seconds.");
            }
            p.edit().putBoolean(ROOT_SEEN, false).putBoolean(WAITING_LOGGED, true).apply();
            schedule(app, RECHECK_MS);
            return;
        }
        if (!p.getBoolean(ROOT_SEEN, false)) {
            p.edit().putBoolean(ROOT_SEEN, true).apply();
            log(app, true, "Root has started. Starting after one more check in 30 seconds.");
            schedule(app, RECHECK_MS);
            return;
        }
        startNow(app);
    }

    private void startNow(Context app) {
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
        // Not a wakeup alarm: until root starts this repeats, and it shouldn't keep
        // waking the phone. It fires once the phone is awake, e.g. to start root.
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
