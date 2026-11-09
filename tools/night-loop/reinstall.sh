#!/bin/bash
# Stop the night loop, back up phone logs, install the built APK, restart BitAim capture, restart the loop.
# usage: reinstall.sh BACKUP_SUFFIX
export MSYS_NO_PATHCONV=1
A="D:/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"
APK=D:/WORK/HACK/HACKTHON/carrom-auto/BitAim-v4.0-source/BitAim-v4.0-source/android/app/build/outputs/apk/release/app-release.apk
powershell -NoProfile -c "Get-CimInstance Win32_Process | ? { \$_.CommandLine -like '*night.py 0540*' -and \$_.Name -like 'python*' } | % { Stop-Process -Id \$_.ProcessId -Force }"
B=D:/WORK/HACK/HACKTHON/carrom-auto/OUTPUTS/phone-data/backup-$(date +%Y%m%d)-$1
mkdir -p $B && $A pull /sdcard/Android/data/com.bitaim.carromaim/files/matches $B 2>&1 | tail -1
$A install -r $APK || exit 1
$A shell dumpsys package com.bitaim.carromaim | grep versionName
$A shell am force-stop com.miniclip.carrom
$A shell monkey -p com.bitaim.carromaim -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
for i in 1 2 3; do $A shell input swipe 540 600 540 2000 200; done; sleep 1
$A shell input tap 879 675; sleep 2; $A shell input tap 879 924; sleep 2
$A shell input tap 540 1020; sleep 1.5; $A shell input tap 335 1240; sleep 1.5
$A shell uiautomator dump /sdcard/ui.xml >/dev/null
if $A shell cat /sdcard/ui.xml | grep -q 'text="Cast"'; then $A shell input tap 867 1690; else echo "capture dialog not found"; exit 2; fi
sleep 2
cd D:/Tools/tmp/loop && rm -f night-state.json && (nohup python -u night.py 0540 > night.out 2>&1 &)
echo "loop restarted"
