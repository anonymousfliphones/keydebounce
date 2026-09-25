package io.github.anonymousfliphones.keydebounce;

import android.os.Build;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Read-only checks of the phone. Nothing here runs su: with the fix installed,
 * asking for root crashed the phone on almost every attempt, so root is only
 * used when the user picks a root action.
 */
final class PhoneStatus {
    static final String KEYPAD = "soc:matrix_keypad@0";
    static final String BINARY = "/system/bin/keydebounce";
    static final String RC = "/system/etc/init/keydebounce.rc";
    static final String POLICY = "/system/etc/selinux/plat_sepolicy.cil";
    static final String MAPPING = "/system/etc/selinux/mapping/27.0.cil";

    boolean binaryInstalled;
    boolean rcInstalled;
    boolean policyInstalled;
    /** init.svc.keydebounce: "running", "stopped", or "" if unknown. */
    String service = "";
    /** Input devices named KEYPAD. The daemon's replacement device makes it 2. */
    int keypads;
    boolean suFound;
    /** Sonim XP3800 on Android 8.1: the only phone the installer is for. */
    boolean supported;

    static PhoneStatus read() {
        PhoneStatus s = new PhoneStatus();
        s.binaryInstalled = new File(BINARY).exists();
        s.rcInstalled = new File(RC).exists();
        s.policyInstalled = policyHasKeydebounce();
        s.service = systemProperty("init.svc.keydebounce");
        s.keypads = countKeypads();
        s.suFound = findSu();
        s.supported = Build.VERSION.SDK_INT == 27 && s.keypads > 0 && new File(MAPPING).exists();
        return s;
    }

    boolean installed() {
        return binaryInstalled && rcInstalled && policyInstalled;
    }

    boolean partlyInstalled() {
        return !installed() && (binaryInstalled || rcInstalled || policyInstalled);
    }

    boolean filtering() {
        return keypads >= 2;
    }

    static int countKeypads() {
        int n = 0;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && KEYPAD.equals(d.getName())) n++;
        }
        return n;
    }

    static boolean policyHasKeydebounce() {
        try (BufferedReader r = new BufferedReader(new FileReader(POLICY))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("keydebounce")) return true;
            }
        } catch (IOException e) {
            // Not readable or not there: treat as not installed.
        }
        return false;
    }

    private static String systemProperty(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class)
                    .invoke(null, key);
            return v == null ? "" : (String) v;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "";
        }
    }

    /** Looks for an su binary without running it. */
    private static boolean findSu() {
        Set<String> dirs = new LinkedHashSet<>(Arrays.asList(
                "/system/bin", "/system/xbin", "/sbin", "/su/bin", "/system/sbin", "/vendor/bin"));
        String path = System.getenv("PATH");
        if (path != null) dirs.addAll(Arrays.asList(path.split(":")));
        for (String d : dirs) {
            if (!d.isEmpty() && new File(d, "su").exists()) return true;
        }
        return false;
    }
}
