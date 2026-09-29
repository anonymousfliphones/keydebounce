# keydebounce

Fixes double key presses on the **Sonim XP3800 Verizon Variant** keypad (Android 8.1).

> [!CAUTION]
> **Use at your own risk.** This project changes low-level parts of the phone. The permanent install rewrites the SELinux security policy in `/system` and adds a system-level boot service. Root mode runs a daemon as root that takes over the keypad input device.
>
> A mistake or an unexpected firmware difference can leave the phone unable to boot. Recovering may need an EDL flash of a system backup you made beforehand. Getting root, which this requires, can itself crash the phone or cause reboot loops. It may also void your warranty and weaken the phone's security.
>
> **The developers take no responsibility** for damaged or unbootable phones, lost data, or anything else that results from using this project. It is provided "as is", without warranty of any kind. See [LICENSE](LICENSE), sections 15 and 16. Don't use it unless you have a full backup and understand how to recover.

> [!WARNING]
> **Only built and tested on the Verizon XP3800 variant.** Other carriers/firmware variants of the XP3800 almost certainly ship a different base SELinux policy, and `keydebounce.cil` was written against Verizon's. `sepolicy/install.sh` now refuses to proceed if this device's compiled stock policy doesn't match its own `/vendor/etc/selinux/precompiled_sepolicy` (the same check `dryrun.sh` reports, but enforced as a hard stop instead of an FYI line) — so an install on a mismatched variant should fail cleanly rather than half-apply. That check isn't a guarantee: it can only catch a policy that's structurally different, not one that's different in some subtler way that still compiles. Don't try this on a non-Verizon variant without a full system backup and EDL recovery ready.

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

### Why you can't just use a standard BOOT_COMPLETED receiver (without root)

To fix the key overlap issue, this daemon has to do two things that normal Android apps are strictly blocked from doing:

1. Take exclusive control of the physical keypad hardware (`/dev/input`).
2. Create a fake replacement keypad to send the filtered keystrokes to Android (`/dev/uinput`).

Standard Android apps do not have permission to access these low-level device files. The permanent install method rewrites the SELinux policy specifically to grant this single daemon a permanent exception so it can run without needing root later.

### Why not an accessibility service or a different keyboard?

Both would work without root, and both were considered:

- **A different keyboard (IME):** tested with TT9 in place of Sonim's keyboard, and the extra digit still appeared. The copy is added below the keyboard app, so replacing the keyboard doesn't help.
- **An accessibility service:** with key filtering on, it sees each key before the app, but it can only let a key through or swallow it. It can't create a key event, because that needs `INJECT_EVENTS`, a permission only system apps get. It also can't hold a key back: it has to answer right away, and Android passes the key on after about 500 ms anyway.

  The overlap fix depends on exactly what it can't do: sending a "key released" event for the held key *before* the next key goes down. Its only options are swallowing the new key (the digit is lost) or swallowing the old key's release (apps think the key is still held, and the dialer's tone can keep playing). It could remove bounce ghosts, but not the "566" doubling, which is the main problem.

Fixing the overlap needs something that owns the keypad device and can create the replacement keypad through `/dev/uinput`. That takes root, or the SELinux rule the permanent install adds.

## The app

KeyDebounce is an Android app that installs, removes and checks the fix on the phone. Every screen works with the keypad; no touchscreen needed.

| Button | What it does | Root |
| --- | --- | --- |
| Install fix | Backs up the stock policy, dry-runs the policy compile, installs, then checks the `.bak` backup and the installed files. **Dry run only** checks without changing anything. Once installed, and before the first restart, **Check install** reruns the final checks. | Yes, for the install and the check only |
| Undo (remove fix) | Restores the stock policy from the backup and removes the daemon | Yes |
| Turn off / on | Shows the adb commands for the off switch. With root it can switch now, without a restart. | Only for "now" |
| Run with root (no install) | Starts the daemon through `su`, like Shizuku starts its server. Nothing in `/system` or the policy changes, and it stops at restart unless **Start at boot** is on. | Yes, every start |
| Boot notification | Turns on or off a silent, clearable notification when Start at boot starts the filter: "starting", then whether it came on. Off by default. | No |
| Bounce filter (no root) | Shows the adb commands to turn on an accessibility-service fallback for phones with no root at all. Tested: fixes contact bounce, not key overlap — see below. | No |
| Key tester | Lists every key press and release with timing, and flags overlaps, fast repeats (bounce) and double presses | No |
| Correction log | Counts the overlap and bounce corrections from logcat | No, but needs a one-time adb grant |

The main screen shows whether the fix is on. When it's running, Android lists two `soc:matrix_keypad@0` keypads: the real one and the daemon's replacement.

The app asks for root only when you pick Install, Undo, root mode or an "(root)" button, or at boot if you turned on root mode's **Start at boot**. Start at boot is off by default and is always skipped when the permanent install is present (see the warning below).

### Get the app

1. On GitHub, open **Actions** → **Build app** → the latest green run, and download the `keydebounce-apk` artifact. It's a zip that contains `keydebounce-debug.apk`.
2. Install it: `adb install keydebounce-debug.apk`
3. For the correction log, once: `adb shell pm grant com.anonymousfliphones.keydebounce android.permission.READ_LOGS`

Each build is signed with a new debug key, so to update, uninstall the old app first (`adb uninstall com.anonymousfliphones.keydebounce`), then install and grant again.

Since v1.18 the package name is `com.anonymousfliphones.keydebounce`. Older builds used `io.github.anonymousfliphones.keydebounce`, which Android treats as a different app, so remove it with `adb uninstall io.github.anonymousfliphones.keydebounce`. The permanent install on `/system` isn't affected.

The same run also has a `keydebounce-daemon` artifact: the daemon binary and `sepolicy/` scripts for installing by hand.

### Install with the app

1. Back up the system partition, and have EDL flashing ready.
2. Get root.
3. Open KeyDebounce → **Install fix**. It stops before changing anything if the policy isn't stock, a compile fails, or the stock compile doesn't match the phone's precompiled policy.
4. The stock policy is now backed up as `.bak` files in `/system/etc/selinux/`; Undo restores from them. Nothing is saved to `/data` or the SD card. The install ends by checking the `.bak` files are the stock policy with the stock label, then that `/system` holds what it installed.
5. Remove root, or disable apps that ask for root at startup (see the warning below).
6. Restart the phone. The main screen should say **Fix is ON**.

> [!IMPORTANT]
> **Installed with the app before v1.5? The fix never ran.** The app's root shell runs at the app's SELinux level (`s0:c512,c768`), and files it creates on `/system` inherit that level. The daemon runs at `s0`, which can't even `stat` a file at that level, so it crashed at every boot before `main()`: logcat shows `Abort message: 'unable to stat "/proc/self/exe": Permission denied'`. Rebooting doesn't help. To fix it: install v1.5 or later, **Undo**, restart, then **Install** again. v1.5 sets the labels explicitly and refuses to install if they don't come out right. To check by hand: `adb shell ls -Z /system/bin/keydebounce` must show `u:object_r:system_file:s0` with nothing after `s0`.
>
> **Installed with v1.9 or earlier and it said "failed" after "Installed. Reboot to start it."?** The install finished, but a last try at making `/system` read-only as the script exited was refused as busy, and that failed the script before the app's final checks ran, including the `.bak` check. Don't restart yet: install v1.10 or later, press **Install fix** → **Check install**, and restart only once it says the install checks out.
>
> The **Restart** button didn't work before v1.5 either, because the app's root shell isn't allowed to set `sys.powerctl`. Since v1.5 it asks the system to restart instead (`svc power reboot`), which was tested and works. Before v1.6 the D-pad couldn't reach Restart or Close on the result screen; v1.6 fixes that (not yet tested on a phone).

> [!WARNING]
> **With Root Manager (the XP3800 root, `com.flipphoneguy.root.xp3`), the first `su` after every restart runs a kernel exploit.** `su` stays installed in `/system/bin`, but after each restart the first root request runs the exploit ([how it works](https://github.com/flipphoneguy/root-sonim-xp3800/blob/main/docs/daemon.md)). That takes about a second and can crash the phone. Then it turns SELinux off and starts a daemon, and every later `su` is instant and safe until the next restart. Opening the Root Manager app doesn't run `su`. With **Start at boot** on, running that first `su` at boot crashed the phone at every boot (a reboot loop), and with the permanent install's policy loaded the exploit crashed on almost every attempt. So the app checks first, by reading a file (the exploit turns SELinux off, and only then can the app read it). If root hasn't started since the restart, root actions say "Root hasn't started yet" and offer **Start root**, which runs the exploit only once you confirm (v1.11+; before that they said "Root isn't active" and told you to open the root app, which did nothing). With **Start at boot** on (v1.16+), about 5 minutes after power-on, once `su` has been there on two checks in a row, the app starts root and the filter by itself, no prompt. The wait is because Root Manager's author says the exploit panics much more often while the phone is busy, especially right after boot; on an XP3800 the load after boot peaks around 2 minutes after power-on and is back near idle by about 5. Apps can't read the load with root off, so it's a fixed wait. A marker file written and flushed to storage 30 seconds before that `su`, and deleted after it, catches a crash: if the exploit crashes the phone, every boot after that asks first (**Start** / **Not now**) instead of trying by itself, until a start works (the prompt's Start has the same guard), so it can't become a reboot loop. If something else uses `su` first (Termux, `adb shell su`), the filter starts 30 to 60 seconds later.

### Root mode (no install)

If the phone is rooted with root that lets `su` use `/dev/input` and `/dev/uinput` (Magisk does), **Run with root (no install)** → **Start** runs the filter with no changes to `/system` or the SELinux policy. **Stop** ends it, and a restart ends it too. To start it after every restart, use **Boot** → **Turn on** (Start at boot). It starts the filter by itself after a restart, with nothing to tap. With a root that's on at boot (Magisk) it starts right away. With Root Manager, whose first `su` after a restart runs its kernel exploit (see above), about 5 minutes after power-on, once `su` has been there on two checks in a row, the app starts root (running the exploit once) and the filter by itself. If that crashed the phone on the previous boot, it asks first instead (see the warning above). Turning Start at boot on asks **Restart now** / **Later**, since it only acts after a restart. Meanwhile it checks every 30 seconds from boot, without asking for root, whether something else has started root, and starts once root has been up for two checks in a row: 30 to 60 seconds after the first `su`. The second check keeps it from calling `su` while the exploit is still running. What it did is saved as `boot-start.log`, next to the other logs listed below. To also get a notification when it starts the filter, use **Boot notification** on the main screen (v1.17+, off by default): a silent notification says it's starting, then whether it came on or why not. You can swipe it away.

If the root doesn't allow the keypad or `/dev/uinput`, the daemon exits right away and the app shows its log line. In that case use the permanent install. Starting the daemon over ADB (like Shizuku without root) can't work: the `adb shell` user can't open `/dev/uinput`.

Every root command's output is saved to `/sdcard/Android/data/com.anonymousfliphones.keydebounce/files/<command>.log`.

#### How to use the "Boot Complete" method (with root)

If you do not want to touch the SELinux policy or `/system`, you don't actually need to edit the code. You can use the app's built-in root mode:

1. Make sure your device is rooted (the root manager must allow `su` to access `/dev/uinput`).
2. In the KeyDebounce app, go to **Run with root (no install)**.
3. Choose **Boot** → **Turn on**.

When you do this, the app uses a standard boot receiver: it launches the daemon via `su` once the phone finishes booting (with Root Manager, once something else has started root, see above). It never runs Root Manager's exploit at boot, and it won't make any permanent changes to your SELinux policy or `/system` partition.

### Bounce filter (no root, accessibility service)

**Tested on a Verizon XP3800 (v1.3 with the manifest fix from PR #9),** using `test/harness.sh` in the dialer:

| Pattern | Filter off | Filter on |
| --- | --- | --- |
| Bounce (5 + ghost press, `inject_55_bounce.sh`) | 55, 55, 55 | **5, 5, 5** |
| Overlap (5,6, `inject_56_overlap.sh`) | 566, 566, 566 | 566, 566, 566 (not fixable this way, see below) |
| Normal typing (`inject_56.sh`) | 56 | 56, 56, 56 |

It ran alongside Mouse Toggle (MATVT), Button Mapper and Voice Access with no crashes. The bounce test injects the ghost press about 0 ms after the release; a bounce from a real finger hasn't been tested yet, and neither has the filter's effect on very fast same-key double taps (its window is 20 ms; the fastest real double tap measured was 45 ms).

For phones with no root at all, `BounceFilterService` is a fallback that only fixes **contact bounce** (5 → "55"), by having Android itself drop the ghost DOWN+UP pair before it reaches any app. It does **not** fix **key overlap** (5,6 → "566"), which is the main problem this project exists for — that fix needs `INJECT_EVENTS` to synthesize a release event that never happened, and normal apps (accessibility services included) can't get that permission. See ["Why not an accessibility service or a different keyboard?"](#why-not-an-accessibility-service-or-a-different-keyboard) above for why.

Turn it on in **Settings ▸ Accessibility ▸ KeyDebounce**, then restart. (The app's Bounce filter screen has an **Accessibility** button that opens that list.)

Or from a computer:

```
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services com.anonymousfliphones.keydebounce/.BounceFilterService
adb reboot
```

That command **replaces** the list of accessibility services that are on. If others are on, include them too, separated by colons (`:`). See the list with `adb shell settings get secure enabled_accessibility_services`. For example, with Mouse Toggle already on:

```
adb shell settings put secure enabled_accessibility_services com.android.cts.io.github.virresh.matvt/.services.MouseEventService:com.anonymousfliphones.keydebounce/.BounceFilterService
```

To add it without retyping the others (PowerShell, or a Linux/Mac terminal):

```
adb shell 'S=com.anonymousfliphones.keydebounce/.BounceFilterService; L=$(settings get secure enabled_accessibility_services); case "$L" in null|"") L=$S;; *BounceFilterService*) ;; *) L="$L:$S";; esac; settings put secure enabled_accessibility_services "$L"; settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services'
adb reboot
```

Things this phone's firmware does that you need to know:

- **Restart after turning it on or off.** Changing the list doesn't take effect until a restart. Before that, the filter isn't running even though the setting lists it.
- **Reinstalling or updating the app removes it from the list.** Each build is signed with a new debug key, so an update means uninstall + install, and Android drops the service from the list at the next restart. Turn it on again (Settings or a command above), then restart.
- **Check it's really on:** `adb shell dumpsys accessibility | grep -A3 KeyDebounce` should show `capabilities=8`. `capabilities=0` means Android didn't load the filter's settings (the bug fixed in PR #9).

To turn it off: Settings ▸ Accessibility ▸ KeyDebounce, or from a computer (removes only this service from the list):

```
adb shell 'L=$(settings get secure enabled_accessibility_services | sed "s#:*com.anonymousfliphones.keydebounce/[^:]*##; s#^:##"); settings put secure enabled_accessibility_services "$L"; settings get secure enabled_accessibility_services'
adb reboot
```

## Requirements

- Sonim XP3800 on Android 8.1
- Root, only for installing and uninstalling. It runs without root afterwards.
- A backup of the system partition, and an EDL flashing setup for the XP3800 in case the phone won't boot

## Install by hand (permanent, runs without root)

Android enforces SELinux at boot on this phone, so the daemon gets its own small security rule. It's added to the phone's policy, and Android recompiles the policy at every boot.

1. Back up the system partition, for EDL recovery if the phone ever fails to boot.
2. Copy `keydebounce`, `sepolicy/keydebounce.cil`, `sepolicy/keydebounce.rc`, `sepolicy/dryrun.sh`, and `sepolicy/install.sh` to `/data/local/tmp/kd_dry/` on the phone. These are only the install inputs; nothing is backed up there.
3. As root, run `sh /data/local/tmp/kd_dry/dryrun.sh`. Both compiles must print `exit=0`, and the baseline must match the phone's prebuilt policy. If not, stop; nothing has been changed.
4. As root, run `sh /data/local/tmp/kd_dry/install.sh`. It checks the stock policy against its hash, compiles once more, and stops before touching `/system` if anything fails.
5. Reboot. Check it's running with `adb shell ps -A -Z | grep keydebounce` (you should see `u:r:keydebounce:s0`).

The installer backs up the two stock policy files **on the phone, next to the originals**:

| Backup | Original |
| --- | --- |
| `/system/etc/selinux/plat_sepolicy.cil.bak` | `plat_sepolicy.cil` |
| `/system/etc/selinux/plat_and_mapping_sepolicy.cil.sha256.bak` | `plat_and_mapping_sepolicy.cil.sha256` |

They're made on the first install only and never overwritten, so they always hold the stock files. Reinstalling rebuilds from the `.bak` copy, so the rule is never added twice. Android ignores `.bak` files, and they survive factory resets because they're in `/system`.

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

Needs root. In the app: **Undo (remove fix)**, then restart. By hand: copy `sepolicy/uninstall.sh` to the phone, run it as root, then reboot.

`uninstall.sh` restores from the first stock backup it finds:

1. The `.bak` files in `/system/etc/selinux/`, made by the current installer.
2. Otherwise, `/data/local/tmp/kd_dry/stock/`. Phones installed before the `.bak` backup existed only have this (put the two stock files there with `adb push`).

It checks the backup against its hash before changing anything, puts the stock files back, and deletes the daemon and its startup entry. The phone then goes back to loading its prebuilt policy, exactly as before the install.

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

## License

Copyright (C) 2026 the keydebounce contributors.

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text.
