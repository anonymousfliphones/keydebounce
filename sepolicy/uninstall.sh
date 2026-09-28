#!/system/bin/sh
# Run as root. Restores the stock policy and removes the daemon. Reboot afterwards.
#
# Stock backup, first one found:
#   1. /system/etc/selinux/*.bak       made by the current install.sh
#   2. /data/local/tmp/kd_dry/stock/   older installs; the app's Undo stages it here
set -e
S=/system/etc/selinux
CIL=$S/plat_sepolicy.cil
SHA=$S/plat_and_mapping_sepolicy.cil.sha256
MAP=$S/mapping/27.0.cil
STAGED=/data/local/tmp/kd_dry/stock
trap 'mount -o ro,remount /system 2>/dev/null' EXIT

hash_of() { cat "$1" $MAP | sha256sum | cut -d' ' -f1; }

verifies() { [ -f "$1" ] && [ -f "$2" ] && [ "$(hash_of "$1")" = "$(cat "$2")" ]; }

# mv keeps the source's label, and .bak files made by the app's root shell before
# v1.5 carry the app's MLS categories. Put the stock files back to their stock label.
relabel() {
    ctx=$1; shift
    chcon "$ctx" "$@"
    for f in "$@"; do
        case "$(ls -Z "$f")" in
            "$ctx "*) ;;
            *) echo "Wrong SELinux label on $f: $(ls -Z "$f")"; exit 1 ;;
        esac
    done
}

if verifies $CIL.bak $SHA.bak; then
    SRC=bak; SRC_CIL=$CIL.bak; SRC_SHA=$SHA.bak
elif verifies $STAGED/plat_sepolicy.cil $STAGED/plat_and_mapping_sepolicy.cil.sha256; then
    SRC=staged; SRC_CIL=$STAGED/plat_sepolicy.cil; SRC_SHA=$STAGED/plat_and_mapping_sepolicy.cil.sha256
    [ -f $CIL.bak ] && echo "note: the .bak files don't verify; using $STAGED"
else
    echo "No stock policy backup that verifies (checked $S/*.bak and $STAGED). Nothing changed."
    exit 1
fi
echo "restoring from: $SRC_CIL"

pkill -f /system/bin/keydebounce 2>/dev/null || true
mount -o rw,remount /system
if [ $SRC = bak ]; then
    mv $CIL.bak $CIL
    mv $SHA.bak $SHA
else
    cp $SRC_CIL $CIL
    cp $SRC_SHA $SHA
    rm -f $CIL.bak $SHA.bak
fi
relabel u:object_r:sepolicy_file:s0 $CIL $SHA
rm -f /system/bin/keydebounce /system/etc/init/keydebounce.rc
sync
mount -o ro,remount /system

# These two should match: the phone goes back to loading its prebuilt policy.
echo "system hash : $(cat $SHA)"
echo "vendor hash : $(cat /vendor/etc/selinux/precompiled_sepolicy.plat_and_mapping.sha256)"
echo "Removed. Reboot to finish."
