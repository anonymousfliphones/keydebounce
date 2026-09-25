#!/system/bin/sh
D=/dev/input/event1
sendevent $D 4 4 3
sendevent $D 1 6 1
sendevent $D 0 0 0
sleep 0.15
sendevent $D 4 4 2
sendevent $D 1 7 1
sendevent $D 0 0 0
sleep 0.05
sendevent $D 4 4 3
sendevent $D 1 6 0
sendevent $D 0 0 0
sleep 0.1
sendevent $D 4 4 2
sendevent $D 1 7 0
sendevent $D 0 0 0
