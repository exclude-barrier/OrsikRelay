#!/usr/bin/env bash
set -euo pipefail

HOME_DIR="/home/user"
PROJECT="$HOME_DIR/Projekte/hermes-android"
export JAVA_HOME="$HOME_DIR/.local/opt/temurin-17"
export ANDROID_HOME="$HOME_DIR/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$HOME_DIR/.local/opt/gradle-9.7.1/bin:$HOME_DIR/.local/bin:$PATH"

printf '== Java ==\n'
java -version
javac -version

printf '\n== Gradle ==\n'
gradle --version

printf '\n== Android tools ==\n'
adb version
sdkmanager --version
emulator -accel-check

printf '\n== Required SDK packages ==\n'
python3 - <<'PY'
from pathlib import Path
required = [
    Path.home() / 'Android/Sdk/platform-tools/adb',
    Path.home() / 'Android/Sdk/platforms/android-36/android.jar',
    Path.home() / 'Android/Sdk/build-tools/36.1.0/aapt2',
    Path.home() / 'Android/Sdk/emulator/emulator',
    Path.home() / 'Android/Sdk/system-images/android-36/google_apis/x86_64/package.xml',
]
missing = [str(path) for path in required if not path.exists()]
if missing:
    raise SystemExit('Missing SDK paths: ' + ', '.join(missing))
print('SDK-PACKAGES-OK')
PY

printf '\n== AVD ==\n'
avdmanager list avd

printf '\n== Repository ==\n'
cd "$PROJECT"
git status --short
git diff --check

printf '\nENVIRONMENT-OK\n'
