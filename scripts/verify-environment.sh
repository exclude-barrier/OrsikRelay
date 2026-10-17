#!/usr/bin/env bash
# Checks that the local toolchain can build and test Orsik Relay.
# Respects an existing JAVA_HOME / ANDROID_HOME; otherwise uses the usual
# defaults. Emulator pieces are optional (only needed for instrumented tests).
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

failures=0
ok() { printf 'OK   %s\n' "$1"; }
warn() { printf 'WARN %s\n' "$1"; }
fail() { printf 'FAIL %s\n' "$1"; failures=$((failures + 1)); }

printf '== Java ==\n'
if command -v java >/dev/null; then
    version="$(java -version 2>&1 | head -n 1)"
    case "$version" in
        *'"17'*|*' 17'*) ok "java: $version" ;;
        *) fail "java 17 required, found: $version" ;;
    esac
else
    fail "java not found (install JDK 17 or set JAVA_HOME)"
fi

printf '\n== Android SDK (%s) ==\n' "$ANDROID_HOME"
for path in \
    platforms/android-37.0/android.jar \
    build-tools/36.1.0/aapt2 \
    platform-tools/adb; do
    if [ -e "$ANDROID_HOME/$path" ]; then ok "$path"; else fail "missing $path"; fi
done

printf '\n== Emulator (optional, instrumented tests only) ==\n'
if [ -x "$ANDROID_HOME/emulator/emulator" ]; then
    ok "emulator installed"
    if ls -d "$ANDROID_HOME"/system-images/android-36/*/* >/dev/null 2>&1; then
        ok "API 36 system image present"
    else
        warn "no API 36 system image (sdkmanager 'system-images;android-36;google_apis;<abi>')"
    fi
else
    warn "emulator not installed"
fi

printf '\n== Gradle wrapper ==\n'
if (cd "$PROJECT" && ./gradlew --version >/dev/null 2>&1); then
    ok "$(cd "$PROJECT" && ./gradlew --version 2>/dev/null | grep -m1 '^Gradle')"
else
    fail "./gradlew --version failed"
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
    printf 'ENVIRONMENT-OK\n'
    exit 0
fi
printf 'ENVIRONMENT-FAIL (%d)\n' "$failures"
exit 1
