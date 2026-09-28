# keydebounce

Fixes double key presses on the **Sonim XP3800 Verizon Variant** keypad (Android 8.1).

> [!CAUTION]
> **Use at your own risk.** This project changes low-level parts of the phone. The permanent install rewrites the SELinux security policy in `/system` and adds a system-level boot service. Root mode runs a daemon as root that takes over the keypad input device.
>
> A mistake or an unexpected firmware difference can leave the phone unable to boot. Recovering may need an EDL flash of a system backup you made beforehand. Getting root, which this requires, can itself crash the phone or cause reboot loops. It may also void your warranty and weaken the phone's security.
>
> **The developers take no responsibility** for damaged or unbootable phones, lost data, or anything else that results from using this project. It is provided "as is", without warranty of any kind. See [LICENSE](LICENSE), sections 15 and 16. Don't use it unless you have a full backup and understand how to recover.

> [!WARNING]
> **The on-phone `.bak` backup and restore have never been tested.** The `.bak` backup in `sepolicy/install.sh` and the restore in `sepolicy/uninstall.sh` were written after the only real install, and have never been run on a phone. That install used an earlier script that kept the stock policy backup off the phone. Keep your own copy of the stock policy files and a full system backup before relying on them.

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
| Install fix | Backs up the stock policy, dry-runs the policy compile, then installs. **Dry run only** checks without changing anything. | Yes, for the install only |
| Undo (remove fix) | Restores the stock policy from the backup and removes the daemon | Yes |
| Turn off / on | Shows the adb commands for the off switch. With root it can switch now, without a restart. | Only for "now" |
| Run with root (no install) | Starts the daemon through `su`, like Shizuku starts its server. Nothing in `/system` or the policy changes, and it stops at restart unless **Start at boot** is on. | Yes, every start |
| Bounce filter (no root) | Shows the adb commands to turn on an accessibility-service fallback for phones with no root at all. **Untested.** Only fixes contact bounce, not key overlap — see below. | No |
| Key tester | Lists every key press and release with timing, and flags overlaps, fast repeats (bounce) and double presses | No |
| Correction log | Counts the overlap and bounce corrections from logcat | No, but needs a one-time adb grant |

The main screen shows whether the fix is on. When it's running, Android lists two `soc:matrix_keypad@0` keypads: the real one and the daemon's replacement.

The app asks for root only when you pick Install, Undo, root mode or an "(root)" button, or at boot if you turned on root mode's **Start at boot**. Start at boot is off by default and is always skipped when the permanent install is present (see the warning below).

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
4. The stock policy is now backed up as `.bak` files in `/system/etc/selinux/`; Undo restores from them. Nothing is saved to `/data` or the SD card. (Untested, see the warning at the top.)
5. Remove root, or disable apps that ask for root at startup (see the warning below).
6. Restart the phone. The main screen should say **Fix is ON**.

### Root mode (no install)

If the phone is rooted with root that lets `su` use `/dev/input` and `/dev/uinput` (Magisk does), **Run with root (no install)** → **Start now** runs the filter with no changes to `/system` or the SELinux policy. **Stop** ends it, and a restart ends it too. To start it after every restart, turn on **Start at boot**. The app then asks for root once the phone has booted. That boot attempt's output is saved as `boot-start.log`, next to the other logs listed below.

If the root doesn't allow the keypad or `/dev/uinput`, the daemon exits right away and the app shows its log line. In that case use the permanent install. Starting the daemon over ADB (like Shizuku without root) can't work: the `adb shell` user can't open `/dev/uinput`.

Every root command's output is saved to `/sdcard/Android/data/io.github.anonymousfliphones.keydebounce/files/<command>.log`.

#### How to use the "Boot Complete" method (with root)

If you do not want to touch the SELinux policy or `/system`, you don't actually need to edit the code. You can use the app's built-in root mode:

1. Make sure your device is rooted (the root manager must allow `su` to access `/dev/uinput`).
2. In the KeyDebounce app, go to **Run with root (no install)**.
3. Toggle on **Start at boot**.

When you do this, the app uses a standard boot receiver to launch the daemon via `su` once the phone finishes booting. It will ask for root permissions upon startup, and it won't make any permanent changes to your SELinux policy or `/system` partition.

### Bounce filter (no root, accessibility service) — untested

> [!WARNING]
> **Untested.** `BounceFilterService` has not been run on a phone yet. It's added here because the mechanism is straightforward and well-documented (see below), not because it's been verified end to end.

For phones with no root at all, `BounceFilterService` is a fallback that only fixes **contact bounce** (5 → "55"), by having Android itself drop the ghost DOWN+UP pair before it reaches any app. It does **not** fix **key overlap** (5,6 → "566"), which is the main problem this project exists for — that fix needs `INJECT_EVENTS` to synthesize a release event that never happened, and normal apps (accessibility services included) can't get that permission. See ["Why not an accessibility service or a different keyboard?"](#why-not-an-accessibility-service-or-a-different-keyboard) above for why.

It can't be turned on from inside the app (Android doesn't let an app enable its own accessibility service). Turn it on from a computer, once:

```
adb shell settings put secure enabled_accessibility_services io.github.anonymousfliphones.keydebounce/io.github.anonymousfliphones.keydebounce.BounceFilterService
adb shell settings put secure accessibility_enabled 1
```

If you already use another accessibility service on this phone, don't run those two commands as-is — they replace the whole list. Add the service to the existing comma-separated list instead, or turn it on by hand in Settings ▸ Accessibility.

To turn it off: Settings ▸ Accessibility ▸ KeyDebounce, or from a computer:

```
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0
```

(again, only safe as a blanket command if this is the only accessibility service enabled).

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

**Untested:** this backup step has never been run on a phone. Also copy both files to your PC before installing.

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

**Untested:** the `.bak` restore has never been run on a phone. If it fails, restore the two stock files from your own copy, or flash the system backup over EDL.

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
