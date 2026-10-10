#!/usr/bin/env bash
# Orsik Mobile Bridge installer (no curl|bash, no core patching).
#
# Installs the bridge into a DEDICATED virtual environment from a local,
# reviewed source checkout. It never modifies Hermes core files, never opens a
# public port, and never overwrites an existing install unless --update is given.
#
# Usage:
#   ./install.sh [--update] [--systemd] [/path/to/bridge/source]
#
# Rollback / uninstall:
#   ./install.sh --uninstall          # stops+removes the user unit
#   rm -rf "$BRIDGE_HOME"             # removes the venv + state database
set -euo pipefail

SRC=""
UPDATE=0
SYSTEMD=0
UNINSTALL=0
for arg in "$@"; do
  case "$arg" in
    --update) UPDATE=1 ;;
    --systemd) SYSTEMD=1 ;;
    --uninstall) UNINSTALL=1 ;;
    *) SRC="$arg" ;;
  esac
done
SRC="${SRC:-$(cd "$(dirname "$0")" && pwd)}"
BRIDGE_HOME="${BRIDGE_HOME:-$HOME/.local/orsik-mobile-bridge}"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_NAME="orsik-mobile-bridge.service"

if [ "$UNINSTALL" -eq 1 ]; then
  systemctl --user disable --now "$UNIT_NAME" 2>/dev/null || true
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

# Do not clobber an existing install unless the operator asked to update it.
if [ -d "$BRIDGE_HOME/venv" ] && [ "$UPDATE" -ne 1 ]; then
  echo "install.sh: $BRIDGE_HOME already exists; re-run with --update to refresh it" >&2
  exit 1
fi

mkdir -p "$BRIDGE_HOME"
chmod 700 "$BRIDGE_HOME"

# A dedicated venv keeps the bridge's dependencies isolated from Hermes.
# Any interpreter the package supports (>=3.11) works; override with BRIDGE_PYTHON.
uv venv --python "${BRIDGE_PYTHON:-3.14}" "$BRIDGE_HOME/venv"
uv pip install --python "$BRIDGE_HOME/venv" "$SRC"

# bridge.env holds the master-key path, bind address and state DB. It is 0600.
ENV="$BRIDGE_HOME/bridge.env"
if [ ! -f "$ENV" ]; then
  cat >"$ENV" <<'ENVEOF'
# Loopback only. TLS is provided out-of-band (e.g. Tailscale Serve).
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8899
# Path to the Hermes install env file that holds API_SERVER_KEY (0600).
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
# Persistent device/pairing store (created 0600). Defaults under BRIDGE_HOME.
BRIDGE_STATE_DB=
# HTTPS base URL used to print the orsik://pair deep link / QR.
BRIDGE_PUBLIC_BASE_URL=
ENVEOF
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
echo "  2. Issue a pairing code:  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code --base-url https://<host>"
echo "  3. Start the bridge:      $BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
echo "     or (with --systemd):   systemctl --user start $UNIT_NAME"
echo "Rollback: $0 --uninstall && rm -rf $BRIDGE_HOME"
