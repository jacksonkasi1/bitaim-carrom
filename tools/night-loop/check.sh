#!/bin/bash
# Per-match health of auto play since a given match folder name (e.g. 20261110-0250).
export MSYS_NO_PATHCONV=1
A="D:/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"; M=/sdcard/Android/data/com.bitaim.carromaim/files/matches
for d in $($A shell "ls $M" | tr -d '\r' | awk -v s="$1" '$1>s'); do
  $A shell "cat $M/$d/events.log" > /tmp/e.txt 2>/dev/null
  echo "$d shots=$(grep -c ' SHOT ' /tmp/e.txt) extra=$(grep -c 'extra turn' /tmp/e.txt) stalls=$(grep -c 'auto-stall' /tmp/e.txt) fallback=$(grep -c 'no route:' /tmp/e.txt) blink=$(grep -c 'line-blink' /tmp/e.txt)"
  grep "auto-stall" /tmp/e.txt | cut -c1-110
done
grep -E "started|game over|restarting|match over" D:/Tools/tmp/loop/night.log | tail -6
