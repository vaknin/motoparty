#!/usr/bin/env bash
# Drive the throwaway recognizer spike on the phone and collect its logcat.
#
#   ./run.sh                # all three tests
#   ./run.sh pfd            # just the EXTRA_AUDIO_SOURCE test
#   ./run.sh usage          # just the AudioTrack usage/routing test
#   ./run.sh props          # just the device properties dump
#
# Environment:
#   PHONE=192.168.1.100:5555   adb target (default shown)
#   GRANT=1                    also grant RECORD_AUDIO before starting
#                              (run once without it, once with it: SpeechRecognizer's javadoc
#                              says the caller must hold RECORD_AUDIO, and this spike exists
#                              partly to find out whether that is enforced for a pipe source)
#   RECOGNIZER=default         use createSpeechRecognizer() instead of the on-device one
#   WAIT=30                    seconds to let the test run before scraping logcat
#   UNINSTALL=1                remove the app afterwards
#
# This script never injects taps or key events and never touches Wi-Fi, Bluetooth or audio
# settings. It installs, starts, reads logcat, and optionally uninstalls.

set -euo pipefail

cd "$(dirname "$0")"

TEST="${1:-all}"
case "$TEST" in
    pfd | usage | props | all) ;;
    *)
        echo "usage: $0 [pfd|usage|props|all]" >&2
        exit 2
        ;;
esac

PHONE="${PHONE:-192.168.1.100:5555}"
PKG=com.kivan.motoparty.spike
APK=app/build/outputs/apk/debug/app-debug.apk

if [ "$TEST" = all ]; then
    WAIT="${WAIT:-45}"
else
    WAIT="${WAIT:-25}"
fi

OUT="results/$(date +%Y-%m-%d-%H%M%S)-$TEST"
mkdir -p "$OUT"

adb_() { timeout 30 adb -s "$PHONE" "$@"; }

if [ ! -f "$APK" ]; then
    echo "missing $APK -- run ./gradlew assembleDebug first" >&2
    exit 1
fi

echo "== target $PHONE, test '$TEST', output $OUT"

echo "== install"
# No `-g`: it would grant RECORD_AUDIO on every install and the ungranted case could never run.
adb_ install -r "$APK"

if [ "${GRANT:-0}" = 1 ]; then
    echo "== grant RECORD_AUDIO"
    adb_ shell pm grant "$PKG" android.permission.RECORD_AUDIO
else
    # A reinstall keeps an earlier grant: take it back so GRANT=0 means what it says.
    echo "== RECORD_AUDIO not granted (set GRANT=1 to grant it)"
    adb_ shell pm revoke "$PKG" android.permission.RECORD_AUDIO 2>/dev/null || true
fi
adb_ shell dumpsys package "$PKG" > "$OUT/package.txt" 2>&1 || true

echo "== clear logcat"
adb_ logcat -c

echo "== start"
if [ "${RECOGNIZER:-}" = default ]; then
    adb_ shell am start -n "$PKG/.MainActivity" --es test "$TEST" --es recognizer default
else
    adb_ shell am start -n "$PKG/.MainActivity" --es test "$TEST"
fi

echo "== waiting ${WAIT}s"
sleep "$WAIT"

echo "== collect"
adb_ logcat -d -s Spike:V > "$OUT/spike.txt"

echo
echo "===== verdicts ====="
grep -E 'VERDICT|PFD PIPE|PFD CALLBACKS' "$OUT/spike.txt" || echo "(no verdict lines -- see $OUT/spike.txt)"
echo "===================="
echo "full log: $OUT/spike.txt"

if [ "${UNINSTALL:-0}" = 1 ]; then
    echo "== uninstall"
    adb_ uninstall "$PKG"
fi
