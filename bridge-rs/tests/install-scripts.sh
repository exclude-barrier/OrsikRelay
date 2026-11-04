#!/usr/bin/env bash
# Exercises bridge/install.sh in a throw-away HOME: install from a local
# binary, --check, refusal without --update, update with a kept previous
# binary, rollback, and a release download whose checksum does not match.
# Usage: bridge-rs/tests/install-scripts.sh <path to orsik-mobile-bridge>
set -euo pipefail

BINARY="$(realpath "$1")"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
INSTALL="$REPO/bridge/install.sh"
SANDBOX="$(mktemp -d)"
cleanup() {
  [ -n "${SERVER_PID:-}" ] && kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$SANDBOX"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

export HOME="$SANDBOX/home"
export BRIDGE_HOME="$HOME/.local/orsik-mobile-bridge"
export XDG_CONFIG_HOME="$HOME/.config"
# No real service manager is ever touched.
mkdir -p "$SANDBOX/stubs"
printf '#!/bin/sh\nexit 0\n' >"$SANDBOX/stubs/systemctl"
chmod +x "$SANDBOX/stubs/systemctl"
export PATH="$SANDBOX/stubs:$PATH"
mkdir -p "$HOME/.hermes"
printf 'API_SERVER_KEY=test-only-not-a-secret\n' >"$HOME/.hermes/.env"
chmod 600 "$HOME/.hermes/.env"

# A stand-in Hermes that answers GET /health with 200.
PORT="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])')"
mkdir -p "$SANDBOX/www" && echo ok >"$SANDBOX/www/health"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$SANDBOX/www" >/dev/null 2>&1 &
SERVER_PID=$!
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$PORT/health" >/dev/null && break; sleep 0.1; done

echo "== fresh install from a local binary"
ORSIK_BINARY="$BINARY" bash "$INSTALL" >/dev/null
[ -x "$BRIDGE_HOME/bin/orsik-mobile-bridge" ] || fail "binary not installed"
[ "$(stat -c %a "$BRIDGE_HOME")" = 700 ] || fail "home not 0700"
[ "$(stat -c %a "$BRIDGE_HOME/bridge.env")" = 600 ] || fail "bridge.env not 0600"
[ "$(readlink "$HOME/.local/bin/omb")" = "$BRIDGE_HOME/bin/orsik-mobile-bridge" ] || fail "omb shortcut"
sed -i "s|^BRIDGE_HERMES_ENV=.*|BRIDGE_HERMES_ENV=$HOME/.hermes/.env|; s|^BRIDGE_UPSTREAM=.*|BRIDGE_UPSTREAM=http://127.0.0.1:$PORT|" \
  "$BRIDGE_HOME/bridge.env"

echo "== --check"
out="$(bash "$INSTALL" --check)" || { echo "$out"; fail "--check failed"; }
echo "$out" | grep -q "PREFLIGHT: PASS" || fail "no PASS line"
echo "$out" | grep -q "^OK   config" || fail "config not checked by the binary"
sed -i 's|^BRIDGE_BIND=.*|BRIDGE_BIND=0.0.0.0|' "$BRIDGE_HOME/bridge.env"
out="$(bash "$INSTALL" --check || true)"
echo "$out" | grep -q "^FAIL bind" || { echo "$out"; fail "a public bind passed"; }
sed -i 's|^BRIDGE_BIND=.*|BRIDGE_BIND=127.0.0.1|' "$BRIDGE_HOME/bridge.env"

echo "== --check reads bridge.env like the bridge"
ENV_FILE="$BRIDGE_HOME/bridge.env"
GOOD_ENV="$(cat "$ENV_FILE")"
reset_env() { printf '%s\n' "$GOOD_ENV" >"$ENV_FILE"; chmod 600 "$ENV_FILE"; }
expect() { # <PASS|FAIL> <line pattern> -- runs --check
  local want="$1" pattern="$2" out code=0
  out="$(bash "$INSTALL" --check 2>&1)" || code=$?
  if [ "$want" = PASS ] && [ "$code" -ne 0 ]; then echo "$out"; fail "expected PASS ($pattern)"; fi
  if [ "$want" = FAIL ] && [ "$code" -eq 0 ]; then echo "$out"; fail "expected FAIL ($pattern)"; fi
  echo "$out" | grep -qE "$pattern" || { echo "$out"; fail "missing: $pattern"; }
  printf '%s' "$out" | grep -q 'test-only-not-a-secret' && fail "a secret was printed"
  return 0
}
for bind in ::1 127.1.2.3 127.255.255.254 0:0:0:0:0:0:0:1 0000::1 ::ffff:127.0.0.1; do
  reset_env; sed -i "s|^BRIDGE_BIND=.*|BRIDGE_BIND=$bind|" "$ENV_FILE"
  expect PASS "^OK   bind"
done
for bind in 0.0.0.0 ::ffff:8.8.8.8 localhost 10.0.0.1; do
  reset_env; sed -i "s|^BRIDGE_BIND=.*|BRIDGE_BIND=$bind|" "$ENV_FILE"
  expect FAIL "^FAIL bind"
done
reset_env; sed -i 's|^BRIDGE_BIND=.*|  export BRIDGE_BIND="127.0.0.1"  |' "$ENV_FILE"; expect PASS "^OK   bind"
reset_env; printf 'BRIDGE_BIND=0.0.0.0\nBRIDGE_BIND=127.0.0.1\n' >>"$ENV_FILE"; expect PASS "^OK   bind"
reset_env; printf 'BRIDGE_BIND=127.0.0.1\nBRIDGE_BIND=0.0.0.0\n' >>"$ENV_FILE"; expect FAIL "^FAIL bind"
reset_env; printf 'BRIDGE_UPSTREAM=\n' >>"$ENV_FILE"; expect FAIL "^FAIL upstream"
reset_env; sed -i 's|^BRIDGE_UPSTREAM=.*|BRIDGE_UPSTREAM=http://192.0.2.1:8642|' "$ENV_FILE"
expect FAIL "^WARN hermes_health: skipped"
reset_env; sed -i 's|^BRIDGE_UPSTREAM=.*|BRIDGE_UPSTREAM=http://127.0.0.1:1|' "$ENV_FILE"; expect FAIL "^FAIL hermes_health"
reset_env; printf 'BRIDGE_PUBLIC_BASE_URL=http://plain.example\n' >>"$ENV_FILE"; expect FAIL "^FAIL public_base_url"
reset_env; printf 'BRIDGE_PORT=70000\n' >>"$ENV_FILE"; expect FAIL "^FAIL port"
reset_env; printf 'BRIDGE_TERMINAL=yes\n' >>"$ENV_FILE"; expect FAIL "^FAIL config: BRIDGE_TERMINAL must be"
reset_env; printf 'BRIDGE_TERMINAL=1\n' >>"$ENV_FILE"
if command -v tmux >/dev/null 2>&1 && command -v setsid >/dev/null 2>&1; then
  expect PASS "^OK   config"
else
  expect FAIL "^FAIL config: BRIDGE_TERMINAL=1 needs tmux"
fi
reset_env; printf 'BRIDGE_FILES=yes\n' >>"$ENV_FILE"; expect FAIL "^FAIL config: BRIDGE_FILES must be"
reset_env; printf 'BRIDGE_FILES=1\n' >>"$ENV_FILE"; expect FAIL "^FAIL config: BRIDGE_FILES=1 needs BRIDGE_FILES_ROOT"
reset_env; printf 'BRIDGE_FILES=1\nBRIDGE_FILES_ROOT=%s\n' "$HOME" >>"$ENV_FILE"; expect FAIL "^FAIL config: BRIDGE_FILES_ROOT must not contain"
mkdir -p "$SANDBOX/projects"
reset_env; printf 'BRIDGE_FILES=1\nBRIDGE_FILES_ROOT="%s"\n' "$SANDBOX/projects" >>"$ENV_FILE"; expect PASS "^OK   config"
reset_env; expect PASS "^WARN approvals_mode"
printf 'approvals:\n  mode: manual\n' >"$HOME/.hermes/config.yaml"; expect PASS "^OK   approvals_mode"
printf 'approvals:\n  mode: smart\n' >"$HOME/.hermes/config.yaml"; expect PASS "^WARN approvals_mode: approvals.mode is smart"
rm "$HOME/.hermes/config.yaml"
printf 'API_SERVER_KEY=\n' >"$HOME/.hermes/.env.empty"; chmod 600 "$HOME/.hermes/.env.empty"
reset_env; sed -i "s|^BRIDGE_HERMES_ENV=.*|BRIDGE_HERMES_ENV=$HOME/.hermes/.env.empty|" "$ENV_FILE"; expect FAIL "^FAIL hermes_env"
reset_env; chmod 644 "$ENV_FILE"; expect FAIL "^FAIL env_mode"
reset_env
before="$(find "$BRIDGE_HOME" -printf '%p %s %m\n' | sort | sha256sum)"
bash "$INSTALL" --check >/dev/null
[ "$before" = "$(find "$BRIDGE_HOME" -printf '%p %s %m\n' | sort | sha256sum)" ] || fail "--check changed the install"

echo "== the systemd unit"
UNIT="$XDG_CONFIG_HOME/systemd/user/orsik-mobile-bridge.service"
PATH="$SANDBOX/stubs:$PATH" ORSIK_BINARY="$BINARY" bash "$INSTALL" --update --systemd >/dev/null
grep -qx "ExecStart=$BRIDGE_HOME/bin/orsik-mobile-bridge" "$UNIT" || fail "ExecStart"
grep -qx "UMask=0077" "$UNIT" || fail "UMask"
grep -qx "NoNewPrivileges=true" "$UNIT" || fail "NoNewPrivileges"
[ "$(grep -c '^ReadWritePaths=' "$UNIT")" = 1 ] && grep -qx "ReadWritePaths=\"$BRIDGE_HOME\"" "$UNIT" || fail "internal store adds a path"
EXTERNAL="$SANDBOX/state %\"dir"
mkdir -p "$EXTERNAL"
printf 'BRIDGE_STATE_DB="%s/orsik.db"\n' "$EXTERNAL" >>"$ENV_FILE"
PATH="$SANDBOX/stubs:$PATH" ORSIK_BINARY="$BINARY" bash "$INSTALL" --update --systemd >/dev/null
ESCAPED="${EXTERNAL//\\/\\\\}"; ESCAPED="${ESCAPED//\"/\\\"}"; ESCAPED="${ESCAPED//%/%%}"
grep -qxF "ReadWritePaths=\"$BRIDGE_HOME\" \"$ESCAPED\"" "$UNIT" || { grep ReadWritePaths "$UNIT"; fail "external store dir not writable/escaped"; }
reset_env

echo "== install-bridge.sh helpers"
INSTALL_BRIDGE="$REPO/bridge/install-bridge.sh"
fn() { awk -v name="$1" '$0 ~ "^"name"\\(\\) \\{" {p=1} p {print} p && /^}$/ {exit}' "$INSTALL_BRIDGE"; }
HELPERS="$SANDBOX/helpers.sh"
{ echo 'die() { echo "die: $*" >&2; exit 1; }'; fn env_value; fn set_env; } >"$HELPERS"
printf 'export A=1\n  B = "two"\nA=3\n# A=4\n' >"$SANDBOX/h.env"
[ "$(bash -c ". '$HELPERS'; env_value '$SANDBOX/h.env' A")" = 3 ] || fail "env_value last wins"
[ "$(bash -c ". '$HELPERS'; env_value '$SANDBOX/h.env' B")" = two ] || fail "env_value quotes/indent"
ENV_FILE="$SANDBOX/h.env" bash -c ". '$HELPERS'; set_env A 'x&y|z\\\\w'; set_env C new"
grep -qxF 'A=x&y|z\\w' "$SANDBOX/h.env" || { cat "$SANDBOX/h.env"; fail "set_env special characters"; }
[ "$(grep -c '^A=' "$SANDBOX/h.env")" = 2 ] || fail "set_env rewrites every occurrence"
grep -qx 'C=new' "$SANDBOX/h.env" || fail "set_env appends"
cp "$SANDBOX/h.env" "$SANDBOX/h.before"
if ENV_FILE="$SANDBOX/h.env" bash -c ". '$HELPERS'; set_env A \$'a\\nb'" 2>/dev/null; then fail "newline accepted"; fi
cmp -s "$SANDBOX/h.env" "$SANDBOX/h.before" || fail "a refused value changed the file"

echo "== install-bridge.sh end to end (stubbed git/systemctl, local binary)"
ORSIK="$SANDBOX/orsik checkout"
git init -q "$ORSIK"
cp -r "$REPO/bridge" "$ORSIK/bridge"
git -C "$ORSIK" -c user.email=t@example.invalid -c user.name=t add -A
git -C "$ORSIK" -c user.email=t@example.invalid -c user.name=t commit -qm release
git -C "$ORSIK" tag v0.0.0-test
git -C "$ORSIK" remote add origin https://github.com/exclude-barrier/OrsikRelay.git
REAL_GIT="$(command -v git)"
cat >"$SANDBOX/stubs/git" <<GIT
#!/bin/sh
# Never touch the network: fetch is a no-op, everything else is real git.
for arg in "\$@"; do [ "\$arg" = fetch ] && exit 0; done
exec "$REAL_GIT" "\$@"
GIT
chmod +x "$SANDBOX/stubs/git"
printf 'API_SERVER_PORT=%s\n' "$PORT" >>"$HOME/.hermes/.env"
printf 'approvals:\n  mode: manual\n' >"$HOME/.hermes/config.yaml"
out="$(PATH="$SANDBOX/stubs:$PATH" ORSIK_DIR="$ORSIK" ORSIK_REF=v0.0.0-test ORSIK_BINARY="$BINARY" \
  bash "$INSTALL_BRIDGE" 2>&1)" || { echo "$out"; fail "install-bridge.sh failed"; }
ls "$BRIDGE_HOME"/state.db.bak-* >/dev/null 2>&1 || true
[ "$(cat "$BRIDGE_HOME/installed-commit")" = "$(git -C "$ORSIK" rev-parse HEAD)" ] || fail "commit marker"
[ "$(stat -c %a "$BRIDGE_HOME/installed-commit")" = 600 ] || fail "commit marker mode"
grep -qx "BRIDGE_UPSTREAM=http://127.0.0.1:$PORT" "$ENV_FILE" || fail "upstream from API_SERVER_PORT"
echo "$out" | grep -qF "admin pairing-code" || fail "pairing hint"
echo "$out" | grep -q "PREFLIGHT: PASS" || { echo "$out"; fail "check inside install-bridge.sh"; }
ENV_FILE="$BRIDGE_HOME/bridge.env"

echo "== a second install needs --update"
if ORSIK_BINARY="$BINARY" bash "$INSTALL" >/dev/null 2>&1; then fail "reinstall without --update"; fi

echo "== update keeps the previous binary, rollback restores it"
cp "$BINARY" "$SANDBOX/marked"
printf '\n# marked build\n' >>"$SANDBOX/marked"
ORSIK_BINARY="$SANDBOX/marked" bash "$INSTALL" --update >/dev/null
cmp -s "$BRIDGE_HOME/bin/orsik-mobile-bridge" "$SANDBOX/marked" || fail "update did not install"
cmp -s "$BRIDGE_HOME/bin/orsik-mobile-bridge.previous" "$BINARY" || fail "previous binary not kept"
grep -q "BRIDGE_UPSTREAM=http://127.0.0.1:$PORT" "$BRIDGE_HOME/bridge.env" || fail "bridge.env overwritten"
bash "$INSTALL" --rollback >/dev/null
cmp -s "$BRIDGE_HOME/bin/orsik-mobile-bridge" "$BINARY" || fail "rollback did not restore"

echo "== a binary that does not run is refused"
printf '#!/bin/sh\nexit 3\n' >"$SANDBOX/broken" && chmod +x "$SANDBOX/broken"
if ORSIK_BINARY="$SANDBOX/broken" bash "$INSTALL" --update >/dev/null 2>&1; then fail "broken binary installed"; fi
cmp -s "$BRIDGE_HOME/bin/orsik-mobile-bridge" "$BINARY" || fail "a refused update replaced the binary"

echo "== a checksum mismatch installs nothing"
mkdir -p "$SANDBOX/fakebin" "$SANDBOX/release"
cp "$BINARY" "$SANDBOX/release/orsik-mobile-bridge"
tar -czf "$SANDBOX/release/orsik-mobile-bridge-x86_64-linux.tar.gz" -C "$SANDBOX/release" orsik-mobile-bridge
printf '%064d  orsik-mobile-bridge-x86_64-linux.tar.gz\n' 0 >"$SANDBOX/release/orsik-mobile-bridge-x86_64-linux.tar.gz.sha256"
# curl stand-in: serves the release files from the sandbox, never the network.
cat >"$SANDBOX/fakebin/curl" <<CURL
#!/bin/sh
out=""; url=""
while [ \$# -gt 0 ]; do
  case "\$1" in -o) out="\$2"; shift 2 ;; http*) url="\$1"; shift ;; *) shift ;; esac
done
[ -n "\$out" ] || exec /usr/bin/curl "\$@"
cp "$SANDBOX/release/\${url##*/}" "\$out"
CURL
chmod +x "$SANDBOX/fakebin/curl"
if PATH="$SANDBOX/fakebin:$PATH" ORSIK_TAG=v0.0.0-test bash "$INSTALL" --update >"$SANDBOX/mismatch.log" 2>&1; then
  fail "a wrong checksum was accepted"
fi
grep -q "checksum mismatch" "$SANDBOX/mismatch.log" || { cat "$SANDBOX/mismatch.log"; fail "no mismatch message"; }
cmp -s "$BRIDGE_HOME/bin/orsik-mobile-bridge" "$BINARY" || fail "a mismatch replaced the binary"
( cd "$SANDBOX/release" && sha256sum orsik-mobile-bridge-x86_64-linux.tar.gz >orsik-mobile-bridge-x86_64-linux.tar.gz.sha256 )
PATH="$SANDBOX/fakebin:$PATH" ORSIK_TAG=v0.0.0-test bash "$INSTALL" --update >/dev/null
cmp -s "$BRIDGE_HOME/bin/orsik-mobile-bridge" "$BINARY" || fail "verified download not installed"

echo "== the omb shortcut never replaces another command"
rm -f "$HOME/.local/bin/omb"
printf '#!/bin/sh\necho mine\n' >"$HOME/.local/bin/omb"; chmod +x "$HOME/.local/bin/omb"
ORSIK_BINARY="$BINARY" bash "$INSTALL" --update >/dev/null 2>&1
[ "$("$HOME/.local/bin/omb")" = mine ] || fail "a foreign omb was replaced"
rm -f "$HOME/.local/bin/omb"
ln -s "$BRIDGE_HOME/venv/bin/omb" "$HOME/.local/bin/omb"
ORSIK_BINARY="$BINARY" bash "$INSTALL" --update >/dev/null 2>&1
[ "$(readlink "$HOME/.local/bin/omb")" = "$BRIDGE_HOME/bin/orsik-mobile-bridge" ] || fail "the old venv link was not replaced"

echo "== uninstall"
bash "$INSTALL" --uninstall >/dev/null
echo "install scripts: all checks passed"
