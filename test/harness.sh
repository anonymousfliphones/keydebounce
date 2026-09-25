#!/system/bin/sh
# usage: sh harness.sh <pattern-script> <runs>
# Refuses to send any key unless the dialer is focused and its digits field is on screen.
# Clears the field one backspace at a time, re-reading it each time, and never
# sends a backspace once the field shows its placeholder.
P=$1
N=${2:-3}
PH='Search in number or name'

field() {
  uiautomator dump /sdcard/h.xml >/dev/null 2>&1
  grep -o 'text="[^"]*" resource-id="com.android.dialer:id/dialer_digits"' /sdcard/h.xml | sed 's/^text="//; s/" resource-id=.*//'
}

guard() {
  input keyevent 224
  sleep 0.5
  dumpsys window | grep mCurrentFocus | grep -q 'com.android.dialer/com.android.dialer.DialerMainActivity' || { echo "ABORT: dialer not focused"; exit 1; }
  grep -q 'com.android.dialer:id/dialer_digits' /sdcard/h.xml || { echo "ABORT: digits field not on screen"; exit 1; }
}

i=1
while [ $i -le $N ]; do
  field >/dev/null
  guard
  n=0
  t=$(field)
  while [ "$t" != "$PH" ]; do
    [ -z "$t" ] && { echo "ABORT: could not read field"; exit 1; }
    [ $n -ge 20 ] && { echo "ABORT: field did not clear"; exit 1; }
    input keyevent 67
    n=$((n+1))
    t=$(field)
  done
  guard
  sh $P
  sleep 1
  echo "run $i: $(field)"
  i=$((i+1))
done
