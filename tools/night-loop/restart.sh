#!/bin/sh
# Restart BitAim overlay + capture after an install, then return to Carrom.
P=D:/Tools/tmp/loop/ph.sh; A="D:/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"
$A shell monkey -p com.bitaim.carromaim -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; ping -n 4 127.0.0.1 >/dev/null
$P tap 758 572; ping -n 2 127.0.0.1 >/dev/null
$P tap 758 782; ping -n 3 127.0.0.1 >/dev/null
$P tap 455 860; ping -n 2 127.0.0.1 >/dev/null
$P tap 283 1046; ping -n 2 127.0.0.1 >/dev/null
$P tap 730 1426; ping -n 3 127.0.0.1 >/dev/null
$P shot r1
