#!/bin/bash
# Waits until no new shot record for 90 s in the newest match folder (max 45 min), then reports.
A="/d/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"
export MSYS_NO_PATHCONV=1
end=$(( $(date +%s) + 2700 ))
while [ $(date +%s) -lt $end ]; do
  d=$($A shell "ls -t /sdcard/Android/data/com.bitaim.carromaim/files/matches | head -1" | tr -d '\r')
  t=$($A shell "stat -c %Y /sdcard/Android/data/com.bitaim.carromaim/files/matches/$d/shots.jsonl 2>/dev/null" | tr -d '\r')
  now=$($A shell date +%s | tr -d '\r')
  if [ -n "$t" ] && [ $(( now - t )) -ge 90 ]; then echo "idle: last shot $(( now - t ))s ago in $d"; exit 0; fi
  if [ -z "$t" ]; then echo "idle: no shots file in $d"; exit 0; fi
  sleep 15
done
echo "timeout: still playing after 45 min"
