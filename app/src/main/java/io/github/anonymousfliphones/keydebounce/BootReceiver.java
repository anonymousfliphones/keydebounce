package io.github.anonymousfliphones.keydebounce;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
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
 * exploit, which can crash the phone, much more often while the phone is busy (per its
 * author); measured on an XP3800, the load after boot peaks around 2 minutes after power-on
 * and is back near idle at about 5. Apps can't read the load with root off, so it waits a
 * fixed time: from boot it checks every 30 seconds, and from 4.5 minutes after power-on,
 * once the su binary has been there on two checks in a row, it calls su itself (at about 5
 * minutes), starting root and the filter. A marker file, fsynced 30 s before that su and
 * deleted after it, catches a crash: every boot after that asks (BootPromptActivity), until
 * a start works, instead of trying again, so a crashing exploit can't turn into a reboot loop. If something else starts root first, it starts the filter once root
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
    private static final String AUTO_ALLOWED = "boot_auto_allowed";
    private static final String UPTIME_WAIT_LOGGED = "boot_uptime_wait_logged";
    /** Marks an automatic root start in progress; see writeMarker(). */
    private static final String ATTEMPT_MARKER = "boot_auto_attempt";
    private static final String ASK_FIRST_MARKER = "boot_ask_first";
    /** Uptime from which su is counted, so the automatic start runs at about 5 minutes. */
    private static final long SU_COUNT_FROM_UPTIME_MS = 270 * 1000;
    /** Uptime at which the after-crash prompt shows: its Start runs the same exploit. */
    private static final long PROMPT_AT_UPTIME_MS = 300 * 1000;
    private static final String SU_SEEN = "boot_su_seen";
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
                SharedPreferences p = prefs(app);
                boolean lastAttemptUnfinished = marker(app).exists();
                clearMarker(app);
                // Once a start has crashed the phone, keep asking on every boot until one works.
                if (lastAttemptUnfinished) writeFlag(askFirst(app));
                boolean ask = askFirst(app).exists();
                p.edit().putBoolean(ROOT_SEEN, false).putBoolean(WAITING_LOGGED, false)
                        .putBoolean(UPTIME_WAIT_LOGGED, false).putBoolean(AUTO_ALLOWED, !ask)
                        .putInt(SU_SEEN, 0).commit();
                log(app, false, "Restarted. XP3800 root: checking every 30 seconds.");
                if (lastAttemptUnfinished) {
                    log(app, true, "The start on the last boot never finished: Root Manager's exploit probably crashed the phone.");
                }
                if (ask) {
                    log(app, true, "Asking before starting root (a start crashed the phone before; this keeps asking until a start works).");
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
            clearMarker(app);
            log(app, true, "Start at boot was turned off. Stopped checking.");
            return;
        }
        if (PhoneStatus.installTraceOnPhone()) {
            clearMarker(app);
            log(app, true, "Skipped: the fix is installed, and root requests with it installed crash the phone.");
            return;
        }
        if (PhoneStatus.countKeypads() >= 2) {
            clearMarker(app);
            log(app, true, "The filter is already running. Stopped checking.");
            return;
        }
        SharedPreferences p = prefs(app);
        if (RootShell.rootNotStarted(app)) {
            p.edit().putBoolean(ROOT_SEEN, false).apply();
            if (p.getBoolean(AUTO_ALLOWED, true) && PhoneStatus.findSu()) {
                long wait = SU_COUNT_FROM_UPTIME_MS - SystemClock.elapsedRealtime();
                if (wait > 0) {
                    if (!p.getBoolean(UPTIME_WAIT_LOGGED, false)) {
                        log(app, true, "Starting root and the filter about 5 minutes after power-on, once the phone has calmed down after booting.");
                        p.edit().putBoolean(UPTIME_WAIT_LOGGED, true).apply();
                    }
                    schedule(app, Math.max(wait, 1000));
                    return;
                }
                int seen = p.getInt(SU_SEEN, 0) + 1;
                p.edit().putInt(SU_SEEN, seen).apply();
                if (seen >= 2) {
                    autoStart(app);
                    return;
                }
                // Written now, 30 s before su, so it is surely on disk if the exploit crashes.
                writeMarker(app);
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
        clearMarker(app);  // root started some other way; no exploit run by us
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
        writeMarker(app);  // already written at check 1; again in case it was cleared since
        log(app, true, "su is there (check 2 of 2). Starting root and the filter (runs Root Manager's exploit once):");
        run(app, true, "");
    }

    private void run(Context app, boolean startRoot, String header) {
        PendingResult pending = goAsync();
        BootNotifier.starting(app, startRoot);
        new Thread(() -> {
            StringBuilder out = new StringBuilder(header);
            try {
                File dir = RootShell.unpack(app);
                StringBuilder last = new StringBuilder();
                int code = RootShell.run(app, dir, RootActivity.ROOT_START, startRoot, line -> {
                    out.append(line).append('\n');
                    String t = line.trim();
                    if (!t.isEmpty()) {
                        last.setLength(0);
                        last.append(t);  // the last line says why, if it failed
                    }
                });
                out.append("exit ").append(code);
                if (startRoot && code == 0) clearFlag(askFirst(app));
                BootNotifier.result(app, code == 0, last.length() > 0 ? last.toString() : "exit " + code);
            } catch (IOException e) {
                out.append("Couldn't unpack the app's files: ").append(e.getMessage());
                BootNotifier.result(app, false, e.getMessage());
            } finally {
                if (startRoot) clearMarker(app);
                log(app, true, out.toString());
                pending.finish();
            }
        }).start();
    }

    /** After a crashed start: opens BootPromptActivity at about 5 minutes after power-on. */
    private static void schedulePrompt(Context c) {
        Intent i = new Intent(c, BootPromptActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        long at = Math.max(PROMPT_AT_UPTIME_MS, SystemClock.elapsedRealtime() + 10 * 1000);
        am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
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

    private static File marker(Context c) {
        return new File(c.getFilesDir(), ATTEMPT_MARKER);
    }

    /** Present once a boot-time root start has crashed the phone, until a start works. */
    private static File askFirst(Context c) {
        return new File(c.getFilesDir(), ASK_FIRST_MARKER);
    }

    /** The prompt's Start: same crash guard as the automatic start. */
    static void promptStartBegins(Context c) {
        writeMarker(c);
    }

    static void promptStartEnded(Context c, boolean ok) {
        clearMarker(c);
        if (ok) clearFlag(askFirst(c));
    }

    /**
     * The crash guard. A plain file, fsynced together with its directory: the v1.13 guard
     * (a SharedPreferences value) came back with its old value after the exploit crashed
     * the phone, so the next boot tried again. If this file is still there at boot, the
     * last automatic start never finished and the app asks instead of trying again.
     */
    private static void writeMarker(Context c) {
        writeFlag(marker(c));
    }

    private static void clearMarker(Context c) {
        clearFlag(marker(c));
    }

    /** Creates f and fsyncs it and its directory, so it survives a crash right after. */
    private static void writeFlag(File f) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write('1');
            out.getFD().sync();
        } catch (IOException ignored) {
            // Nothing better to do at boot; the log shows the attempt.
        }
        syncDir(f.getParentFile());
    }

    private static void clearFlag(File f) {
        if (f.delete()) syncDir(f.getParentFile());
    }

    private static void syncDir(File dir) {
        try {
            FileDescriptor fd = Os.open(dir.getPath(), OsConstants.O_RDONLY, 0);
            try {
                Os.fsync(fd);
            } finally {
                Os.close(fd);
            }
        } catch (ErrnoException ignored) {
            // The file's own sync above still happened.
        }
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
