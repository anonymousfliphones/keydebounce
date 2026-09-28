#!/system/bin/sh
# Compiles the phone's policy exactly as init does at boot, twice:
# baseline (stock) and with keydebounce.cil appended.
# Uses the stock .bak if an earlier install made one.
# Writes only to /data/local/tmp/kd_dry. Changes nothing in /system or /vendor.
W=/data/local/tmp/kd_dry
S=/system/etc/selinux
V=$(cat /sys/fs/selinux/policyvers)
STOCK=$S/plat_sepolicy.cil
[ -f $S/plat_sepolicy.cil.bak ] && STOCK=$S/plat_sepolicy.cil.bak
mkdir -p $W
cat $STOCK $W/keydebounce.cil > $W/plat_sepolicy.cil

run() {
  /system/bin/secilc $1 -M true -G -N -c $V $S/mapping/27.0.cil /vendor/etc/selinux/nonplat_sepolicy.cil -o $2 -f /dev/null
  echo "exit=$?"
}

echo "stock source: $STOCK"
echo "== baseline =="
run $STOCK $W/baseline.policy
echo "== with keydebounce =="
run $W/plat_sepolicy.cil $W/modified.policy
echo "baseline matches the phone's prebuilt policy: $( [ "$(sha256sum < $W/baseline.policy)" = "$(sha256sum < /vendor/etc/selinux/precompiled_sepolicy)" ] && echo yes || echo NO)"
echo "keydebounce type in modified policy: $(grep -a -c keydebounce $W/modified.policy)"
