#!/system/bin/sh
# Run as root. Inputs staged in /data/local/tmp/kd_dry:
#   keydebounce (binary), keydebounce.rc, plat_sepolicy.cil (stock + keydebounce.cil, already dry-run compiled)
set -e
W=/data/local/tmp/kd_dry
S=/system/etc/selinux
NEWHASH=$(cat $W/plat_sepolicy.cil $S/mapping/27.0.cil | sha256sum | cut -d' ' -f1)

mount -o rw,remount /system
cp $W/keydebounce /system/bin/keydebounce
chown root:shell /system/bin/keydebounce
chmod 755 /system/bin/keydebounce
cp $W/keydebounce.rc /system/etc/init/keydebounce.rc
chown root:root /system/etc/init/keydebounce.rc
chmod 644 /system/etc/init/keydebounce.rc
cp $W/plat_sepolicy.cil $S/plat_sepolicy.cil
printf '%s\n' "$NEWHASH" > $S/plat_and_mapping_sepolicy.cil.sha256
sync
mount -o ro,remount /system

ls -laZ /system/bin/keydebounce /system/etc/init/keydebounce.rc $S/plat_sepolicy.cil $S/plat_and_mapping_sepolicy.cil.sha256
echo "hash file: $(cat $S/plat_and_mapping_sepolicy.cil.sha256)"
echo "expected : $NEWHASH"
sha256sum $W/plat_sepolicy.cil $S/plat_sepolicy.cil $W/keydebounce /system/bin/keydebounce
