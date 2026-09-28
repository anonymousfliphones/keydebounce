#!/system/bin/sh
# Run as root. Stage these in /data/local/tmp/kd_dry first:
#   keydebounce (binary), keydebounce.rc, keydebounce.cil
#
# The stock policy is backed up in place, next to the originals:
#   /system/etc/selinux/plat_sepolicy.cil.bak
#   /system/etc/selinux/plat_and_mapping_sepolicy.cil.sha256.bak
# The .bak files are made once and never overwritten, so they always hold stock.
set -e
W=/data/local/tmp/kd_dry
S=/system/etc/selinux
CIL=$S/plat_sepolicy.cil
SHA=$S/plat_and_mapping_sepolicy.cil.sha256
MAP=$S/mapping/27.0.cil
# '|| :' matters: under set -e a failing command in an EXIT trap becomes the script's
# exit status, so a refused (busy) remount here turned a finished run into "failed".
trap 'mount -o ro,remount /system 2>/dev/null || :' EXIT

hash_of() { cat "$1" $MAP | sha256sum | cut -d' ' -f1; }

# New files take the creating shell's MLS level, and the app's root shell runs at
# the app's categories (s0:c512,c768). The daemon runs at s0 and SELinux won't let
# it even stat a file at that level: the linker aborts with 'unable to stat
# "/proc/self/exe"' before main(). So set the exact labels and check them.
relabel() {
    ctx=$1; shift
    chcon "$ctx" "$@"
    for f in "$@"; do
        case "$(ls -Z "$f")" in
            "$ctx "*) ;;
            *) echo "Wrong SELinux label on $f: $(ls -Z "$f"). Refusing to install the policy."; exit 1 ;;
        esac
    done
}

# Build from stock: the .bak if an earlier install made one, otherwise the live file.
if [ -f $CIL.bak ]; then STOCK=$CIL.bak; STOCK_SHA=$SHA.bak; else STOCK=$CIL; STOCK_SHA=$SHA; fi
if [ "$(hash_of $STOCK)" != "$(cat $STOCK_SHA)" ]; then
    echo "Stock policy ($STOCK) doesn't match its hash file. Refusing to install."
    exit 1
fi

cat $STOCK $W/keydebounce.cil > $W/plat_sepolicy.cil
NEWHASH=$(hash_of $W/plat_sepolicy.cil)

# This project has only been built and tested against the Verizon XP3800's stock
# policy. A different carrier/firmware variant can have a different base policy
# that still passes the hash check above yet compiles to something else entirely.
# Catch that by recompiling the untouched stock policy and comparing it to what
# this device itself shipped precompiled, exactly like dryrun.sh reports but
# enforced here as a hard stop instead of an FYI line.
/system/bin/secilc $STOCK -M true -G -N -c "$(cat /sys/fs/selinux/policyvers)" \
    $MAP /vendor/etc/selinux/nonplat_sepolicy.cil -o $W/baseline.policy -f /dev/null
if [ "$(sha256sum < $W/baseline.policy | cut -d' ' -f1)" \
     != "$(sha256sum < /vendor/etc/selinux/precompiled_sepolicy | cut -d' ' -f1)" ]; then
    echo "This device's compiled stock policy doesn't match its own precompiled_sepolicy."
    echo "That means this phone's base SELinux policy differs from the Verizon variant"
    echo "keydebounce was built and tested against (likely a different carrier/firmware"
    echo "build). Installing here is untested and could leave the phone unable to boot."
    echo "Refusing to install."
    exit 1
fi

# Compile exactly as init does at boot. Stop before touching /system if it fails.
/system/bin/secilc $W/plat_sepolicy.cil -M true -G -N -c "$(cat /sys/fs/selinux/policyvers)" \
    $MAP /vendor/etc/selinux/nonplat_sepolicy.cil -o $W/test.policy -f /dev/null
echo "dry-run compile: ok"

mount -o rw,remount /system
[ -f $CIL.bak ] || cp -p $CIL $CIL.bak
[ -f $SHA.bak ] || cp -p $SHA $SHA.bak
cp $W/keydebounce /system/bin/keydebounce
chown root:shell /system/bin/keydebounce
chmod 755 /system/bin/keydebounce
cp $W/keydebounce.rc /system/etc/init/keydebounce.rc
chown root:root /system/etc/init/keydebounce.rc
chmod 644 /system/etc/init/keydebounce.rc
# Before the policy changes, so a failure here leaves the phone booting stock.
relabel u:object_r:system_file:s0 /system/bin/keydebounce /system/etc/init/keydebounce.rc
relabel u:object_r:sepolicy_file:s0 $CIL.bak $SHA.bak
cp $W/plat_sepolicy.cil $CIL
printf '%s\n' "$NEWHASH" > $SHA
sync
# Refused ("busy") while any process has a /system file open for writing, or a deleted
# one still open. Everything above is written and synced, so that's not a failure:
# a restart mounts /system read-only again.
mount -o ro,remount /system 2>/dev/null ||
    echo "note: /system couldn't be made read-only again (busy). The install is done; restarting does it."

ls -laZ /system/bin/keydebounce /system/etc/init/keydebounce.rc $CIL $CIL.bak $SHA $SHA.bak
echo "hash file: $(cat $SHA)"
echo "expected : $NEWHASH"
echo "Installed. Reboot to start it."
