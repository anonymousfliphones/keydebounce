# keydebounce

Fixes double key presses on the **Sonim XP3800** keypad (Android 8.1).

## The problem

On every XP3800, typing quickly produces doubled digits and letters in every app. Two separate causes were confirmed on the device:

| Problem | What happens | Example |
| --- | --- | --- |
| Key overlap | Pressing a key before fully releasing the previous one makes the stock firmware insert an extra copy of the second key | 5 then 6 → "566" |
| Contact bounce | A hard press registers a ghost second press about 10 ms after the key is released | 5 → "55" |

The keypad hardware and Android's input system both deliver clean presses. The extra digit from key overlap is added inside Sonim's built-in software, which can't be fixed directly, so this works around it before any app sees the keys.

## How it works

`keydebounce` is a small native daemon that sits between the keypad and Android:

1. It creates a replacement keypad device with the same name (`soc:matrix_keypad@0`), so Android uses the same key layout.
2. It takes exclusive control of the real keypad.
3. **Overlap:** when a new key goes down while another is held, it releases the held key first.
4. **Bounce:** it holds each release for 20 ms. A press of the same key inside that window is treated as the same press.

If the daemon stops for any reason, the keypad goes straight back to working normally (unfiltered). It logs every correction to logcat under the tag `keydebounce`.

**Tradeoff:** you can't hold two keys at once. Holding a single key (long press) still works.

## The app

KeyDebounce is an Android app that installs, removes and checks the fix on the phone. Every screen works with the keypad; no touchscreen needed.

| Button | What it does | Root |
| --- | --- | --- |
| Install fix | Backs up the stock policy, dry-runs the policy compile, then installs. **Dry run only** checks without changing anything. | Yes, for the install only |
| Undo (remove fix) | Restores the stock policy from the backup and removes the daemon | Yes |
| Turn off / on | Shows the adb commands for the off switch. With root it can switch now, without a restart. | Only for "now" |
| Key tester | Lists every key press and release with timing, and flags overlaps, fast repeats (bounce) and double presses | No |
| Correction log | Counts the overlap and bounce corrections from logcat | No, but needs a one-time adb grant |

The main screen shows whether the fix is on. When it's running, Android lists two `soc:matrix_keypad@0` keypads: the real one and the daemon's replacement.

The app asks for root only when you pick Install, Undo or an "(root)" button. It never asks at startup or boot (see the warning below).

### Get the app

1. On GitHub, open **Actions** → **Build app** → the latest green run, and download the `keydebounce-apk` artifact. It's a zip that contains `keydebounce-debug.apk`.
2. Install it: `adb install keydebounce-debug.apk`
3. For the correction log, once: `adb shell pm grant io.github.anonymousfliphones.keydebounce android.permission.READ_LOGS`

Each build is signed with a new debug key, so to update, uninstall the old app first (`adb uninstall io.github.anonymousfliphones.keydebounce`), then install and grant again.

The same run also has a `keydebounce-daemon` artifact: the daemon binary and `sepolicy/` scripts for installing by hand.

### Install with the app

1. Back up the system partition, and have EDL flashing ready.
2. Get root.
3. Open KeyDebounce → **Install fix**. It stops before changing anything if the policy isn't stock, a compile fails, or the stock compile doesn't match the phone's precompiled policy.
4. Copy `/sdcard/keydebounce-backup` to your computer. Undo needs it.
5. Remove root, or disable apps that ask for root at startup (see the warning below).
6. Restart the phone. The main screen should say **Fix is ON**.

Every root command's output is saved to `/sdcard/Android/data/io.github.anonymousfliphones.keydebounce/files/<command>.log`.

## Requirements

- Sonim XP3800 on Android 8.1
- Root, only for installing and uninstalling. It runs without root afterwards.
- A backup of the system partition, and an EDL flashing setup for the XP3800 in case the phone won't boot

## Install by hand (permanent, runs without root)

Android enforces SELinux at boot on this phone, so the daemon gets its own small security rule. It's added to the phone's policy, and Android recompiles the policy at every boot.

1. Back up the system partition and `/system/etc/selinux/plat_sepolicy.cil` + `plat_and_mapping_sepolicy.cil.sha256`.
2. Copy `keydebounce`, `sepolicy/keydebounce.cil`, `sepolicy/keydebounce.rc`, `sepolicy/dryrun.sh`, and `sepolicy/install.sh` to `/data/local/tmp/kd_dry/` on the phone.
3. As root, run `sh /data/local/tmp/kd_dry/dryrun.sh`. Both compiles must print `exit=0`. If not, stop; nothing has been changed.
4. As root, run `sh /data/local/tmp/kd_dry/install.sh`.
5. Reboot. Check it's running with `adb shell ps -A -Z | grep keydebounce` (you should see `u:r:keydebounce:s0`).

> **Warning: remove root, or disable apps that ask for root at startup (such as Lucky Patcher), before rebooting.**
> With this installed, the Root Manager root exploit crashed the phone on almost every attempt during testing. An app that requests root at boot then puts the phone into a reboot loop.

## Turn it off

No root needed:

```
adb shell touch /data/local/tmp/keydebounce.off
adb reboot
```

Delete that file and reboot to turn it back on.

## Uninstall

Needs root. In the app: **Undo (remove fix)**, then restart. By hand: copy the backed-up stock policy files to `/data/local/tmp/kd_dry/stock/`, run `sepolicy/uninstall.sh` as root, then reboot.

If the phone won't boot, flash the system backup over EDL.

## Build

Android NDK r27, 32-bit ARM:

```
armv7a-linux-androideabi21-clang -O2 -Wall -Wextra -o keydebounce keydebounce.c -llog
```

The app (Android SDK plus NDK 27.0.12077973, JDK 17+):

```
./gradlew assembleDebug
```

The build compiles `keydebounce.c` with the same command and bundles it and `sepolicy/` into the APK, at `app/build/outputs/apk/debug/keydebounce-debug.apk`. GitHub Actions (`.github/workflows/build-app.yml`) runs this on every push.

## Testing

`test/harness.sh` reproduces the bug without typing. It injects key presses through the real keypad device and reads the dialer's digits field back. Open the dialer first.

```
sh /data/local/tmp/harness.sh /data/local/tmp/inject_56_overlap.sh 3
```

| Pattern | Without the fix (automated, 3 runs) | With the fix |
| --- | --- | --- |
| Overlapped (5 down, 6 down, 5 up, 6 up) | 566, 566, 566 | 56 when typed by hand (8 overlaps corrected); the automated run with the fix hasn't been completed |
| Back-to-back / separated | 56, 56, 56 | 56 |

## Known issues

- The mouse service (MATVT) crashed twice while the daemon ran alongside automated screenshots. It didn't happen in normal use.
- Root exploit crashes with the policy installed (see the warning above).
