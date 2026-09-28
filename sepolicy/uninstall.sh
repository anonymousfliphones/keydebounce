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

if [ -f $CIL.bak ] && [ -f $SHA.bak ]; then
    SRC=bak; SRC_CIL=$CIL.bak; SRC_SHA=$SHA.bak
elif [ -f $STAGED/plat_sepolicy.cil ] && [ -f $STAGED/plat_and_mapping_sepolicy.cil.sha256 ]; then
    SRC=staged; SRC_CIL=$STAGED/plat_sepolicy.cil; SRC_SHA=$STAGED/plat_and_mapping_sepolicy.cil.sha256
else
    echo "No stock policy backup: no .bak files in $S and nothing in $STAGED. Nothing changed."
    exit 1
fi
if [ "$(hash_of $SRC_CIL)" != "$(cat $SRC_SHA)" ]; then
    echo "The backup in $SRC_CIL doesn't match its hash file. Nothing changed."
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
fi
rm -f /system/bin/keydebounce /system/etc/init/keydebounce.rc
sync
mount -o ro,remount /system

# These two should match: the phone goes back to loading its prebuilt policy.
echo "system hash : $(cat $SHA)"
echo "vendor hash : $(cat /vendor/etc/selinux/precompiled_sepolicy.plat_and_mapping.sha256)"
echo "Removed. Reboot to finish."
