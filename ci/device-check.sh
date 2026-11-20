#!/usr/bin/env bash
# Runs inside the emulator job: installs the app and its on-device check,
# runs the check in light and dark mode, and collects the report + screenshots.
set -x
mkdir -p out
adb wait-for-device
adb shell settings put global window_animation_scale 0
# Slow CI emulators often show "Pixel Launcher isn't responding"; keep such popups
# off the screenshots.
adb shell settings put global hide_error_dialogs 1
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
sleep 20
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.roboduck.image android.permission.RECORD_AUDIO
RUNNER=com.roboduck.image.test/androidx.test.runner.AndroidJUnitRunner

adb shell cmd uimode night no
sleep 2
adb shell am instrument -w -r -e class com.roboduck.image.DeviceCheck "$RUNNER" > out/instrument-light.txt 2>&1
adb shell cmd uimode night yes
sleep 3
adb shell am instrument -w -r -e class com.roboduck.image.DeviceCheck#screens "$RUNNER" > out/instrument-dark.txt 2>&1
adb shell cmd uimode night no

for f in $(adb shell run-as com.roboduck.image ls files/ci | tr -d '\r'); do
  adb exec-out run-as com.roboduck.image cat "files/ci/$f" > "out/$f"
done
adb logcat -d -s RoboDuckCheck:I AndroidRuntime:E TestRunner:* > out/logcat.txt 2>&1 || true
grep -h -E "OK \(|FAILURES|Tests run" out/instrument-*.txt > out/summary.txt || true
ls -la out
exit 0
