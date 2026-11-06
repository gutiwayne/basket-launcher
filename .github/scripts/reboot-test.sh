#!/usr/bin/env bash
# Cesta: create app lists, reboot the emulator and check they are still there.
set -euo pipefail
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=io.github.jesusg703.cesta.debug.test/androidx.test.runner.AndroidJUnitRunner
CLASS=app.olauncher.ListPersistenceTest

run_test() {
  local out
  out=$(adb shell am instrument -w -r -e class "$CLASS#$1" "$RUNNER" | tr -d '\r')
  echo "$out" | grep -E "INSTRUMENTATION_STATUS: (test|stack)=|OK \(|FAILURES" || true
  echo "$out" | grep -q "^OK (1 test)" || { echo "$out"; echo "::error::$1 failed"; exit 1; }
}

wait_boot() {
  adb wait-for-device
  for _ in $(seq 1 90); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
    sleep 2
  done
  echo "::error::the emulator did not finish booting"; exit 1
}

adb install -r "$APP"
adb install -r "$TEST"

echo "== 1. Create lists"
run_test writeLists

echo "== 2. Reboot the emulator"
adb reboot
wait_boot
adb shell input keyevent 82 || true   # unlock screen
sleep 5

echo "== 3. Check they survived"
run_test verifyListsAfterReboot
echo "Lists intact after reboot"
