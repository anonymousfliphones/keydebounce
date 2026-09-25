#!/system/bin/sh
# Compiles the phone's policy exactly as init does at boot, twice:
# baseline (unmodified files) and with keydebounce.cil appended.
# Writes only to /data/local/tmp/kd_dry. Changes nothing in /system or /vendor.
W=/data/local/tmp/kd_dry
S=/system/etc/selinux
V=$(cat /sys/fs/selinux/policyvers)
mkdir -p $W
cat $S/plat_sepolicy.cil $W/keydebounce.cil > $W/plat_sepolicy.cil

run() {
  /system/bin/secilc $1 -M true -G -N -c $V $S/mapping/27.0.cil /vendor/etc/selinux/nonplat_sepolicy.cil -o $2 -f /dev/null
  echo "exit=$?"
}

echo "== baseline =="
run $S/plat_sepolicy.cil $W/baseline.policy
echo "== with keydebounce =="
run $W/plat_sepolicy.cil $W/modified.policy
ls -la $W
echo "new hash: $(cat $W/plat_sepolicy.cil $S/mapping/27.0.cil | sha256sum | cut -d' ' -f1)"
echo "keydebounce type in modified policy: $(grep -a -c keydebounce $W/modified.policy)"
