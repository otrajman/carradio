#!/usr/bin/env bash
# Launch the local Android emulator and install the current debug build.
# Usage: ./run-emulator.sh            (boots emulator, installs Car Radio when ready)
#        ./run-emulator.sh peloton    (boots emulator, installs PelotonCB)
#        ./run-emulator.sh --no-app   (emulator only)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
export JAVA_HOME="$ROOT/.toolchain/jdk-17.0.20+8"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

"$ANDROID_HOME/emulator/emulator" -avd carradio -gpu host -no-snapshot-load &
EMU_PID=$!

FLAVOR=carradio
PACKAGE=com.carradio.app
if [[ "${1:-}" == "peloton" ]]; then
  FLAVOR=peloton
  PACKAGE=com.pelotoncb.app
fi

if [[ "${1:-}" != "--no-app" ]]; then
  echo "Waiting for emulator to boot…"
  adb wait-for-device
  until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do sleep 2; done
  APK="$ROOT/android/app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-debug.apk"
  if [[ ! -f "$APK" ]]; then
    echo "No debug APK found — building…"
    (cd "$ROOT/android" && "$ROOT/.toolchain/gradle-8.9/bin/gradle" --no-daemon "assemble${FLAVOR^}Debug")
  fi
  adb install -r "$APK"
  adb shell monkey -p "$PACKAGE" 1 >/dev/null
  echo "$PACKAGE installed and launched."
fi
wait $EMU_PID
