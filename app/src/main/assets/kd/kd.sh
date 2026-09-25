#!/system/bin/sh
# Root helper for the keydebounce app. The app unpacks its bundled files (this
# script, the daemon and sepolicy/*) to a private directory and runs, via su:
#   sh <dir>/kd.sh <command> <dir>
#
#   dryrun     stage files in /data/local/tmp/kd_dry and compile the policy twice
#              (stock, stock + keydebounce) the way init does. Changes nothing.
#   install    back up the stock policy, dry run, then sepolicy/install.sh
#   uninstall  find a stock policy backup that verifies, then sepolicy/uninstall.sh
#   off | on   create/remove the kill switch and stop/start the daemon now
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
FILES="keydebounce keydebounce.cil keydebounce.rc dryrun.sh install.sh uninstall.sh"

fail() { echo "STOPPED: $*"; exit 1; }

first() { head -n 1 "$1" 2>/dev/null | cut -d' ' -f1; }
hash_with_mapping() { cat "$1" $MAP | sha256sum | cut -d' ' -f1; }
policy_installed() { grep -q keydebounce $S/plat_sepolicy.cil; }
daemon_running() { pgrep -f /system/bin/keydebounce >/dev/null; }

# Stock plat_sepolicy.cil: no keydebounce, and together with the mapping it
# hashes to what the vendor's precompiled policy was built from.
is_stock() {
  [ -f "$1" ] || return 1
  grep -q keydebounce "$1" && return 1
  [ "$(hash_with_mapping "$1")" = "$(first $VHASH)" ]
}

# A directory holding what uninstall.sh restores: stock cil + stock hash file.
good_backup() {
  is_stock "$1/plat_sepolicy.cil" &&
    [ "$(first "$1/plat_and_mapping_sepolicy.cil.sha256")" = "$(first $VHASH)" ]
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

backup() {
  if good_backup $STOCK; then
    echo "stock policy backup already in $STOCK"
  else
    is_stock $S/plat_sepolicy.cil || fail "the phone's current policy isn't stock, so it won't be backed up or changed"
    [ "$(first $S/plat_and_mapping_sepolicy.cil.sha256)" = "$(first $VHASH)" ] ||
      fail "the phone's policy hash file isn't stock, so nothing will be changed"
    mkdir -p $STOCK &&
      cp $S/plat_sepolicy.cil $S/plat_and_mapping_sepolicy.cil.sha256 $STOCK/ ||
      fail "couldn't back up the stock policy"
    good_backup $STOCK || fail "the stock policy backup doesn't verify"
    chown -R shell:shell $STOCK 2>/dev/null
    echo "stock policy backed up to $STOCK"
  fi
  # Second copy where adb pull and file managers can reach it.
  for d in /sdcard /data/media/0; do
    [ -d $d ] || continue
    if mkdir -p $d/keydebounce-backup 2>/dev/null &&
      cp $STOCK/plat_sepolicy.cil $STOCK/plat_and_mapping_sepolicy.cil.sha256 $d/keydebounce-backup/ 2>/dev/null; then
      echo "copy saved to /sdcard/keydebounce-backup"
      return 0
    fi
  done
  echo "note: couldn't copy the backup to /sdcard; pull $STOCK with adb instead"
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
  backup
  dryrun
  echo "== installing =="
  sh $W/install.sh || fail "install.sh failed. Run Undo before restarting."
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
    fail "no stock policy backup that verifies. Copy policy-backup/ from your computer to $STOCK with adb push, then try again."
  stage
  echo "== removing =="
  sh $W/uninstall.sh || fail "uninstall.sh failed. Don't restart until Undo succeeds."
  cmp -s $STOCK/plat_sepolicy.cil $S/plat_sepolicy.cil ||
    fail "the restored policy doesn't match the backup. Don't restart until Undo succeeds."
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

case "$CMD" in
  dryrun) preflight; stage; dryrun ;;
  install) install_fix ;;
  uninstall) uninstall_fix ;;
  off) turn_off ;;
  on) turn_on ;;
  reboot) sync; reboot ;;
  *) fail "unknown command: $CMD" ;;
esac
