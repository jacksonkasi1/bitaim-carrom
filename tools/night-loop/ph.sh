#!/bin/sh
# usage: ph.sh tap X Y (display coords from a 911-wide screenshot) | shot NAME | key K | swipe x1 y1 x2 y2 ms
A="D:/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"
case "$1" in
 tap) $A shell input tap $(awk "BEGIN{printf \"%d %d\", $2*1.1855, $3*1.1855}");;
 swipe) $A shell input swipe $(awk "BEGIN{printf \"%d %d %d %d\", $2*1.1855, $3*1.1855, $4*1.1855, $5*1.1855}") $6;;
 shot) $A exec-out screencap -p > D:/Tools/tmp/loop/$2.png;;
 key) $A shell input keyevent $2;;
esac
