#!/bin/bash
# Privacy guard: capture only while Carrom or BitAim is the focused app (popups over them allowed; never the shade).
A="/d/Tools/Android/platform-tools/adb.exe -s 9DCYBIDMRCOJQSMN"
W=$($A shell dumpsys window)
app=$(echo "$W" | grep "mFocusedApp=" | grep -v "=null" | head -1)
cur=$(echo "$W" | grep "mCurrentFocus=" | grep -v "=null" | head -1)
case "$cur" in *NotificationShade*|*StatusBar*|*com.android.systemui*) echo "SKIP: system UI in front"; rm -f /d/Tools/tmp/loop/small.jpg; exit 0;; esac
case "$app" in
  *com.miniclip.carrom*|*com.bitaim.carromaim*) ;;
  *) echo "SKIP: other app in front"; rm -f /d/Tools/tmp/loop/small.jpg; exit 0;;
esac
$A exec-out screencap -p > /d/Tools/tmp/loop/full.png
python -c "from PIL import Image;im=Image.open('D:/Tools/tmp/loop/full.png');im.convert('RGB').resize((im.width//3,im.height//3)).save('D:/Tools/tmp/loop/small.jpg',quality=70)"
rm -f /d/Tools/tmp/loop/full.png
