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
 * With Root Manager (the XP3800 root) the first su after each boot runs its kernel
 * exploit, which can crash the phone. From boot it checks every 30 seconds. Once the su
 * binary has been there on two checks in a row, it calls su itself, starting root and the
 * filter. A flag written before that su and cleared after it catches a crash: the next
 * boot asks (BootPromptActivity) instead of trying again, so a crashing exploit can't turn
 * into a reboot loop. If something else starts root first, it starts the filter once root
 * has been up for two checks in a row, keeping su away from an exploit still running.
 *
 * Never asks for root when the permanent install is present: with its policy loaded,
 * root requests crashed the phone and caused reboot loops.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "settings";
    private static final String START_AT_BOOT = "start_at_boot";
    private static final String ROOT_SEEN = "boot_root_seen";
    private static final String WAITING_LOGGED = "boot_waiting_logged";
    private static final String AUTO_ATTEMPT = "boot_auto_attempt";
    private static final String AUTO_ALLOWED = "boot_auto_allowed";
    private static final String SU_SEEN = "boot_su_seen";
    private static final String ACTION_CHECK = "io.github.anonymousfliphones.keydebounce.BOOT_CHECK";
    private static final long RECHECK_MS = 30 * 1000;
    private static final long PROMPT_MS = 60 * 1000;

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
                SharedPreferences p = prefs(app);
                boolean lastAttemptUnfinished = p.getBoolean(AUTO_ATTEMPT, false);
                p.edit().putBoolean(ROOT_SEEN, false).putBoolean(WAITING_LOGGED, false)
                        .putBoolean(AUTO_ATTEMPT, false).putBoolean(AUTO_ALLOWED, !lastAttemptUnfinished)
                        .putInt(SU_SEEN, 0).commit();
                log(app, false, "Restarted. XP3800 root: checking every 30 seconds.");
                if (lastAttemptUnfinished) {
                    log(app, true, "The automatic start on the last boot never finished: Root Manager's exploit probably crashed the phone. Asking this time instead of trying again.");
                    schedulePrompt(app);
                }
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
            p.edit().putBoolean(ROOT_SEEN, false).apply();
            if (p.getBoolean(AUTO_ALLOWED, true) && PhoneStatus.findSu()) {
                int seen = p.getInt(SU_SEEN, 0) + 1;
                p.edit().putInt(SU_SEEN, seen).apply();
                if (seen >= 2) {
                    autoStart(app);
                    return;
                }
                log(app, true, "su is there (check 1 of 2). Starting root and the filter after one more check in 30 seconds.");
            } else {
                p.edit().putInt(SU_SEEN, 0).apply();
                if (!p.getBoolean(WAITING_LOGGED, false)) {
                    log(app, true, "Root hasn't started since the restart. Checking every 30 seconds; if something else starts root, the filter starts 30 to 60 seconds later.");
                    p.edit().putBoolean(WAITING_LOGGED, true).apply();
                }
            }
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
        run(app, false, "Starting the filter:\n");
    }

    /**
     * Starts root (Root Manager's exploit) and the filter, no prompt. Called by check() once
     * su has been there on two checks in a row and root hasn't started.
     */
    private void autoStart(Context app) {
        // commit(), not apply(): it must be on disk before the exploit, which can crash the phone.
        prefs(app).edit().putBoolean(AUTO_ATTEMPT, true).commit();
        log(app, true, "su is there (check 2 of 2). Starting root and the filter (runs Root Manager's exploit once):");
        run(app, true, "");
    }

    private void run(Context app, boolean startRoot, String header) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            StringBuilder out = new StringBuilder(header);
            try {
                File dir = RootShell.unpack(app);
                int code = RootShell.run(app, dir, RootActivity.ROOT_START, startRoot, line -> out.append(line).append('\n'));
                out.append("exit ").append(code);
            } catch (IOException e) {
                out.append("Couldn't unpack the app's files: ").append(e.getMessage());
            } finally {
                if (startRoot) prefs(app).edit().putBoolean(AUTO_ATTEMPT, false).commit();
                log(app, true, out.toString());
                pending.finish();
            }
        }).start();
    }

    /** After a crashed automatic start: opens BootPromptActivity a minute from now. */
    private static void schedulePrompt(Context c) {
        Intent i = new Intent(c, BootPromptActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PROMPT_MS, pi);
    }

    private static void schedule(Context c, long delayMs) {
        // Not a wakeup alarm: until root starts this repeats, and it shouldn't keep
        // waking the phone. It fires once the phone is awake, e.g. to start root.
        scheduleAction(c, ACTION_CHECK, 0, delayMs, false);
    }

    private static void scheduleAction(Context c, String action, int requestCode, long delayMs, boolean wakeup) {
        Intent i = new Intent(c, BootReceiver.class).setAction(action);
        PendingIntent pi = PendingIntent.getBroadcast(c, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setExact(wakeup ? AlarmManager.ELAPSED_REALTIME_WAKEUP : AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + delayMs, pi);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Android/data/<package>/files/boot-start.log, readable with adb pull. New file at each restart. */
    static void log(Context c, boolean append, String text) {
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
