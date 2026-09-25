#!/system/bin/sh
# Run as root. Restores the stock policy files (staged in /data/local/tmp/kd_dry/stock)
# and removes the daemon. Reboot afterwards.
set -e
W=/data/local/tmp/kd_dry/stock
S=/system/etc/selinux
pkill -f /system/bin/keydebounce || true
mount -o rw,remount /system
cp $W/plat_sepolicy.cil $S/plat_sepolicy.cil
cp $W/plat_and_mapping_sepolicy.cil.sha256 $S/plat_and_mapping_sepolicy.cil.sha256
rm -f /system/bin/keydebounce /system/etc/init/keydebounce.rc
sync
mount -o ro,remount /system
sha256sum $S/plat_sepolicy.cil $W/plat_sepolicy.cil
cat $S/plat_and_mapping_sepolicy.cil.sha256 /vendor/etc/selinux/precompiled_sepolicy.plat_and_mapping.sha256
