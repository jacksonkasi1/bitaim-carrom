#!/bin/bash
# Guarded recorder: frames only while Carrom/BitAim is the focused app; never system UI. Stops after 40 min.
A="/d/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"
end=$(( $(date +%s) + 2400 ))
while [ $(date +%s) -lt $end ]; do
  W=$($A shell dumpsys window 2>/dev/null)
  app=$(echo "$W" | grep "mFocusedApp=" | grep -v "=null" | head -1)
  cur=$(echo "$W" | grep "mCurrentFocus=" | grep -v "=null" | head -1)
  ok=1
  case "$cur" in *NotificationShade*|*StatusBar*|*com.android.systemui*) ok=0;; esac
  case "$app" in *com.miniclip.carrom*|*com.bitaim.carromaim*) ;; *) ok=0;; esac
  if [ $ok = 1 ]; then
    n=$(date +%H%M%S)
    $A exec-out screencap -p > /d/Tools/tmp/loop/rec/raw.png 2>/dev/null && python -c "from PIL import Image;im=Image.open('D:/Tools/tmp/loop/rec/raw.png').convert('RGB');im.resize((180,395)).save('D:/Tools/tmp/loop/rec/$n.jpg',quality=60)" 2>/dev/null
    rm -f /d/Tools/tmp/loop/rec/raw.png
  fi
  ls -1 /d/Tools/tmp/loop/rec/*.jpg 2>/dev/null | head -n -500 | xargs -r rm -f
  sleep 4
done
