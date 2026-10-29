#!/usr/bin/env bash
# Regenerates the README screenshots in docs/images/ from the real app screens
# with sample data (ReadmeScreenshotsTest, @CaptureOnly).
#
# Needs exactly one running emulator or device. For the run it sets a phone
# resolution, disables animations (so dialogs are captured fully faded in)
# and enables the System UI demo mode (fixed clock, no notification icons);
# every setting is restored on exit, also on failure.
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ADB="$ANDROID_HOME/platform-tools/adb"
DEVICE_DIR=/sdcard/Download/orsik-readme
SHOTS=(chat chat-dark approval approval-dark sessions onboarding home server files projects)

setting() { "$ADB" shell settings get global "$1" | tr -d '\r'; }
restore_setting() {
    if [ "$2" = null ]; then
        "$ADB" shell settings delete global "$1" >/dev/null
    else
        "$ADB" shell settings put global "$1" "$2"
    fi
}
demo() { "$ADB" shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

old_window="$(setting window_animation_scale)"
old_transition="$(setting transition_animation_scale)"
old_animator="$(setting animator_duration_scale)"
old_demo="$(setting sysui_demo_allowed)"

restore() {
    demo exit || true
    restore_setting sysui_demo_allowed "$old_demo"
    restore_setting window_animation_scale "$old_window"
    restore_setting transition_animation_scale "$old_transition"
    restore_setting animator_duration_scale "$old_animator"
    "$ADB" shell wm size reset
    "$ADB" shell wm density reset
}
trap restore EXIT

"$ADB" shell wm size 1080x1920
"$ADB" shell wm density 420
for key in window_animation_scale transition_animation_scale animator_duration_scale; do
    "$ADB" shell settings put global "$key" 0
done
"$ADB" shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 1200
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e mobile show -e datatype none -e level 4
demo notifications -e visible false

# The test cannot overwrite files left by an earlier run.
"$ADB" shell rm -rf "$DEVICE_DIR"

(cd "$PROJECT" && ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.ReadmeScreenshotsTest)

mkdir -p "$PROJECT/docs/images"
for name in "${SHOTS[@]}"; do
    "$ADB" pull -q "$DEVICE_DIR/$name.png" "$PROJECT/docs/images/$name.png"
done
"$ADB" shell rm -rf "$DEVICE_DIR"
ls -l "$PROJECT/docs/images"
