package io.github.anonymousfliphones.keydebounce;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Runs the bundled assets/kd/kd.sh as root. */
final class RootShell {
    interface Output {
        void line(String s);
    }

    /** Exit code when su couldn't be started or refused the command. */
    static final int NO_ROOT = -1;
    /** Exit code when su wasn't called because Root Manager's root hasn't started this boot. */
    static final int ROOT_NOT_STARTED = -2;

    /**
     * Root Manager, the XP3800 root. Its su stays in /system/bin for good, but the first su
     * after each boot runs its kernel exploit (CVE-2019-2215, about a second), which can
     * crash the phone; at boot that became a reboot loop. The exploit turns SELinux off
     * and starts a daemon that serves every later su with no kernel risk, until the next
     * restart. Opening the Root Manager app doesn't run su.
     */
    static final String XP3_ROOT = "com.flipphoneguy.root.xp3";

    /**
     * True when Root Manager is installed and nothing has run su since this boot, so the
     * next su runs the exploit. This app can read the policy file only once the exploit
     * has turned SELinux off. Other roots aren't checked.
     */
    static boolean rootNotStarted(Context c) {
        if (!isXp3RootInstalled(c)) return false;
        try (FileInputStream in = new FileInputStream(PhoneStatus.POLICY)) {
            in.read();
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    static boolean isXp3RootInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(XP3_ROOT, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static final String EXIT_MARK = "__kd_exit=";

    /** Copies assets/kd/* (kd.sh, the daemon, sepolicy/*) to files/kd and returns that directory. */
    static File unpack(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), "kd");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't create " + dir);
        AssetManager assets = c.getAssets();
        String[] names = assets.list("kd");
        if (names == null || names.length == 0) throw new IOException("the app has no bundled files");
        byte[] buf = new byte[64 * 1024];
        for (String name : names) {
            try (InputStream in = assets.open("kd/" + name);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        return dir;
    }

    /**
     * Runs "sh kd.sh command dir" through su, passing each output line to out.
     * Blocks until it finishes; call from a background thread. Returns kd.sh's
     * exit code, NO_ROOT, or ROOT_NOT_STARTED (su not called).
     */
    static int run(Context c, File dir, String command, Output out) {
        return run(c, dir, command, false, out);
    }

    /** startRoot: the user agreed to start Root Manager's root, exploit and all, if needed. */
    static int run(Context c, File dir, String command, boolean startRoot, Output out) {
        if (!startRoot && rootNotStarted(c)) return ROOT_NOT_STARTED;
        Process p;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(true).start();
        } catch (IOException e) {
            out.line("Couldn't start su: " + e.getMessage());
            return NO_ROOT;
        }
        String script = new File(dir, "kd.sh").getPath();
        try (Writer w = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)) {
            w.write("sh " + quote(script) + " " + command + " " + quote(dir.getPath()) + "\n");
            w.write("echo " + EXIT_MARK + "$?\n");
            w.write("exit\n");
        } catch (IOException e) {
            // su closed its input (denied or crashed); its output below says why.
        }
        int code = NO_ROOT;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith(EXIT_MARK)) {
                    try {
                        code = Integer.parseInt(line.substring(EXIT_MARK.length()).trim());
                    } catch (NumberFormatException ignored) {
                        // keep NO_ROOT
                    }
                } else {
                    out.line(line);
                }
            }
        } catch (IOException e) {
            out.line("Reading su output failed: " + e.getMessage());
        }
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return code;
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
