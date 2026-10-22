#!/usr/bin/env bash
# Isolated live test stand for Orsik Relay: an official Hermes core and an
# Orsik Mobile Bridge, both separate from any productive Hermes install.
#
#   live-test-stack.sh setup     # once: config files and a fresh gateway key
#   live-test-stack.sh start     # provider key in, gateway + bridge up, tailnet :8443
#   live-test-stack.sh pair      # print a fresh pairing code
#   live-test-stack.sh status
#   live-test-stack.sh stop      # everything down, provider key copy removed
#
# Layout under $LIVE_ROOT (default ~/or-live, mode 0700):
#   hermes-src/   official checkout with its own venv (uv sync --frozen --extra messaging)
#   hermes-home/  HERMES_HOME: config.yaml and .env (0600)
#   gateway.env   API_SERVER_KEY only, read by the bridge (0600)
#   bridge/       bridge venv, bridge.env and state database (install.sh)
#
# The productive install ($PROD_HERMES_HOME) is only READ: one line with the
# provider key is copied file to file on start and deleted again on stop. No
# key is ever printed. Approvals stay manual so approval tests see a prompt.
set -euo pipefail
umask 077

LIVE_ROOT="${LIVE_ROOT:-$HOME/or-live}"
PROD_HERMES_HOME="${PROD_HERMES_HOME:-$HOME/.hermes}"
PROVIDER_KEY_NAME="${PROVIDER_KEY_NAME:-DEEPSEEK_API_KEY}"
GW_PORT="${GW_PORT:-8899}"
BRIDGE_PORT="${BRIDGE_PORT:-9842}"
SERVE_PORT="${SERVE_PORT:-8443}"
GW_UNIT=or-live-gateway
BRIDGE_UNIT=or-live-bridge

HOME_DIR="$LIVE_ROOT/hermes-home"
HERMES_ENV="$HOME_DIR/.env"
GATEWAY_ENV="$LIVE_ROOT/gateway.env"
BRIDGE_HOME="$LIVE_ROOT/bridge"
BRIDGE_CLI="$BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
HERMES_CLI="$LIVE_ROOT/hermes-src/.venv/bin/hermes"

die() { echo "live-test-stack: $*" >&2; exit 1; }

public_url() {
  local name
  name="$(tailscale status --self --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
  echo "https://$name:$SERVE_PORT"
}

# Replace or append KEY=VALUE in a file, as data (no sourcing).
set_kv() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "$file.XXXX")"
  { grep -v "^$key=" "$file" 2>/dev/null || true; echo "$key=$value"; } > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$file"
}

drop_key() {
  local file="$1" key="$2" tmp
  [ -f "$file" ] || return 0
  tmp="$(mktemp "$file.XXXX")"
  grep -v "^$key=" "$file" > "$tmp" || true
  chmod 600 "$tmp"
  mv "$tmp" "$file"
}

cmd_setup() {
  [ -x "$HERMES_CLI" ] || die "missing $HERMES_CLI (clone and uv sync first)"
  [ -x "$BRIDGE_CLI" ] || die "missing $BRIDGE_CLI (run bridge/install.sh with BRIDGE_HOME=$BRIDGE_HOME)"
  chmod 700 "$LIVE_ROOT"
  mkdir -p "$HOME_DIR"
  chmod 700 "$HOME_DIR"
  if [ ! -f "$HOME_DIR/config.yaml" ]; then
    cat > "$HOME_DIR/config.yaml" <<'EOF'
model:
  provider: deepseek
  default: deepseek-flash
approvals:
  mode: manual
EOF
  fi
  if ! grep -q '^API_SERVER_KEY=.' "$GATEWAY_ENV" 2>/dev/null; then
    key="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
    : > "$GATEWAY_ENV"
    set_kv "$GATEWAY_ENV" API_SERVER_KEY "$key"
    : > "$HERMES_ENV"
    set_kv "$HERMES_ENV" API_SERVER_KEY "$key"
    unset key
  fi
  set_kv "$HERMES_ENV" API_SERVER_ENABLED true
  set_kv "$HERMES_ENV" API_SERVER_HOST 127.0.0.1
  set_kv "$HERMES_ENV" API_SERVER_PORT "$GW_PORT"
  set_kv "$BRIDGE_HOME/bridge.env" BRIDGE_BIND 127.0.0.1
  set_kv "$BRIDGE_HOME/bridge.env" BRIDGE_PORT "$BRIDGE_PORT"
  set_kv "$BRIDGE_HOME/bridge.env" BRIDGE_UPSTREAM "http://127.0.0.1:$GW_PORT"
  set_kv "$BRIDGE_HOME/bridge.env" BRIDGE_HERMES_ENV "$GATEWAY_ENV"
  set_kv "$BRIDGE_HOME/bridge.env" BRIDGE_STATE_DB "$BRIDGE_HOME/state.db"
  set_kv "$BRIDGE_HOME/bridge.env" BRIDGE_PUBLIC_BASE_URL "$(public_url)"
  echo "setup done: $LIVE_ROOT"
}

wait_http() {
  local url="$1" i
  for i in $(seq 1 60); do
    curl -fsS -o /dev/null "$url" && return 0
    sleep 1
  done
  return 1
}

cmd_start() {
  [ -f "$GATEWAY_ENV" ] || die "run setup first"
  line="$(grep "^$PROVIDER_KEY_NAME=." "$PROD_HERMES_HOME/.env" || true)"
  [ -n "$line" ] || die "$PROVIDER_KEY_NAME not found in $PROD_HERMES_HOME/.env"
  set_kv "$HERMES_ENV" "$PROVIDER_KEY_NAME" "${line#*=}"
  unset line
  systemd-run --user --unit="$GW_UNIT" --collect \
    -E HERMES_HOME="$HOME_DIR" \
    "$HERMES_CLI" gateway run >/dev/null
  wait_http "http://127.0.0.1:$GW_PORT/health" || die "gateway did not come up (journalctl --user -u $GW_UNIT)"
  systemd-run --user --unit="$BRIDGE_UNIT" --collect \
    -E BRIDGE_ENV_FILE="$BRIDGE_HOME/bridge.env" \
    "$BRIDGE_CLI" >/dev/null
  wait_http "http://127.0.0.1:$BRIDGE_PORT/health" || die "bridge did not come up (journalctl --user -u $BRIDGE_UNIT)"
  tailscale serve --bg --https="$SERVE_PORT" "http://127.0.0.1:$BRIDGE_PORT" >/dev/null
  echo "up: $(public_url)"
}

cmd_stop() {
  tailscale serve --https="$SERVE_PORT" off >/dev/null 2>&1 || true
  systemctl --user stop "$BRIDGE_UNIT" 2>/dev/null || true
  systemctl --user stop "$GW_UNIT" 2>/dev/null || true
  drop_key "$HERMES_ENV" "$PROVIDER_KEY_NAME"
  echo "down"
}

cmd_status() {
  for u in "$GW_UNIT" "$BRIDGE_UNIT"; do
    printf '%-18s %s\n' "$u" "$(systemctl --user is-active "$u" 2>/dev/null || true)"
  done
  if grep -q "^$PROVIDER_KEY_NAME=" "$HERMES_ENV" 2>/dev/null; then
    echo "provider key:      present"
  else
    echo "provider key:      absent"
  fi
  tailscale serve status 2>/dev/null | grep -q ":$SERVE_PORT" && echo "tailnet:           $(public_url)" || echo "tailnet:           off"
}

cmd_pair() {
  BRIDGE_ENV_FILE="$BRIDGE_HOME/bridge.env" "$BRIDGE_CLI" admin pairing-code --base-url "$(public_url)"
}

case "${1:-}" in
  setup) cmd_setup ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  pair) cmd_pair ;;
  *) echo "usage: $0 setup|start|stop|status|pair" >&2; exit 2 ;;
esac
