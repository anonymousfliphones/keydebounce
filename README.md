# keydebounce

Fixes double key presses on the **Sonim XP3800 Verizon Variant** keypad (Android 8.1).

> [!CAUTION]
> **Use at your own risk.** This project changes low-level parts of the phone. The permanent install rewrites the SELinux security policy in `/system` and adds a system-level boot service. Root mode runs a daemon as root that takes over the keypad input device.
>
> A mistake or an unexpected firmware difference can leave the phone unable to boot. Recovering may need an EDL flash of a system backup you made beforehand. Getting root, which this requires, can itself crash the phone. It may also void your warranty and weaken the phone's security.
>
> **The developers take no responsibility** for damaged or unbootable phones, lost data, or anything else that results from using this project. It is provided "as is", without warranty of any kind. See [LICENSE](LICENSE), sections 15 and 16. Don't use it unless you have a full backup and understand how to recover.

> [!WARNING]
> **Only built and tested on the Verizon XP3800.** Other carrier or firmware variants almost certainly ship a different base SELinux policy. The installer refuses to go ahead if the phone's compiled stock policy doesn't match its own precompiled policy, so a mismatched variant should fail cleanly. That check can't catch every difference, so don't try another variant without a full system backup and EDL recovery ready.

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

There are two ways to run it:

- **Permanent install:** the daemon goes into `/system` with its own SELinux rule and starts at every boot, with no root needed after installing.
- **Root mode:** the daemon runs through root, with no changes to `/system`, until the next restart (or at every boot with Start at boot).

### Why it needs root or the permanent install

The daemon has to take exclusive control of the keypad (`/dev/input`) and create the replacement keypad (`/dev/uinput`). Normal apps can't touch either, so it runs either through root or with the SELinux rule the permanent install adds.

A different keyboard or an accessibility service can't fix the overlap:

- **A different keyboard (IME):** the extra digit is added below the keyboard app; it still appeared with TT9 in place of Sonim's keyboard.
- **An accessibility service** can only let a key through or swallow it. The overlap fix needs to send a "key released" event for the held key before the next key goes down, and creating key events needs `INJECT_EVENTS`, which only system apps get. It can remove bounce ghosts, though: that's the app's [bounce filter](#bounce-filter-no-root).

## Requirements

- Sonim XP3800 (Verizon) on Android 8.1
- Root, for installing and uninstalling the permanent install (it runs without root afterwards), or for root mode
- A backup of the system partition, and an EDL flashing setup for the XP3800 in case the phone won't boot

## Get the app

1. Download `keydebounce-vX.apk` from the [latest release](https://github.com/anonymousfliphones/keydebounce/releases/latest).
2. Install it, and give it log access for the correction log:

   ```
   adb install keydebounce-vX.apk
   adb shell pm grant com.anonymousfliphones.keydebounce android.permission.READ_LOGS
   ```

   Without a computer, the app offers **Grant with root** for log access instead, on first open and on the Correction log screen.

Each release is signed with a new debug key, so to update, uninstall the old app first, then install and grant again:

```
adb uninstall com.anonymousfliphones.keydebounce
adb install keydebounce-vX.apk
adb shell pm grant com.anonymousfliphones.keydebounce android.permission.READ_LOGS
```

Older versions used the package name `io.github.anonymousfliphones.keydebounce`, which Android treats as a different app; remove it with `adb uninstall io.github.anonymousfliphones.keydebounce`. Uninstalling the app doesn't affect the permanent install on `/system`.

Each release also has a `keydebounce-daemon-vX.zip`: the daemon binary and `sepolicy/` scripts for installing by hand.

## The app

KeyDebounce installs, runs, checks and removes the fix. Every screen works with the keypad; no touchscreen needed.

| Button | What it does | Root |
| --- | --- | --- |
| Install fix | Installs the fix permanently: backs up the stock policy, checks the new policy compiles, installs, then checks the backup and the installed files. **Dry run only** does the check without changing anything. On a phone that already has the fix, **Check install** reruns the final checks. | Yes (not needed afterwards) |
| Undo (remove fix) | Puts the stock policy back from the backup and removes the daemon | Yes |
| Turn off / on | Turns the installed fix off or on without uninstalling it. Shows the adb commands; with root it can switch right away. | Only for "right away" |
| Run with root (no install) | **Start** runs the filter through root until the next restart, **Stop** stops it, **Boot** sets [Start at boot](#root-mode-and-start-at-boot). Nothing in `/system` or the policy changes. | Yes |
| Boot notification | A silent, clearable notification when Start at boot starts the filter, and whether it came on. Off by default. | No |
| Bounce filter (no root) | Explains the [bounce filter](#bounce-filter-no-root) and opens Accessibility settings | No |
| Key tester | Lists every key press and release with timing, and flags overlaps, fast repeats (bounce) and double presses | No |
| Correction log | Counts the overlap and bounce corrections the filter made | No, but needs log access |

The main screen shows whether the fix is on. When it's running, Android lists two `soc:matrix_keypad@0` keypads: the real one and the daemon's replacement.

The app asks for root only when you press a root action, or at boot if Start at boot is on. **Restart** buttons restart the phone in the background. Every root command's output is saved to `/sdcard/Android/data/com.anonymousfliphones.keydebounce/files/<command>.log`.

## Rooting with Root Manager

[Root Manager](https://github.com/flipphoneguy/root-sonim-xp3800) (`com.flipphoneguy.root.xp3`) keeps `su` installed in `/system/bin`, but root doesn't survive a restart. **The first root request after each restart runs a kernel exploit**, which can crash (panic) the phone. If it works, a daemon serves every later `su` safely until the next restart. Opening the Root Manager app doesn't start root; the first `su` does.

- The exploit panics more often while the phone is busy, especially in the first few minutes after booting, and more often with the permanent install's policy loaded.
- If root hasn't started since the restart, the app's root actions say so and offer **Start root**, which runs the exploit only when you press it.
- The **permanent install** is the way to stop needing root: after installing, the filter runs at every boot without any root request.
- If panics are frequent, [Root Manager](https://github.com/flipphoneguy/root-sonim-xp3800)'s author recommends flashing Magisk instead, which has root at every boot with no exploit.

## Install with the app

1. Back up the system partition, and have EDL flashing ready.
2. Get root.
3. Open KeyDebounce → **Install fix**. It stops before changing anything if the policy isn't stock, a compile fails, or the stock compile doesn't match the phone's precompiled policy.
4. It backs up the stock policy as `.bak` files in `/system/etc/selinux/` (Undo restores from them), installs, and checks that the `.bak` files are the stock policy and that `/system` holds what it installed.
5. Press **Restart**. After the restart the main screen should say **Fix is ON**.

## Root mode and Start at boot

**Run with root (no install)** → **Start** runs the filter through root, with no changes to `/system` or the SELinux policy, until you press **Stop** or restart. The root has to let `su` use `/dev/input` and `/dev/uinput` (Magisk and [Root Manager](https://github.com/flipphoneguy/root-sonim-xp3800) do); if it doesn't, the daemon exits right away and the output shows why. Running the daemon over `adb shell` without root can't work: the shell user can't open `/dev/uinput`.

**Boot** → **Turn on** sets **Start at boot**, which starts the filter by itself after each restart. Turning it on offers **Restart now** or **Later**. It's skipped when the permanent install is present.

- **With Magisk** (root that's on at boot) it starts right after the phone finishes booting.
- **With [Root Manager](https://github.com/flipphoneguy/root-sonim-xp3800)** it waits until about 5 minutes after power-on, when the phone has calmed down after booting (on an XP3800 the load peaks about 2 minutes after power-on and is back near idle by about 5), then starts root and the filter. It checks every 30 seconds and calls `su` once the `su` binary has been there on two checks in a row.
- **If starting root crashes the phone**, the next restarts ask first (**Start the keypad filter?** Start / Not now) instead of trying by themselves, until a start works. A marker file, flushed to storage 30 seconds before the `su` and deleted after it, is how the app knows.
- If something else starts root first (for example `su` in Termux), the filter starts 30 to 60 seconds later.

What Start at boot did is saved in `boot-start.log`, next to the other logs. To be told when it starts the filter, turn on **Boot notification** on the main screen.

## Bounce filter (no root)

An accessibility service that drops the extra press when one key press registers twice (contact bounce, 5 → "55"). It doesn't fix key overlap (5,6 → "566"); that needs the daemon.

Turn it on in **Settings ▸ Accessibility ▸ KeyDebounce**. The app's Bounce filter screen has an **Accessibility** button that opens that list. On the XP3800, turning it on or off only takes effect after a restart; most phones don't need one.

Or from a computer:

```
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services com.anonymousfliphones.keydebounce/.BounceFilterService
```

That command **replaces** the list of accessibility services that are on. If others are on, include them too, separated by colons (`:`). See the list with `adb shell settings get secure enabled_accessibility_services`.

To add it without retyping the others (PowerShell, or a Linux/Mac terminal):

```
adb shell 'S=com.anonymousfliphones.keydebounce/.BounceFilterService; L=$(settings get secure enabled_accessibility_services); case "$L" in null|"") L=$S;; *BounceFilterService*) ;; *) L="$L:$S";; esac; settings put secure enabled_accessibility_services "$L"; settings put secure accessibility_enabled 1; settings get secure enabled_accessibility_services'
```

To turn it off, switch it off in the same Accessibility list, or from a computer (removes only this service from the list):

```
adb shell 'L=$(settings get secure enabled_accessibility_services | sed "s#:*com.anonymousfliphones.keydebounce/[^:]*##; s#^:##"); settings put secure enabled_accessibility_services "$L"; settings get secure enabled_accessibility_services'
```

- **Reinstalling or updating the app removes it from the list.** Turn it on again afterwards.
- **Check it's really on:** `adb shell dumpsys accessibility | grep -A3 KeyDebounce` should show `capabilities=8`.

## Install by hand (permanent)

Android enforces SELinux at boot on this phone, so the daemon gets its own small security rule. It's added to the phone's policy, and Android recompiles the policy at every boot.

1. Back up the system partition, for EDL recovery if the phone ever fails to boot.
2. Copy `keydebounce`, `sepolicy/keydebounce.cil`, `sepolicy/keydebounce.rc`, `sepolicy/dryrun.sh`, and `sepolicy/install.sh` to `/data/local/tmp/kd_dry/` on the phone (they're in the release's daemon zip).
3. As root, run `sh /data/local/tmp/kd_dry/dryrun.sh`. Both compiles must print `exit=0`, and the baseline must match the phone's prebuilt policy. If not, stop; nothing has been changed.
4. As root, run `sh /data/local/tmp/kd_dry/install.sh`. It checks the stock policy against its hash and against the phone's precompiled policy, compiles once more, and stops before touching `/system` if anything fails.
5. Reboot. Check it's running with `adb shell ps -A -Z | grep keydebounce` (you should see `u:r:keydebounce:s0`).

The installer backs up the two stock policy files **on the phone, next to the originals**:

| Backup | Original |
| --- | --- |
| `/system/etc/selinux/plat_sepolicy.cil.bak` | `plat_sepolicy.cil` |
| `/system/etc/selinux/plat_and_mapping_sepolicy.cil.sha256.bak` | `plat_and_mapping_sepolicy.cil.sha256` |

They're made on the first install only and never overwritten, so they always hold the stock files. Reinstalling rebuilds from the `.bak` copy, so the rule is never added twice. Android ignores `.bak` files, and they survive factory resets because they're in `/system`.

## Turn the permanent install off

No root needed:

```
adb shell touch /data/local/tmp/keydebounce.off
adb reboot
```

Delete that file and reboot to turn it back on. The app's **Turn off / on** shows the same commands.

## Uninstall the permanent install

Needs root. In the app: **Undo (remove fix)**, then restart. By hand: copy `sepolicy/uninstall.sh` to the phone, run it as root, then reboot.

`uninstall.sh` checks the `.bak` backup against its hash before changing anything, puts the stock files back, and deletes the daemon and its startup entry. The phone then goes back to loading its prebuilt policy, exactly as before the install.

If the phone won't boot, flash the system backup over EDL.

## Build

Releases are built by GitHub Actions (`.github/workflows/build-app.yml`): every push builds the app, and **Actions → Build app → Run workflow** with a release tag (for example `v2.0`) also publishes a release with the APK and the daemon zip.

To build locally, the daemon (Android NDK r27, 32-bit ARM):

```
armv7a-linux-androideabi21-clang -O2 -Wall -Wextra -o keydebounce keydebounce.c -llog
```

The app (Android SDK plus NDK 27.0.12077973, JDK 17+):

```
./gradlew assembleDebug
```

The app build compiles `keydebounce.c` with the same command and bundles it and `sepolicy/` into the APK, at `app/build/outputs/apk/debug/keydebounce-debug.apk`.

## Testing

`test/harness.sh` reproduces the bug without typing: it injects key presses through the real keypad device and reads the dialer's digits field back. Copy `test/` to `/data/local/tmp/`, open the dialer, and run an inject script a few times:

```
sh /data/local/tmp/harness.sh /data/local/tmp/inject_56.sh 3
```

| Pattern | Without the fix | With the fix |
| --- | --- | --- |
| Back-to-back / separated (`inject_56.sh`) | 56 | 56 |
| Bounce (`inject_55_bounce.sh`), with the bounce filter | 55 | 5 |

## License

KeyDebounce is free to use, copy, and share for **noncommercial purposes** —
personal use, family, schools, charities, and the like — under the
[PolyForm Noncommercial License 1.0.0](LICENSE.md).

Using or distributing it **commercially** (for example, preinstalling it on
phones you sell, or bundling it with a paid service) requires permission —
[open an issue](https://github.com/anonymousfliphones/keydebounce/issues/new) on this page to ask.

Required Notice: Copyright anonymousfliphones
(https://github.com/anonymousfliphones/keydebounce)
