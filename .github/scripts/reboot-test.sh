#!/usr/bin/env bash
# Cesta: create app lists, reboot the emulator and check they are still there;
# then export/import settings and check that uninstalling an app cleans the lists.
set -euo pipefail
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=io.github.jesusg703.cesta.debug.test/androidx.test.runner.AndroidJUnitRunner
CLASS=app.olauncher.ListPersistenceTest
BACKUP=app.olauncher.BackupAndCleanupTest

run_test() {
  local out
  out=$(adb shell am instrument -w -r -e class "${2:-$CLASS}#$1" "$RUNNER" | tr -d '\r')
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

echo "== 4. Export and import settings"
run_test exportImportKeepsListApps "$BACKUP"

echo "== 5. Uninstalling an app deletes the lists left without apps"
run_test writeListWithTestPackage "$BACKUP"
# Instrumentation leaves the app force-stopped, and stopped apps get no system broadcasts.
# A launcher is always running on a real phone, so start it before uninstalling.
adb shell am start -W -n io.github.jesusg703.cesta.debug/app.olauncher.MainActivity >/dev/null
sleep 3
adb uninstall io.github.jesusg703.cesta.debug.test
sleep 5
adb install -r "$TEST"
# Instrumentation force-stops the app, so the broadcast may be missed; the launcher then cleans up
# when it comes to the foreground (MainActivity.onResume), as it would on a phone.
adb shell am start -W -n io.github.jesusg703.cesta.debug/app.olauncher.MainActivity >/dev/null
sleep 2
run_test verifyUninstalledListDeleted "$BACKUP"
echo "Backup and list cleanup OK"

echo "== 6. Backup through the real UI and the system file picker"
# Cesta as the default home, as on a real phone. With two home apps and no default, Pixel Launcher
# relaunches itself and covers the file picker (seen in CI: isDefaultHomeNull=true).
PKG=io.github.jesusg703.cesta.debug
HOME_ACTIVITY=$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME "$PKG" | tr -d '\r' | tail -1)
for attempt in 1 2 3 4 5; do
  adb shell cmd role add-role-holder android.app.role.HOME "$PKG" 0 2>/dev/null \
    || adb shell cmd package set-home-activity "$HOME_ACTIVITY" 2>/dev/null || true
  adb shell cmd role get-role-holders android.app.role.HOME | tr -d '\r' | grep -q "$PKG" && break
  echo "home role not set yet (attempt $attempt)"; sleep 3
done
adb shell cmd role get-role-holders android.app.role.HOME | tr -d '\r' | grep -q "$PKG" || { echo "::error::could not make Cesta the home app"; exit 1; }
adb shell input keyevent KEYCODE_HOME
sleep 3
ui_status=0
adb logcat -c
( run_test exportThenImportThroughSystemPicker app.olauncher.BackupUiTest ) || ui_status=$?
mkdir -p ui-test
adb logcat -d > ui-test/logcat.txt || true
adb shell dumpsys activity activities > ui-test/activities.txt || true
adb pull /sdcard/Android/data/io.github.jesusg703.cesta.debug/files/ui-test/. ui-test/ || true
ls ui-test
exit "$ui_status"
