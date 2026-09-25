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

## Requirements

- Sonim XP3800 on Android 8.1
- Root, only for installing and uninstalling. It runs without root afterwards.
- A backup of the system partition, and an EDL flashing setup for the XP3800 in case the phone won't boot

## Install (permanent, runs without root)

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

Needs root. Copy the backed-up stock policy files to `/data/local/tmp/kd_dry/stock/`, run `sepolicy/uninstall.sh` as root, then reboot.

If the phone won't boot, flash the system backup over EDL.

## Build

Android NDK r27, 32-bit ARM:

```
armv7a-linux-androideabi21-clang -O2 -Wall -Wextra -o keydebounce keydebounce.c -llog
```

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
