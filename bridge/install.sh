#!/usr/bin/env bash
# Orsik Mobile Bridge — install concept (no curl|bash, no core patching).
#
# Installs the bridge into a DEDICATED virtual environment from a local,
# reviewed source checkout or a pinned wheel. It never modifies Hermes core
# files and never opens a public port.
#
# Usage:
#   ./install.sh /path/to/bridge/source
#
# Rollback:
#   rm -rf "$BRIDGE_HOME"
set -euo pipefail

SRC="${1:-$(cd "$(dirname "$0")" && pwd)}"
BRIDGE_HOME="${BRIDGE_HOME:-$HOME/.local/orsik-mobile-bridge}"

if [ ! -f "$SRC/pyproject.toml" ]; then
  echo "install.sh: $SRC is not a bridge source tree (missing pyproject.toml)" >&2
  exit 1
fi

# A dedicated venv keeps the bridge's dependencies isolated from Hermes.
# Any interpreter the package supports (>=3.11) works; override with BRIDGE_PYTHON.
uv venv --python "${BRIDGE_PYTHON:-3.14}" "$BRIDGE_HOME/venv"
uv pip install --python "$BRIDGE_HOME/venv" "$SRC"

cat >"$BRIDGE_HOME/bridge.env.example" <<'ENV'
# Loopback only. TLS is provided out-of-band (e.g. Tailscale Serve).
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8899
# Path to the Hermes install env file that holds API_SERVER_KEY (0600).
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
# Admin token for POST /admin/pairing-code (0600; never shared with devices).
BRIDGE_ADMIN_TOKEN=replace-with-openssl-rand
ENV

echo "Installed. Edit $BRIDGE_HOME/bridge.env.example, then run:"
echo "  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
echo "Rollback: rm -rf $BRIDGE_HOME"
