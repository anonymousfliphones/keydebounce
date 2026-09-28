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
trap 'mount -o ro,remount /system 2>/dev/null' EXIT

hash_of() { cat "$1" $MAP | sha256sum | cut -d' ' -f1; }

# Build from stock: the .bak if an earlier install made one, otherwise the live file.
if [ -f $CIL.bak ]; then STOCK=$CIL.bak; STOCK_SHA=$SHA.bak; else STOCK=$CIL; STOCK_SHA=$SHA; fi
if [ "$(hash_of $STOCK)" != "$(cat $STOCK_SHA)" ]; then
    echo "Stock policy ($STOCK) doesn't match its hash file. Refusing to install."
    exit 1
fi

cat $STOCK $W/keydebounce.cil > $W/plat_sepolicy.cil
NEWHASH=$(hash_of $W/plat_sepolicy.cil)

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
cp $W/plat_sepolicy.cil $CIL
printf '%s\n' "$NEWHASH" > $SHA
sync
mount -o ro,remount /system

ls -laZ /system/bin/keydebounce /system/etc/init/keydebounce.rc $CIL $CIL.bak $SHA $SHA.bak
echo "hash file: $(cat $SHA)"
echo "expected : $NEWHASH"
echo "Installed. Reboot to start it."
