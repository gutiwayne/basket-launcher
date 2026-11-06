#!/usr/bin/env bash
# Cesta: README screenshots on the CI emulator, with sample lists only (no personal data).
set -euo pipefail
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
PKG=io.github.jesusg703.cesta.debug
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
OUT=screenshots
mkdir -p "$OUT"

# Fail loudly instead of capturing another app (e.g. a system dialog on top).
in_cesta() {
  local top
  top=$(adb shell dumpsys activity activities | tr -d '\r' | grep -m1 -E "mResumedActivity|topResumedActivity" || true)
  echo "$top" | grep -q "$PKG" || { echo "::error::foreground is not Cesta: $top"; exit 1; }
}
shot() { sleep "${2:-2}"; in_cesta; adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
find_ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull -q /sdcard/ui.xml ui.xml; python3 .github/scripts/ui.py ui.xml "$1" "$2"; }
read -r W H < <(adb shell wm size | tail -1 | grep -oE '[0-9]+x[0-9]+' | tr 'x' ' ')
swipe_up() { adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/4)) 200; }
swipe_to_previous() { adb shell input swipe $((W/6)) $((H/2)) $((W*5/6)) $((H/2)) 150; }

adb install -r "$APP"
adb install -r "$TEST"
seed=$(adb shell am instrument -w -e class app.olauncher.ScreenshotSeed "$RUNNER" | tr -d '\r')
echo "$seed" | grep -E "CESTA_SEED|OK \(|FAILURES" || true
echo "$seed" | grep -q "^OK (1 test)" || { echo "$seed"; exit 1; }

# Make Cesta the home app, hide the navigation hint bar noise, go home.
HOME_ACTIVITY=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tr -d '\r' | tail -1)
adb shell cmd package set-home-activity "$HOME_ACTIVITY"
adb shell settings put global policy_control immersive.navigation=* || true
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell input keyevent KEYCODE_BACK   # dismiss anything a first launch may have opened
adb shell input keyevent KEYCODE_HOME
shot 1-home 3

swipe_up
shot 2-all-apps

swipe_to_previous        # All apps -> last list (Tools)
shot 3-list

read -r X Y < <(find_ui id search_src_text)
adb shell input tap "$X" "$Y"
sleep 1
adb shell input text ca
shot 4-search-in-list

# Fresh drawer on All apps, long press an app that is in a list, open "Lists".
adb shell input keyevent KEYCODE_HOME
sleep 2
adb shell input keyevent KEYCODE_HOME
sleep 1
swipe_up
sleep 2
read -r X Y < <(find_ui text Settings)
adb shell input swipe "$X" "$Y" "$X" "$Y" 900
sleep 1
read -r X Y < <(find_ui text Lists)
adb shell input tap "$X" "$Y"
shot 5-lists-dialog
