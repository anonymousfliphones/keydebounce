# keydebounce: Sonim XP3800 keypad double-press fix

Native daemon that sits between the XP3800 matrix keypad (`soc:matrix_keypad@0`, `/dev/input/event1`) and Android, fixing two confirmed problems:

- **Overlap:** pressing a key while the previous one is still held makes the stock firmware insert an extra copy of the second key (5 then 6 typed as "566"). Every layer up to Android's input dispatch was verified clean; the extra digit is added inside built-in software. Swapping the IME for TT9 didn't help, so it isn't the keyboard app; no-root routes (IME, accessibility key filter) can't inject the early release this needs. Fix: when a new key goes down, release any held key first.
- **Contact bounce:** a hard press can register a ghost second press ~10 ms after release. Fix: hold each release 20 ms; a same-key press inside that window is merged.

Findings doc: https://claude.ai/code/artifact/d743b08c-8f0c-4289-8a52-9ad630f0f1e2

## Layout

| Path | What |
| --- | --- |
| `keydebounce.c` | Daemon source. `nograb` arg = test mode (creates the replacement device only). |
| `keydebounce` | Built binary (armeabi-v7a). Rebuild from source; see below. |
| `sepolicy/` | Permanent install: SELinux rule (`keydebounce.cil`), init service (`keydebounce.rc`), `dryrun.sh`, `install.sh`, `uninstall.sh` |
| `policy-backup/` | Stock `plat_sepolicy.cil` + its `.sha256` pulled from the phone before install. Needed to uninstall. Never delete. |
| `test/` | `harness.sh` + `inject_56*.sh`: automated repro via `sendevent`, reads the dialer field back with `uiautomator` |
| `app/` | Android app (Java, no AndroidX): status, Install/Undo/Off-On via root, key tester, correction log. Package `io.github.anonymousfliphones.keydebounce`, minSdk 26. |
| `app/src/main/assets/kd/kd.sh` | Root helper the app runs via `su`. Wraps `sepolicy/*.sh`: stages to `/data/local/tmp/kd_dry`, backs up stock policy (also `/sdcard/keydebounce-backup`), dry-runs, refuses unless the policy is stock and the baseline compile matches `/vendor/etc/selinux/precompiled_sepolicy`. `rootstart`/`rootstop` = root mode: runs the daemon from `/data/local/tmp/kd_dry/run/` via `setsid`, no install. |
| `.github/workflows/build-app.yml` | CI: builds the APK and uploads `keydebounce-apk` + `keydebounce-daemon` artifacts |

## Build

```
"C:/Users/adsch/AppData/Local/Android/Sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/windows-x86_64/bin/armv7a-linux-androideabi21-clang.cmd" -O2 -Wall -Wextra -o keydebounce keydebounce.c -llog
```

App: `./gradlew assembleDebug` (AGP 8.7.3, Gradle 8.9 wrapper, JDK 17+). The `buildDaemon<Variant>` task in `app/build.gradle.kts` runs the same clang command from the pinned NDK and bundles the binary with `sepolicy/*` as `assets/kd/`. CI is the reference build; the cloud dev container can't reach dl.google.com.

The app must never run `su` on its own (startup, status checks): root requests with the policy installed crash the phone. The one exception is root mode's opt-in Start at boot (`BootReceiver`), which must skip `su` whenever `plat_sepolicy.cil` contains keydebounce. Status is read without root (`/system` files, `init.svc.keydebounce`, count of `soc:matrix_keypad@0` input devices).

## Device facts that matter

- Android 8.1, user build, SELinux **enforcing at boot**. A plain init service won't start; the daemon needs its own domain (`sepolicy/keydebounce.cil`), appended to `/system/etc/selinux/plat_sepolicy.cil`, plus a new `plat_and_mapping_sepolicy.cil.sha256` so init recompiles policy at boot.
- `dryrun.sh` compiles the policy on the phone exactly as init does; its baseline output is byte-identical to `/vendor/etc/selinux/precompiled_sepolicy`. Always dry-run before changing policy.
- Runs as user `system`, groups `input` + `bluetooth` (owner/group of `/dev/uinput`), domain `u:r:keydebounce:s0`, started on `sys.boot_completed=1`, oneshot.
- Replacement uinput device keeps the name `soc:matrix_keypad@0` so Android loads the same `.kl`/`.idc`. If the daemon dies, the kernel drops the grab and the keypad works unfiltered.
- Logs to logcat tag `keydebounce` (`overlap:` and `bounce:` lines = corrections). Boot noise can push early lines out of the buffer.

## Current state of the user's phone (2026-09-24)

- Permanent install is live and verified: 8 overlap corrections and 2 bounces caught with SELinux enforcing.
- Root was removed afterwards (no `/system/bin/su`). Uninstalling now needs root again or an EDL flash.
- Lucky Patcher is disabled (`pm disable-user`); re-enable with `adb shell pm enable ru.yqkvpzvg.dbnodtftw`.
- Test settings still changed: `screen_off_timeout` = 1800000, `stay_on_while_plugged_in` = 2.

## Off switch and undo

- Disable, no root: `adb shell touch /data/local/tmp/keydebounce.off`, then reboot. Remove the file and reboot to re-enable.
- Full removal (needs root): stage `policy-backup/` files to `/data/local/tmp/kd_dry/stock/` and run `sepolicy/uninstall.sh` as root, then reboot.
- Phone won't boot: EDL-flash a system backup with `prog_emmc_firehose_8909_ddr.mbn` (in the platform-tools workshop).

## Warnings

- **With the policy installed, Root Manager's kernel exploit crashed the phone on almost every attempt** (from apps and from ADB). Any app that asks for root at startup, such as Lucky Patcher, then causes a reboot loop. Remove root, or disable those apps, before rebooting with the permanent install.
- The mouse service (MATVT) crashed twice (`EGL_BAD_ALLOC`) while the daemon ran alongside automated screenshots/UI dumps. Not reproduced in normal use.

## Working on this device

- Bash tool: start every command with `export MSYS_NO_PATHCONV=1;` or Git Bash rewrites `/dev/...`, `/sdcard/...` paths.
- USB drops constantly; use `adb wait-for-device` and retry loops.
- Screen dozes within 1–2 min; wake with `input keyevent 224` and check `dumpsys window | grep mCurrentFocus` before any automated input.
- Never send backspaces blindly in the dialer: once the field is empty they hit the call log and open "delete call log?". `test/harness.sh` guards against this.
- `pm`/`settings put` changes to IMEs and accessibility services don't rebind live on this ROM; force-stop the app or reboot, then verify with `dumpsys`.
- Grep tool: use `output_mode: "content"`; the default only lists matching files.
