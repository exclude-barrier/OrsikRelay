#!/usr/bin/env bash
# Owner-only tool: create the productive Orsik Relay upload/release keystore.
#
# This script is meant to be run by a human on a trusted build machine, never
# by CI. It never accepts passwords as arguments: keytool asks for them
# interactively. The resulting keystore must live outside the repository and is
# backed up offline (docs/release.md).
#
# Validity: 10950 days (30 years). Android recommends at least 25 years and
# Google Play requires a key valid beyond 2033-10-22:
# https://developer.android.com/studio/publish/app-signing ("Signing
# considerations", last verified 2026-10-17).
set -euo pipefail
umask 077

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly DEFAULT_TARGET="$HOME/.config/orsik-release/release.jks"
readonly DEFAULT_ALIAS="orsik-release"
readonly VALIDITY_DAYS=10950

usage() {
    cat <<'EOF'
Usage: scripts/release-keystore.sh [--path PATH] [--alias ALIAS] [--help]

Creates a new RSA 4096 release keystore (30 years validity) with keytool.
keytool asks for the keystore password and its confirmation interactively;
there are no password arguments and no password is written anywhere.

Options:
  --path PATH    Target keystore file. Default: ~/.config/orsik-release/release.jks
  --alias ALIAS  Key alias inside the keystore. Default: orsik-release
  --help         Show this help.

The target must be outside the repository and must not exist yet. The parent
directory is created with mode 0700, the keystore with mode 0600. At the end
only the path, alias and SHA-256 certificate fingerprint are printed.
keytool asks for the keystore password a second time to read the fingerprint.
EOF
}

die() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

target="$DEFAULT_TARGET"
key_alias="$DEFAULT_ALIAS"

while [ "$#" -gt 0 ]; do
    case "$1" in
        --help|-h)
            usage
            exit 0
            ;;
        --path)
            [ "$#" -ge 2 ] || die "--path needs a value"
            target="$2"
            shift 2
            ;;
        --path=*)
            target="${1#*=}"
            shift
            ;;
        --alias)
            [ "$#" -ge 2 ] || die "--alias needs a value"
            key_alias="$2"
            shift 2
            ;;
        --alias=*)
            key_alias="${1#*=}"
            shift
            ;;
        *)
            printf 'ERROR: unknown argument: %s\n' "$1" >&2
            usage >&2
            exit 2
            ;;
    esac
done

[ -n "$target" ] || die "target path must not be empty"
[ -n "$key_alias" ] || die "alias must not be empty"

canonical_repo="$(cd "$REPO_ROOT" && pwd -P)"
canonical_target="$(python3 -c 'import os, sys; print(os.path.realpath(sys.argv[1]))' "$target")"

case "$canonical_target" in
    "$canonical_repo"|"$canonical_repo"/*)
        die "target path is inside the repository; choose a path outside"
        ;;
esac

if [ -e "$canonical_target" ] || [ -L "$canonical_target" ]; then
    die "target already exists; refusing to overwrite"
fi

target_dir="$(dirname -- "$canonical_target")"
install -d -m 0700 -- "$target_dir"
chmod 0700 -- "$target_dir"

generated_ok=0
cleanup() {
    if [ "$generated_ok" -ne 1 ] && [ -e "$canonical_target" ]; then
        rm -f -- "$canonical_target"
    fi
}
trap cleanup EXIT

# keytool prompts for the password itself; no -storepass/-keypass arguments.
if ! keytool -J-Duser.language=en -J-Duser.country=US \
        -genkeypair \
        -alias "$key_alias" \
        -keyalg RSA \
        -keysize 4096 \
        -validity "$VALIDITY_DAYS" \
        -dname "CN=Orsik Relay Release, OU=Android, O=Orsik Relay, C=DE" \
        -keystore "$canonical_target" \
        -storetype PKCS12; then
    die "keytool failed; no keystore was kept"
fi

generated_ok=1
chmod 0600 -- "$canonical_target"

# Reading the fingerprint needs the keystore password again (interactive).
list_output="$(keytool -J-Duser.language=en -J-Duser.country=US \
    -list -v \
    -alias "$key_alias" \
    -keystore "$canonical_target")"

fingerprint="$(printf '%s\n' "$list_output" | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)"
if [ -z "$fingerprint" ]; then
    die "keystore created, but reading the SHA-256 fingerprint failed"
fi

printf 'path: %s\n' "$canonical_target"
printf 'alias: %s\n' "$key_alias"
printf 'sha256: %s\n' "$fingerprint"
