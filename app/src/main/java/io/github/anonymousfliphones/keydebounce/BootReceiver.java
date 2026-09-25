package io.github.anonymousfliphones.keydebounce;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Root mode's "Start at boot": after a restart, starts the daemon through su
 * (kd.sh rootstart). Off by default, and it never asks for root when the
 * permanent install is present: with its policy loaded, root requests crashed
 * the phone and caused reboot loops.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS = "settings";
    private static final String START_AT_BOOT = "start_at_boot";

    static boolean startAtBoot(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(START_AT_BOOT, false);
    }

    static void setStartAtBoot(Context c, boolean on) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putBoolean(START_AT_BOOT, on).apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        if (!startAtBoot(app)) return;
        PendingResult pending = goAsync();
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                if (PhoneStatus.policyHasKeydebounce()) {
                    out.append("Skipped: the fix is installed, and root requests with it installed crash the phone.\n");
                } else {
                    File dir = RootShell.unpack(app);
                    int code = RootShell.run(dir, RootActivity.ROOT_START, line -> out.append(line).append('\n'));
                    out.append("exit ").append(code).append('\n');
                }
            } catch (IOException e) {
                out.append("Couldn't unpack the app's files: ").append(e.getMessage()).append('\n');
            } finally {
                save(app, out.toString());
                pending.finish();
            }
        }).start();
    }

    /** Android/data/<package>/files/boot-start.log, readable with adb pull. */
    private static void save(Context c, String text) {
        File dir = c.getExternalFilesDir(null);
        if (dir == null) return;
        try (OutputStream out = new FileOutputStream(new File(dir, "boot-start.log"))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // Nothing else to report it to at boot.
        }
    }
}
