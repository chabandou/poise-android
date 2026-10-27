#!/usr/bin/env bash
# Dev loop on a USB-connected phone. Usage: scripts/dev.sh <build|install|test|start|play [file]|stop|metrics|logs|check|smoke>
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.poise.android
TAGS="AudioPipeline AudioCaptureService GTCRNProcessor STFT PoiseJNI AndroidRuntime DEBUG libc"
TEST_MEDIA=build/poise_test.mp4

ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null | grep -o '<node[^>]*>' || true; }

# Tap the centre of the first on-screen node matching a regex, e.g. 'text="Start now"'.
# Retries for up to ${TRIES:-5} seconds while the screen settles.
tap() {
  local b="" i
  for ((i = 0; i < ${TRIES:-5}; i++)); do
    b=$(ui | grep -m1 -E "$1" | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ') || true
    [ -n "$b" ] && break
    sleep 1
  done
  [ -n "$b" ] || { echo "not on screen: $1" >&2; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

# The start/stop button has no label, so take the app's first clickable node.
tap_main() { adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 1; tap "package=\"$PKG\".*clickable=\"true\""; }

now() { adb shell date +%s.000; }

# Wait up to 20 s for a log line matching $2 written after device time $1; fails on timeout.
wait_log() {
  timeout 20 adb logcat -T "$1" -m 1 -e "$2" -s AudioCaptureService AudioPipeline AndroidRuntime | grep -v '^---' ||
    { echo "timed out waiting for: $2" >&2; return 1; }
}

volume() { adb shell dumpsys audio | grep -A8 'STREAM_MUSIC:' | grep -m2 -E 'Muted|Current' | tr -s ' ' | paste -sd' '; }

case "${1:-}" in
  build)   ./gradlew assembleDebug -q --console=plain ;;
  install) "$0" build && adb install -r app/build/outputs/apk/debug/app-debug.apk ;;
  test)    ./gradlew connectedDebugAndroidTest --console=plain 2>&1 | grep -E "Starting|FAILED|Error|expected|Tests on|BUILD" ;;
  start)
    adb logcat -c; since=$(now)
    tap_main; sleep 1
    TRIES=2 tap 'text="Allow"' 2>/dev/null && sleep 1 || true   # first run: mic permission
    tap 'text="Start now"'                                       # cast consent
    wait_log "$since" 'Audio capture started|Failed|FATAL' ;;
  play)
    # 220 Hz tone, 1 s on / 1 s off, over light white noise. Pass your own file to test speech.
    file=${2:-$TEST_MEDIA}
    if [ ! -f "$file" ]; then
      mkdir -p build
      ffmpeg -loglevel error -y -f lavfi -i color=black:s=160x120:r=5 \
        -f lavfi -i "sine=f=220:d=20,volume='lt(mod(t,2),1)':eval=frame" -f lavfi -i "anoisesrc=d=20:a=0.05" \
        -filter_complex "[1][2]amix=inputs=2:normalize=0[a]" -map 0 -map "[a]" -shortest -c:v libx264 -c:a aac "$file"
    fi
    adb push "$file" /sdcard/Download/ >/dev/null
    adb shell am start -a android.intent.action.VIEW -d "file:///sdcard/Download/$(basename "$file")" -t video/mp4 -p com.samsung.android.video >/dev/null ;;
  stop)
    adb shell input keyevent KEYCODE_MEDIA_PAUSE
    since=$(now); tap_main
    wait_log "$since" 'Service destroyed|FATAL' ;;
  metrics)
    adb shell am start -n $PKG/.MainActivity >/dev/null; sleep 1; ui | grep -o 'text="[^"]\+"' | sed 's/text=//' | tr '\n' ' '; echo
    adb logcat -d -s AudioPipeline | grep -o 'Health: .*' | tail -1 ;;
  logs)    adb logcat -b main,crash -s $TAGS ;;
  check)
    echo "crashes:"; adb logcat -d -b crash | grep -E "$PKG|FATAL|signal" || echo "  none"
    echo "media volume: $(volume)" ;;
  smoke)
    "$0" install; "$0" start; "$0" play; sleep 10
    "$0" metrics; "$0" stop; "$0" check ;;
  *) sed -n 2p "$0"; exit 1 ;;
esac
