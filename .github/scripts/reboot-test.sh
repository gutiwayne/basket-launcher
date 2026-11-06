#!/usr/bin/env bash
# Cesta: crea carpetas, reinicia el emulador y comprueba que siguen ahí.
set -euo pipefail
APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
RUNNER=io.github.jesusg703.cesta.debug.test/androidx.test.runner.AndroidJUnitRunner
CLASS=app.olauncher.FolderPersistenceTest

run_test() {
  local out
  out=$(adb shell am instrument -w -r -e class "$CLASS#$1" "$RUNNER" | tr -d '\r')
  echo "$out" | grep -E "INSTRUMENTATION_STATUS: (test|stack)=|OK \(|FAILURES" || true
  echo "$out" | grep -q "^OK (1 test)" || { echo "$out"; echo "::error::$1 ha fallado"; exit 1; }
}

wait_boot() {
  adb wait-for-device
  for _ in $(seq 1 90); do
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && return 0
    sleep 2
  done
  echo "::error::el emulador no terminó de arrancar"; exit 1
}

adb install -r "$APP"
adb install -r "$TEST"

echo "== 1. Crear carpetas"
run_test writeFolders

echo "== 2. Reiniciar el emulador"
adb reboot
wait_boot
adb shell input keyevent 82 || true   # desbloquear pantalla
sleep 5

echo "== 3. Comprobar que siguen ahí"
run_test verifyFoldersAfterReboot
echo "Carpetas intactas tras el reinicio"
