#!/usr/bin/env bash
# Orsik Mobile Bridge installer (no curl|bash, no core patching).
#
# Installs the bridge into a DEDICATED virtual environment from a local,
# reviewed source checkout. It never modifies Hermes core files, never opens a
# public port, and never overwrites an existing install unless --update is given.
#
# Usage:
#   ./install.sh [--update] [--systemd] [/path/to/bridge/source]
#   ./install.sh --check              # report on an install, change nothing
#
# Rollback / uninstall:
#   ./install.sh --uninstall          # stops+removes the user unit
#   rm -rf "$BRIDGE_HOME"             # removes the venv + state database
set -euo pipefail

SRC=""
UPDATE=0
SYSTEMD=0
UNINSTALL=0
CHECK=0
for arg in "$@"; do
  case "$arg" in
    --update) UPDATE=1 ;;
    --systemd) SYSTEMD=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --check) CHECK=1 ;;
    *) SRC="$arg" ;;
  esac
done
SRC="${SRC:-$(cd "$(dirname "$0")" && pwd)}"
BRIDGE_HOME="${BRIDGE_HOME:-$HOME/.local/orsik-mobile-bridge}"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_NAME="orsik-mobile-bridge.service"

# --check is read-only: it inspects an existing installation, reports one line
# per check and exits non-zero if anything FAILed. It never creates, moves or
# rewrites a file, and it never prints a key or token value.
if [ "$CHECK" -eq 1 ]; then
  ENV_FILE="$BRIDGE_HOME/bridge.env"
  FAILED=0

  chk_ok() { printf 'OK   %s\n' "$1"; }
  chk_warn() { printf 'WARN %s: %s\n' "$1" "$2"; }
  chk_fail() {
    printf 'FAIL %s: %s\n' "$1" "$2"
    FAILED=$((FAILED + 1))
  }

  # Read bridge.env as data: line by line KEY=VALUE, comments and blank lines
  # ignored. Never sourced, never eval'd - the file is operator input.
  env_value() {
    local want="$1" line key value
    [ -f "$ENV_FILE" ] || return 0
    while IFS= read -r line || [ -n "$line" ]; do
      case "$line" in
        \#*) continue ;;
      esac
      case "$line" in
        *=*) ;;
        *) continue ;;
      esac
      key="${line%%=*}"
      value="${line#*=}"
      key="${key%"${key##*[![:space:]]}"}"
      key="${key#"${key%%[![:space:]]*}"}"
      key="${key#export }"
      if [ "$key" = "$want" ]; then
        # Same value rules as the bridge (_env_value): trimmed, matching
        # outer quotes removed, an unquoted value ends at " #".
        value="${value#"${value%%[![:space:]]*}"}"
        value="${value%"${value##*[![:space:]]}"}"
        case "$value" in
          \"*\") value="${value:1:${#value}-2}" ;;
          \'*\') value="${value:1:${#value}-2}" ;;
          *" #"*) value="${value%% #*}"; value="${value%"${value##*[![:space:]]}"}" ;;
        esac
        printf '%s\n' "$value"
        return 0
      fi
    done <"$ENV_FILE"
  }

  mode_of() { stat -c %a "$1" 2>/dev/null || true; }

  # 1. The bridge home must stay private to the operator.
  if [ ! -d "$BRIDGE_HOME" ]; then
    chk_fail home_mode "$BRIDGE_HOME does not exist"
  else
    mode="$(mode_of "$BRIDGE_HOME")"
    if [ "$mode" = "700" ]; then
      chk_ok home_mode
    else
      chk_fail home_mode "$BRIDGE_HOME has mode ${mode:-unknown}, expected 700"
    fi
  fi

  # 2. bridge.env carries no secrets, but it points at them: keep it 0600.
  if [ ! -f "$ENV_FILE" ]; then
    chk_fail env_mode "bridge.env is missing"
  else
    mode="$(mode_of "$ENV_FILE")"
    if [ "$mode" = "600" ]; then
      chk_ok env_mode
    else
      chk_fail env_mode "bridge.env has mode ${mode:-unknown}, expected 600"
    fi
  fi

  # 3. The installed operator CLI.
  CLI="$BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
  if [ -x "$CLI" ]; then
    chk_ok venv
  else
    chk_fail venv "venv/bin/orsik-mobile-bridge is not executable"
  fi

  # 4. The bind address must stay on loopback (empty = bridge default).
  BIND_VALUE="$(env_value BRIDGE_BIND)"
  case "$BIND_VALUE" in
    ""|127.0.0.1|::1) chk_ok bind ;;
    *) chk_fail bind "BRIDGE_BIND is not loopback (127.0.0.1 or ::1)" ;;
  esac

  # 5. The upstream must be a plain-HTTP loopback IP with an explicit port.
  UPSTREAM="$(env_value BRIDGE_UPSTREAM)"
  # A present but empty BRIDGE_UPSTREAM is refused by the bridge (the master
  # key never goes to a guessed target); only a missing line means the default.
  if [ -z "$UPSTREAM" ] && grep -qE '^[[:space:]]*(export[[:space:]]+)?BRIDGE_UPSTREAM=' "$ENV_FILE" 2>/dev/null; then
    UPSTREAM="(empty)"
  fi
  UPSTREAM="${UPSTREAM:-http://127.0.0.1:8642}"
  LOOPBACK_HTTP='^http://(127\.[0-9]+\.[0-9]+\.[0-9]+|\[::1\]):[0-9]+$'
  UPSTREAM_OK=0
  if [[ "$UPSTREAM" =~ $LOOPBACK_HTTP ]]; then
    UPSTREAM_OK=1
    chk_ok upstream
  else
    chk_fail upstream "must be loopback http IP with port"
  fi

  # 6. Hermes env file: 0600 and holding a non-empty API_SERVER_KEY. The value
  # itself is only ever passed to grep, never captured or printed.
  HERMES_ENV="$(env_value BRIDGE_HERMES_ENV)"
  if [ -z "$HERMES_ENV" ]; then
    chk_fail hermes_env "BRIDGE_HERMES_ENV is not set"
  elif [ ! -f "$HERMES_ENV" ]; then
    chk_fail hermes_env "BRIDGE_HERMES_ENV does not point at a file"
  else
    mode="$(mode_of "$HERMES_ENV")"
    if [ "$mode" != "600" ]; then
      chk_fail hermes_env "Hermes env file has mode ${mode:-unknown}, expected 600"
    elif grep -qE '^API_SERVER_KEY=.+' "$HERMES_ENV"; then
      chk_ok hermes_env
    else
      chk_fail hermes_env "Hermes env file has no API_SERVER_KEY line"
    fi
  fi

  # 6b. The phone only sees approvals the core escalates. With the core default
  # (approvals.mode: smart) an auxiliary model may auto-approve a dangerous
  # command, so anything but `manual` in the Hermes home's config.yaml (next to
  # the env file) is reported. A WARN, not a FAIL: the bridge cannot enforce it.
  if [ -n "$HERMES_ENV" ] && [ -f "$HERMES_ENV" ]; then
    HERMES_CFG="$(dirname "$HERMES_ENV")/config.yaml"
    if [ ! -f "$HERMES_CFG" ]; then
      chk_warn approvals_mode "no config.yaml next to the Hermes env file (core default: smart)"
    else
      approvals_mode="$(awk '
        /^approvals:/ { in_block = 1; next }
        in_block && /^[^[:space:]#]/ { in_block = 0 }
        in_block && /^[[:space:]]+mode:/ {
          sub(/^[[:space:]]+mode:[[:space:]]*/, ""); sub(/[[:space:]]*#.*/, "")
          gsub(/["'"'"']/, ""); print; exit
        }' "$HERMES_CFG")"
      if [ "$approvals_mode" = "manual" ]; then
        chk_ok approvals_mode
      else
        chk_warn approvals_mode "approvals.mode is ${approvals_mode:-unset (core default: smart)}, expected manual"
      fi
    fi
  fi

  # 7. Without a public base URL there is no deep link and no QR.
  BASE_URL="$(env_value BRIDGE_PUBLIC_BASE_URL)"
  case "$BASE_URL" in
    "") chk_warn public_base_url "no deep link/QR" ;;
    https://*) chk_ok public_base_url ;;
    *) chk_fail public_base_url "must start with https://" ;;
  esac

  # 8. A listener on the bridge port usually means the bridge is already up.
  PORT="$(env_value BRIDGE_PORT)"
  PORT="${PORT:-${BRIDGE_PORT:-9842}}"
  if ! [[ "$PORT" =~ ^[0-9]+$ ]] || [ "$PORT" -lt 1 ] || [ "$PORT" -gt 65535 ]; then
    chk_fail port "BRIDGE_PORT must be a number between 1 and 65535"
  elif ! command -v ss >/dev/null 2>&1; then
    chk_warn port "ss not found, listeners unchecked"
  elif ss -H -ltn "sport = :$PORT" 2>/dev/null \
    | grep -qE "(^|[[:space:]])(127\.[0-9]+\.[0-9]+\.[0-9]+|\[::1\]|0\.0\.0\.0|\[::\]|\*):$PORT([[:space:]]|$)"; then
    chk_warn port "port in use (running bridge?)"
  else
    chk_ok port
  fi

  # 9. Hermes health, only meaningful with a valid loopback upstream.
  if [ "$UPSTREAM_OK" -ne 1 ]; then
    chk_warn hermes_health "skipped: upstream is not loopback http"
  else
    code="$(curl -sS -m 3 -o /dev/null -w '%{http_code}' "$UPSTREAM/health" 2>/dev/null || true)"
    if [ "$code" = "200" ]; then
      chk_ok hermes_health
    else
      chk_fail hermes_health "Hermes api_server not reachable"
    fi
  fi

  # 10. The settings the bridge checks at start (state DB path, service list),
  # validated by the installed bridge itself so both always agree.
  if [ -x "$BRIDGE_HOME/venv/bin/python" ]; then
    if cfg_error="$(BRIDGE_STATE_DB="$(env_value BRIDGE_STATE_DB)" \
      BRIDGE_HOST_SERVICES="$(env_value BRIDGE_HOST_SERVICES)" \
      BRIDGE_TERMINAL="$(env_value BRIDGE_TERMINAL)" \
      BRIDGE_FILES="$(env_value BRIDGE_FILES)" \
      BRIDGE_FILES_ROOT="$(env_value BRIDGE_FILES_ROOT)" \
      BRIDGE_HERMES_ENV="$(env_value BRIDGE_HERMES_ENV)" \
      BRIDGE_ENV_FILE="$ENV_FILE" \
      DEFAULT_DB="$BRIDGE_HOME/state.db" \
      "$BRIDGE_HOME/venv/bin/python" - 2>&1 <<'PYCHECK'
import os, sys
from orsik_mobile_bridge import hostmetrics, server, terminal
db = os.environ.get("BRIDGE_STATE_DB") or os.environ["DEFAULT_DB"]
try:
    hostmetrics.parse_services(os.environ.get("BRIDGE_HOST_SERVICES") or None)
    if terminal.parse_enabled(os.environ.get("BRIDGE_TERMINAL")):
        terminal.default_command()
    server._files_root_from_env({
        key: os.environ.get(key, "")
        for key in ("BRIDGE_FILES", "BRIDGE_FILES_ROOT", "BRIDGE_HERMES_ENV", "BRIDGE_ENV_FILE")
    } | {"BRIDGE_STATE_DB": db})
except SystemExit as exc:
    sys.exit(str(exc))
parent = os.path.dirname(os.path.abspath(os.path.expanduser(db)))
probe = parent
while not os.path.exists(probe):
    probe = os.path.dirname(probe)
if not os.access(probe, os.W_OK | os.X_OK):
    sys.exit("BRIDGE_STATE_DB is not writable: " + parent)
PYCHECK
)"; then
      chk_ok config
    else
      chk_fail config "$cfg_error"
    fi
  fi

  if [ "$FAILED" -eq 0 ]; then
    echo "PREFLIGHT: PASS"
    exit 0
  fi
  echo "PREFLIGHT: FAIL ($FAILED failed)"
  exit 1
fi

if [ "$UNINSTALL" -eq 1 ]; then
  if [ -f "$UNIT_DIR/$UNIT_NAME" ] && ! systemctl --user disable --now "$UNIT_NAME" 2>/dev/null; then
    echo "install.sh: could not stop $UNIT_NAME (no user session?); stop the bridge process yourself" >&2
  fi
  rm -f "$UNIT_DIR/$UNIT_NAME"
  systemctl --user daemon-reload 2>/dev/null || true
  echo "Removed the systemd user unit. The venv/state remain under $BRIDGE_HOME;"
  echo "delete them with: rm -rf \"$BRIDGE_HOME\""
  exit 0
fi

if [ ! -f "$SRC/pyproject.toml" ]; then
  echo "install.sh: $SRC is not a bridge source tree (missing pyproject.toml)" >&2
  exit 1
fi

# The exact dependency set is frozen in uv.lock; without it the install would
# silently resolve whatever PyPI serves today.
if [ ! -f "$SRC/uv.lock" ]; then
  echo "install.sh: $SRC/uv.lock is missing; refusing an unreproducible install" >&2
  exit 1
fi

# Prerequisites are checked before anything is created: a half-installed
# BRIDGE_HOME is worse than a refusal.
if ! command -v uv >/dev/null 2>&1; then
  echo "install.sh: uv is required but not on PATH (see docs/bridge-installation.md)" >&2
  exit 1
fi

# Fail early when the requested interpreter is missing; uv would otherwise
# download one in the middle of the install.
if ! uv python find "${BRIDGE_PYTHON:-3.14}" >/dev/null 2>&1; then
  echo "install.sh: no usable Python ${BRIDGE_PYTHON:-3.14} (try: uv python install ${BRIDGE_PYTHON:-3.14})" >&2
  exit 1
fi

# Do not clobber an existing install unless the operator asked to update it.
if [ -d "$BRIDGE_HOME/venv" ] && [ "$UPDATE" -ne 1 ]; then
  echo "install.sh: $BRIDGE_HOME already exists; re-run with --update to refresh it" >&2
  exit 1
fi

mkdir -p "$BRIDGE_HOME"
chmod 700 "$BRIDGE_HOME"

# A dedicated venv keeps the bridge's dependencies isolated from Hermes.
# Any interpreter the package supports (>=3.11) works; override with BRIDGE_PYTHON.
# --update rebuilds the venv from scratch (uv refuses an existing one otherwise);
# bridge.env and the state database live outside it and are kept.
VENV_FLAGS=()
if [ "$UPDATE" -eq 1 ]; then
  VENV_FLAGS=(--clear)
  # Keep the working venv until the new one is complete: a failed update
  # (stale lock, network, disk full) restores it instead of leaving no CLI.
  rm -rf "$BRIDGE_HOME/venv.previous"
  if [ -d "$BRIDGE_HOME/venv" ]; then
    # A failed copy aborts here, before anything touched the working venv.
    cp -a "$BRIDGE_HOME/venv" "$BRIDGE_HOME/venv.previous"
  fi
  restore_venv() {
    if [ -d "$BRIDGE_HOME/venv.previous" ]; then
      rm -rf "$BRIDGE_HOME/venv"
      mv "$BRIDGE_HOME/venv.previous" "$BRIDGE_HOME/venv"
      echo "install.sh: update failed; the previous venv was restored" >&2
    fi
  }
  # Armed only after the backup is complete; also on Ctrl-C/TERM.
  trap restore_venv ERR
  trap 'restore_venv; exit 130' INT TERM
fi
uv venv "${VENV_FLAGS[@]}" --python "${BRIDGE_PYTHON:-3.14}" "$BRIDGE_HOME/venv"

# Dependencies come from the frozen lockfile, verified against its hashes
# (--require-hashes). The bridge itself is installed last, with --no-deps, so
# nothing outside the lock can slip in.
REQ="$(mktemp)"
trap 'rm -f "$REQ"' EXIT
uv export --project "$SRC" --frozen --no-dev --no-emit-project --format requirements.txt -o "$REQ"
uv pip install --python "$BRIDGE_HOME/venv" --require-hashes -r "$REQ"
uv pip install --python "$BRIDGE_HOME/venv" --no-deps "$SRC"
trap - ERR INT TERM
rm -rf "$BRIDGE_HOME/venv.previous"

# bridge.env holds the master-key path, bind address and state DB. It is 0600.
ENV="$BRIDGE_HOME/bridge.env"
if [ ! -f "$ENV" ]; then
  cat >"$ENV" <<'ENVEOF'
# Loopback only. TLS is provided out-of-band (e.g. Tailscale Serve).
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8642
# Path to the Hermes install env file that holds API_SERVER_KEY (0600).
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
# Persistent device/pairing store (created 0600), inside BRIDGE_HOME.
BRIDGE_STATE_DB=@STATE_DB@
# HTTPS base URL used to print the orsik://pair deep link / QR.
BRIDGE_PUBLIC_BASE_URL=
# 1 = paired phones may open a shell (tmux) as this user, WITHOUT Hermes
# approvals. Needs tmux. See docs/bridge-installation.md.
BRIDGE_TERMINAL=0
# 1 = paired phones may browse and read (never write) files below
# BRIDGE_FILES_ROOT (an absolute folder outside the bridge and Hermes data)
# and see the git projects in it.
BRIDGE_FILES=0
BRIDGE_FILES_ROOT=
ENVEOF
  # The store lives in this install's BRIDGE_HOME (also with a custom one,
  # which the systemd unit may write); the path is data, never sed syntax.
  # index/substr, not sub(): an "&" in the path must stay literal.
  STATE_DB="$BRIDGE_HOME/state.db" awk '{
    i = index($0, "@STATE_DB@")
    if (i > 0) $0 = substr($0, 1, i - 1) ENVIRON["STATE_DB"] substr($0, i + 10)
    print
  }' "$ENV" >"$ENV.tmp"
  mv "$ENV.tmp" "$ENV"
  chmod 600 "$ENV"
  echo "Wrote $ENV (edit BRIDGE_HERMES_ENV / BRIDGE_PUBLIC_BASE_URL)"
fi

if [ "$SYSTEMD" -eq 1 ]; then
  mkdir -p "$UNIT_DIR"
  cat >"$UNIT_DIR/$UNIT_NAME" <<UNITEOF
[Unit]
Description=Orsik Mobile Bridge (loopback Mobile-v1 sidecar over official Hermes)
After=network-online.target

[Service]
Type=simple
Environment=BRIDGE_ENV_FILE=$ENV
ExecStart=$BRIDGE_HOME/venv/bin/orsik-mobile-bridge
Restart=on-failure
RestartSec=2
NoNewPrivileges=true
UMask=0077
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=$BRIDGE_HOME

[Install]
WantedBy=default.target
UNITEOF
  systemctl --user daemon-reload
  echo "Installed user unit $UNIT_NAME. Enable it with:"
  echo "  systemctl --user enable --now $UNIT_NAME"
fi

echo
echo "Installed into $BRIDGE_HOME."
echo "Next steps:"
echo "  1. Edit $ENV (Hermes env path, public base URL)."
echo "  2. Issue a pairing code:  BRIDGE_ENV_FILE=$ENV $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code"
echo "  3. Start the bridge:      BRIDGE_ENV_FILE=$ENV $BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
echo "     or (with --systemd):   systemctl --user start $UNIT_NAME"
echo "  4. Verify the setup:     $0 --check"
echo "Rollback: $0 --uninstall && rm -rf $BRIDGE_HOME"
