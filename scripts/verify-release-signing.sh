#!/usr/bin/env bash
# Verifies the fail-closed release signing contract and a real signed release
# build with a throwaway keystore outside the repository (docs/release.md).
#
# (a) no ORSIK_RELEASE_* variables  -> unsigned release builds
# (b) partially set variables       -> build aborts, message has no values
# (c) keystore inside the repository -> build aborts
# (d) throwaway keystore outside    -> signed AAB/APK, jarsigner/apksigner OK
#
# The throwaway keystore and its password live only in a mktemp directory and
# shell variables; a trap removes them. No password is printed or logged.
set -uo pipefail
export LC_ALL=C

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly GRADLEW="$REPO_ROOT/gradlew"
readonly ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
readonly APKSIGNER="$ANDROID_HOME/build-tools/36.1.0/apksigner"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/orsik-release-verify.XXXXXX")"
readonly WORK
readonly GRADLE_LOG="$WORK/gradle.log"
INSIDE_KEYSTORE=""
failures=0

cleanup() {
    rm -rf -- "$WORK"
    if [ -n "$INSIDE_KEYSTORE" ]; then
        rm -f -- "$INSIDE_KEYSTORE"
    fi
}
trap cleanup EXIT

pass() {
    printf 'PASS: %s\n' "$1"
}

fail() {
    printf 'FAIL: %s\n' "$1" >&2
    failures=$((failures + 1))
}

show_gradle_log() {
    printf '%s\n' '--- gradle output (tail) ---' >&2
    tail -n 20 "$GRADLE_LOG" >&2 || true
    printf '%s\n' '--- end gradle output ---' >&2
}

release_outputs() {
    rm -rf -- "$REPO_ROOT/app/build/outputs/apk/release" \
        "$REPO_ROOT/app/build/outputs/bundle/release"
}

run_unsigned() {
    (
        cd "$REPO_ROOT" &&
            env \
                -u ORSIK_RELEASE_STORE_FILE \
                -u ORSIK_RELEASE_STORE_PASSWORD \
                -u ORSIK_RELEASE_KEY_ALIAS \
                -u ORSIK_RELEASE_KEY_PASSWORD \
                "$GRADLEW" --offline --no-daemon --console=plain "$@"
    ) >"$GRADLE_LOG" 2>&1
}

run_with_env() {
    # $1..$4: store file, store password, alias, key password; rest: tasks.
    local store_file="$1" store_password="$2" key_alias="$3" key_password="$4"
    shift 4
    (
        cd "$REPO_ROOT" &&
            env \
                -u ORSIK_RELEASE_STORE_FILE \
                -u ORSIK_RELEASE_STORE_PASSWORD \
                -u ORSIK_RELEASE_KEY_ALIAS \
                -u ORSIK_RELEASE_KEY_PASSWORD \
                ORSIK_RELEASE_STORE_FILE="$store_file" \
                ORSIK_RELEASE_STORE_PASSWORD="$store_password" \
                ORSIK_RELEASE_KEY_ALIAS="$key_alias" \
                ORSIK_RELEASE_KEY_PASSWORD="$key_password" \
                "$GRADLEW" --offline --no-daemon --console=plain "$@"
    ) >"$GRADLE_LOG" 2>&1
}

find_aab() {
    find "$REPO_ROOT/app/build/outputs/bundle/release" -maxdepth 1 \
        -name '*.aab' -print -quit 2>/dev/null
}

find_apk() {
    local unsigned="$REPO_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
    if [ -f "$unsigned" ]; then
        printf '%s\n' "$unsigned"
        return 0
    fi
    find "$REPO_ROOT/app/build/outputs/apk/release" -maxdepth 1 \
        -name '*.apk' -print -quit 2>/dev/null
}

[ -x "$GRADLEW" ] || { printf 'ERROR: gradlew not executable\n' >&2; exit 2; }
[ -x "$APKSIGNER" ] || { printf 'ERROR: apksigner not found: %s\n' "$APKSIGNER" >&2; exit 2; }
command -v keytool >/dev/null || { printf 'ERROR: keytool not found\n' >&2; exit 2; }
command -v jarsigner >/dev/null || { printf 'ERROR: jarsigner not found\n' >&2; exit 2; }

printf '== (a) no signing variables: unsigned release ==\n'
release_outputs
if run_unsigned :app:bundleRelease :app:assembleRelease; then
    aab="$(find_aab)"
    apk="$(find_apk)"
    if [ -z "$aab" ] || [ -z "$apk" ]; then
        fail "unsigned build produced no AAB/APK"
        show_gradle_log
    else
        jarsigner -verify "$aab" >"$WORK/jarsigner-unsigned.log" 2>&1
        jarsigner_rc=$?
        if ! grep -qi 'jar is unsigned' "$WORK/jarsigner-unsigned.log"; then
            fail "jarsigner did not report an unsigned AAB (exit $jarsigner_rc)"
            tail -n 5 "$WORK/jarsigner-unsigned.log" >&2 || true
        elif "$APKSIGNER" verify "$apk" >"$WORK/apksigner-unsigned.log" 2>&1; then
            fail "unsigned APK unexpectedly verifies as signed"
        else
            pass "unsigned release build (AAB and APK are unsigned)"
        fi
    fi
else
    fail "unsigned release build failed"
    show_gradle_log
fi

printf '== (b) partially set variables: build aborts without values ==\n'
partial_store="$WORK/partial-store-sentinel.jks"
partial_alias="partial-alias-sentinel"
if (
    cd "$REPO_ROOT" &&
        env \
            -u ORSIK_RELEASE_STORE_PASSWORD \
            -u ORSIK_RELEASE_KEY_PASSWORD \
            -u ORSIK_RELEASE_STORE_FILE \
            -u ORSIK_RELEASE_KEY_ALIAS \
            ORSIK_RELEASE_STORE_FILE="$partial_store" \
            ORSIK_RELEASE_KEY_ALIAS="$partial_alias" \
            "$GRADLEW" --offline --no-daemon --console=plain :app:bundleRelease
) >"$GRADLE_LOG" 2>&1; then
    fail "build did not abort with partially set signing variables"
else
    missing=0
    grep -q 'Release signing needs all of' "$GRADLE_LOG" || missing=1
    for name in ORSIK_RELEASE_STORE_FILE ORSIK_RELEASE_STORE_PASSWORD \
        ORSIK_RELEASE_KEY_ALIAS ORSIK_RELEASE_KEY_PASSWORD; do
        grep -q "$name" "$GRADLE_LOG" || missing=1
    done
    if grep -q 'partial-store-sentinel' "$GRADLE_LOG" ||
        grep -q 'partial-alias-sentinel' "$GRADLE_LOG"; then
        missing=1
    fi
    if [ "$missing" -eq 0 ]; then
        pass "partial variables abort the build without printing values"
    else
        fail "partial-variable abort message is wrong or leaks a value"
        show_gradle_log
    fi
fi

printf '== (c) keystore inside the repository: build aborts ==\n'
INSIDE_KEYSTORE="$REPO_ROOT/.verify-release-signing-inside-$$.jks"
if (
    cd "$REPO_ROOT" &&
        env \
            -u ORSIK_RELEASE_STORE_FILE \
            -u ORSIK_RELEASE_STORE_PASSWORD \
            -u ORSIK_RELEASE_KEY_ALIAS \
            -u ORSIK_RELEASE_KEY_PASSWORD \
            ORSIK_RELEASE_STORE_FILE="$INSIDE_KEYSTORE" \
            ORSIK_RELEASE_STORE_PASSWORD="inside-store-password-sentinel" \
            ORSIK_RELEASE_KEY_ALIAS="inside-alias-sentinel" \
            ORSIK_RELEASE_KEY_PASSWORD="inside-key-password-sentinel" \
            "$GRADLEW" --offline --no-daemon --console=plain :app:bundleRelease
) >"$GRADLE_LOG" 2>&1; then
    fail "build did not abort for a keystore inside the repository"
else
    if grep -q 'Release keystore must live outside the repository' "$GRADLE_LOG" &&
        ! grep -Fq "$INSIDE_KEYSTORE" "$GRADLE_LOG"; then
        pass "repository keystore path aborts without printing the path"
    else
        fail "repository keystore abort message is wrong or leaks the path"
        show_gradle_log
    fi
fi

printf '== (d) throwaway keystore outside the repository: signed release ==\n'
throwaway_store="$WORK/throwaway-release.jks"
throwaway_alias="orsik-release-verify"
throwaway_password="$(openssl rand -hex 24)"

if ! printf '%s\n%s\n' "$throwaway_password" "$throwaway_password" |
    keytool -J-Duser.language=en -J-Duser.country=US \
        -genkeypair \
        -alias "$throwaway_alias" \
        -keyalg RSA \
        -keysize 4096 \
        -validity 10950 \
        -dname "CN=Orsik Relay Verify, OU=Android, O=Orsik Relay, C=DE" \
        -keystore "$throwaway_store" \
        -storetype PKCS12 >"$WORK/keytool.log" 2>&1; then
    fail "could not create the throwaway keystore"
    tail -n 10 "$WORK/keytool.log" >&2 || true
else
    chmod 0600 -- "$throwaway_store"
    release_outputs
    if run_with_env "$throwaway_store" "$throwaway_password" "$throwaway_alias" \
        "$throwaway_password" :app:bundleRelease :app:assembleRelease; then
        aab="$(find_aab)"
        apk="$(find_apk)"
        if [ -z "$aab" ] || [ -z "$apk" ]; then
            fail "signed build produced no AAB/APK"
            show_gradle_log
        elif ! "$APKSIGNER" verify --print-certs "$apk" >"$WORK/apksigner.log" 2>&1; then
            fail "apksigner verify failed for the signed APK"
            tail -n 10 "$WORK/apksigner.log" >&2 || true
        elif ! grep -q 'Signer #1 certificate DN' "$WORK/apksigner.log" ||
            ! grep -q 'SHA-256 digest' "$WORK/apksigner.log"; then
            fail "apksigner did not print signer certificate details"
        elif ! jarsigner -verify "$aab" >"$WORK/jarsigner.log" 2>&1; then
            fail "jarsigner -verify failed for the signed AAB"
            tail -n 10 "$WORK/jarsigner.log" >&2 || true
        elif ! grep -qi 'jar verified' "$WORK/jarsigner.log"; then
            fail "jarsigner did not report a verified AAB"
        else
            pass "signed release build verifies (APK certificate, AAB signature)"
        fi
    else
        fail "signed release build failed"
        show_gradle_log
    fi
fi

printf '\n'
if [ "$failures" -eq 0 ]; then
    printf 'RESULT: PASS (4/4 checks)\n'
    exit 0
fi
printf 'RESULT: FAIL (%d failed)\n' "$failures" >&2
exit 1
