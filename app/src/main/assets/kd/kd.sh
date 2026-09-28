#!/system/bin/sh
# Root helper for the keydebounce app. The app unpacks its bundled files (this
# script, the daemon and sepolicy/*) to a private directory and runs, via su:
#   sh <dir>/kd.sh <command> <dir>
#
#   dryrun     stage files in /data/local/tmp/kd_dry and compile the policy twice
#              (stock, stock + keydebounce) the way init does. Changes nothing.
#   install    check the policy is stock, dry run, then sepolicy/install.sh, which
#              backs up the stock policy as .bak files in /system/etc/selinux
#   uninstall  check a stock backup verifies, then sepolicy/uninstall.sh
#              (.bak files first; older installs: $STOCK or /sdcard/keydebounce-backup)
#   off | on   create/remove the kill switch and stop/start the daemon now
#   rootstart  run the daemon from root without installing anything (until restart)
#   rootstop   stop the daemon started by rootstart
#   reboot
#
# Every check runs before the step it guards; a failed check stops the command.

CMD=$1
SRC=$2
W=/data/local/tmp/kd_dry
STOCK=$W/stock
S=/system/etc/selinux
MAP=$S/mapping/27.0.cil
VHASH=/vendor/etc/selinux/precompiled_sepolicy.plat_and_mapping.sha256
VPOLICY=/vendor/etc/selinux/precompiled_sepolicy
KILL=/data/local/tmp/keydebounce.off
RUN=$W/run/keydebounce
FILES="keydebounce keydebounce.cil keydebounce.rc dryrun.sh install.sh uninstall.sh"

fail() { echo "STOPPED: $*"; exit 1; }

first() { head -n 1 "$1" 2>/dev/null | cut -d' ' -f1; }
hash_with_mapping() { cat "$1" $MAP | sha256sum | cut -d' ' -f1; }
policy_installed() { grep -q keydebounce $S/plat_sepolicy.cil; }
daemon_running() { pgrep -f '^/system/bin/keydebounce' >/dev/null; }
root_running() { pgrep -f "^$RUN" >/dev/null; }

# Stock plat_sepolicy.cil: no keydebounce, and together with the mapping it
# hashes to what the vendor's precompiled policy was built from.
is_stock() {
  [ -f "$1" ] || return 1
  grep -q keydebounce "$1" && return 1
  [ "$(hash_with_mapping "$1")" = "$(first $VHASH)" ]
}

# A directory holding what uninstall.sh restores: stock cil + stock hash file.
# Only older installs have one; current installs keep the .bak files instead.
good_backup() {
  is_stock "$1/plat_sepolicy.cil" &&
    [ "$(first "$1/plat_and_mapping_sepolicy.cil.sha256")" = "$(first $VHASH)" ]
}

# The .bak files sepolicy/install.sh leaves next to the originals.
good_bak() {
  is_stock $S/plat_sepolicy.cil.bak &&
    [ "$(first $S/plat_and_mapping_sepolicy.cil.sha256.bak)" = "$(first $VHASH)" ]
}

preflight() {
  [ "$(id -u)" = 0 ] || fail "not running as root"
  [ -f $MAP ] || fail "$MAP not found; this isn't the phone this installer is for"
  [ -s $VHASH ] || fail "$VHASH not found"
  [ -f $VPOLICY ] || fail "$VPOLICY not found"
}

stage() {
  [ -n "$SRC" ] && [ -d "$SRC" ] || fail "the app's files weren't found"
  mkdir -p $W || fail "can't create $W"
  for f in $FILES; do
    [ -f "$SRC/$f" ] || fail "app file missing: $f"
    cp "$SRC/$f" "$W/$f" && cmp -s "$SRC/$f" "$W/$f" || fail "couldn't copy $f to $W"
  done
  chmod 755 $W/keydebounce
  # Same owner as when staged with adb push, so adb can still read and write here.
  chown -R shell:shell $W 2>/dev/null
  echo "files staged in $W"
}

# Nothing is copied here: install.sh makes the .bak backup in /system/etc/selinux.
# This only checks there's a stock policy for it to back up (or a good .bak already).
check_stock() {
  if good_bak; then
    echo "stock policy backup already in $S (.bak)"
    return 0
  fi
  is_stock $S/plat_sepolicy.cil || fail "the phone's current policy isn't stock, so it won't be backed up or changed"
  [ "$(first $S/plat_and_mapping_sepolicy.cil.sha256)" = "$(first $VHASH)" ] ||
    fail "the phone's policy hash file isn't stock, so nothing will be changed"
  echo "policy is stock; install.sh will back it up as .bak files in $S"
}

dryrun() {
  policy_installed && fail "the fix is already in the policy; use Undo first"
  echo "== dry run: compiling the policy the way init does at boot =="
  sh $W/dryrun.sh > $W/dryrun.out 2>&1
  cat $W/dryrun.out
  [ "$(grep -c '^exit=0$' $W/dryrun.out)" = 2 ] || fail "a policy compile failed; nothing was changed"
  cmp -s $W/baseline.policy $VPOLICY ||
    fail "the stock compile doesn't match the phone's precompiled policy; nothing was changed"
  [ "$(grep -a -c keydebounce $W/modified.policy)" -gt 0 ] 2>/dev/null ||
    fail "the compiled policy is missing the keydebounce rule; nothing was changed"
  echo "dry run passed"
}

install_fix() {
  preflight
  policy_installed && fail "the fix is already installed; use Undo first"
  stage
  check_stock
  dryrun
  echo "== installing =="
  sh $W/install.sh || fail "install.sh failed. Run Undo before restarting."
  good_bak || fail "the .bak backup in $S is missing or doesn't verify. Run Undo before restarting."
  echo "stock policy backed up: $S/plat_sepolicy.cil.bak"
  cmp -s $W/keydebounce /system/bin/keydebounce || fail "the installed daemon doesn't match. Run Undo before restarting."
  cmp -s $W/plat_sepolicy.cil $S/plat_sepolicy.cil || fail "the installed policy doesn't match. Run Undo before restarting."
  [ "$(first $S/plat_and_mapping_sepolicy.cil.sha256)" = "$(hash_with_mapping $S/plat_sepolicy.cil)" ] ||
    fail "the policy hash file doesn't match. Run Undo before restarting."
  if [ -e $KILL ]; then
    rm -f $KILL && echo "off switch removed"
  fi
  echo "installed"
}

uninstall_fix() {
  preflight
  if ! policy_installed && [ ! -e /system/bin/keydebounce ] && [ ! -e /system/etc/init/keydebounce.rc ]; then
    fail "the fix isn't installed; nothing to undo"
  fi
  if good_bak; then
    echo "using the .bak backup in $S"
  else
    # Older installs kept the backup outside /system.
    if ! good_backup $STOCK; then
      for d in /sdcard/keydebounce-backup /data/media/0/keydebounce-backup; do
        if good_backup $d; then
          mkdir -p $STOCK && cp $d/plat_sepolicy.cil $d/plat_and_mapping_sepolicy.cil.sha256 $STOCK/ &&
            echo "using the backup in $d"
          break
        fi
      done
    fi
    good_backup $STOCK ||
      fail "no stock policy backup that verifies: no .bak files in $S. Copy policy-backup/ from your computer to $STOCK with adb push, then try again."
    echo "using the backup in $STOCK"
  fi
  stage
  echo "== removing =="
  sh $W/uninstall.sh || fail "uninstall.sh failed. Don't restart until Undo succeeds."
  is_stock $S/plat_sepolicy.cil ||
    fail "the restored policy isn't stock. Don't restart until Undo succeeds."
  [ "$(first $S/plat_and_mapping_sepolicy.cil.sha256)" = "$(first $VHASH)" ] ||
    fail "the restored hash file doesn't match the vendor policy. Don't restart until Undo succeeds."
  [ -e /system/bin/keydebounce ] && fail "the daemon is still in /system/bin"
  rm -f $KILL
  echo "removed"
}

turn_off() {
  touch $KILL || fail "couldn't create $KILL"
  chown shell:shell $KILL 2>/dev/null
  chmod 644 $KILL
  echo "off switch created: $KILL"
  pkill -f /system/bin/keydebounce 2>/dev/null
  sleep 1
  daemon_running && stop keydebounce
  sleep 1
  daemon_running && fail "the daemon is still running; restart the phone to finish turning it off"
  echo "off"
}

turn_on() {
  [ -e /system/bin/keydebounce ] || fail "the fix isn't installed"
  rm -f $KILL || fail "couldn't remove $KILL"
  echo "off switch removed"
  if ! daemon_running; then
    start keydebounce
    sleep 2
  fi
  daemon_running || fail "it didn't start now; restart the phone to turn it on"
  echo "on"
}

# Root mode: nothing in /system or the policy changes, so SELinux has to let
# su use /dev/input and /dev/uinput (Magisk does). Runs from its own copy so
# staging for an install never overwrites a running binary.
root_start() {
  [ "$(id -u)" = 0 ] || fail "not running as root"
  policy_installed && fail "the fix is installed, so it already starts at boot. Use Turn off / on instead."
  if root_running; then
    echo "already running"
    echo "on"
    return 0
  fi
  [ -f "$SRC/keydebounce" ] || fail "app file missing: keydebounce"
  mkdir -p $W/run &&
    cp "$SRC/keydebounce" $RUN.new && cmp -s "$SRC/keydebounce" $RUN.new &&
    chmod 755 $RUN.new && mv -f $RUN.new $RUN || fail "couldn't copy the daemon to $W/run"
  chown shell:shell $W 2>/dev/null
  if [ -e $KILL ]; then
    rm -f $KILL && echo "off switch file removed (it only applies to the installed fix)"
  fi
  # New session, so it outlives this su shell.
  if command -v setsid >/dev/null 2>&1; then
    setsid $RUN </dev/null >/dev/null 2>&1 &
  else
    nohup $RUN </dev/null >/dev/null 2>&1 &
  fi
  sleep 2
  echo "log:"
  logcat -d -s keydebounce 2>/dev/null | tail -n 4
  root_running ||
    fail "the daemon exited right away. The log above says why; this root may not allow /dev/input or /dev/uinput."
  echo "on"
}

root_stop() {
  if ! root_running; then
    echo "wasn't running"
    echo "off"
    return 0
  fi
  pkill -f "^$RUN"
  sleep 1
  root_running && fail "it's still running"
  echo "off"
}

case "$CMD" in
  dryrun) preflight; stage; dryrun ;;
  install) install_fix ;;
  uninstall) uninstall_fix ;;
  off) turn_off ;;
  on) turn_on ;;
  rootstart) root_start ;;
  rootstop) root_stop ;;
  # The reboot binary only sets sys.powerctl, and the property service refused that
  # for the app's root shell (reboot.log: "reboot: Success", then exit 1). Ask
  # system_server instead, which may reboot and accepts root as the caller.
  reboot)
    sync
    echo "context: $(cat /proc/self/attr/current 2>/dev/null)"
    svc power reboot
    echo "svc power reboot didn't restart the phone (exit $?); trying the reboot binary"
    reboot
    ;;
  *) fail "unknown command: $CMD" ;;
esac
