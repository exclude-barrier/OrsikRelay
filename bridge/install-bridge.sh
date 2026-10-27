#!/usr/bin/env bash
# Orsik Mobile Bridge quick install / update.
#
#   curl -fsSL https://raw.githubusercontent.com/exclude-barrier/OrsikRelay/main/bridge/install-bridge.sh | bash
#   curl -fsSL …/install-bridge.sh | bash -s -- --pair   # also print a pairing code
#
# Run it as the user that runs Hermes, NOT as root. It
#   - checks Hermes (api_server on loopback, API key file, approvals: manual),
#   - clones (or updates) the latest release into ~/OrsikRelay,
#   - installs the bridge with bridge/install.sh (hash-pinned dependencies),
#   - writes ~/.local/orsik-mobile-bridge/bridge.env,
#   - starts the systemd user service,
#   - and prints the one root command for private HTTPS (tailscale serve).
# It never changes Hermes itself, never opens a port, never prints a secret.
# Running it again updates an existing install (backup of the device store first).
#
# Options: --pair        print a pairing code at the end
#          --no-service  install and check only, do not touch systemd (tests)
# Environment: HERMES_HOME (default ~/.hermes), ORSIK_DIR (default ~/OrsikRelay),
#              ORSIK_REF (release tag to install, default: the latest tag),
#              BRIDGE_PYTHON (default: 3.14 if available, else the newest 3.11+).
set -euo pipefail

REPO_URL="https://github.com/exclude-barrier/OrsikRelay.git"
PAIR=0
SERVICE=1
for arg in "$@"; do
  case "$arg" in
    --pair) PAIR=1 ;;
    --no-service) SERVICE=0 ;;
    *) echo "install-bridge: unknown option: $arg" >&2; exit 2 ;;
  esac
done

say() { printf '\n==> %s\n' "$*"; }
die() { printf 'install-bridge: %s\n' "$*" >&2; exit 1; }

# Read an env file exactly like the bridge (_load_env_file / _env_value):
# `export `, surrounding whitespace, matching outer quotes, an unquoted " #"
# comment, and the LAST occurrence of a key wins. Data only, never sourced.
env_value() { # <file> <key>
  local file="$1" want="$2" line key value result="" found=0
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    case "$line" in
      "export "*) line="${line#export }"; line="${line#"${line%%[![:space:]]*}"}" ;;
    esac
    case "$line" in
      ""|\#*) continue ;;
      *=*) ;;
      *) continue ;;
    esac
    key="${line%%=*}"
    key="${key#"${key%%[![:space:]]*}"}"
    key="${key%"${key##*[![:space:]]}"}"
    if [ "$key" = "$want" ]; then
      value="${line#*=}"
      value="${value#"${value%%[![:space:]]*}"}"
      value="${value%"${value##*[![:space:]]}"}"
      case "$value" in
        \"*\"|\'*\') value="${value:1:${#value}-2}" ;;
        *" #"*) value="${value%% #*}"; value="${value%"${value##*[![:space:]]}"}" ;;
      esac
      result="$value"
      found=1
    fi
  done <"$file"
  [ "$found" -eq 1 ] && printf '%s\n' "$result"
  return 0
}

# Package command for 'qrencode' on this distribution (hint only; never run).
qr_install_command() {
  local id="" like=""
  if [ -r /etc/os-release ]; then
    id="$(. /etc/os-release && printf '%s' "${ID:-}")"
    like="$(. /etc/os-release && printf '%s' "${ID_LIKE:-}")"
  fi
  case " $id $like " in
    *debian*|*ubuntu*) printf '%s' "sudo apt install qrencode" ;;
    *fedora*|*rhel*) printf '%s' "sudo dnf install qrencode" ;;
    *arch*) printf '%s' "sudo pacman -S qrencode" ;;
    *suse*) printf '%s' "sudo zypper install qrencode" ;;
    *) printf '%s' "install qrencode with your distribution's package manager" ;;
  esac
}

if [ "$(id -u)" -eq 0 ]; then
  die "do not run this as root; run it as the user that runs Hermes"
fi

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
ORSIK_DIR="${ORSIK_DIR:-$HOME/OrsikRelay}"
BRIDGE_HOME="${BRIDGE_HOME:-$HOME/.local/orsik-mobile-bridge}"
ENV_FILE="$BRIDGE_HOME/bridge.env"
UNIT=orsik-mobile-bridge.service
export PATH="$HOME/.local/bin:$PATH"

# --- 1. Prerequisites -------------------------------------------------------
say "Checking prerequisites"
for tool in git curl; do
  command -v "$tool" >/dev/null || die "$tool is missing; install it first"
done
command -v uv >/dev/null || die "uv is missing; install it first, e.g.: sudo apt install pipx && pipx install uv"

HERMES_ENV="$HERMES_HOME/.env"
[ -f "$HERMES_ENV" ] || die "no Hermes env file at $HERMES_ENV (set HERMES_HOME)"
# Same parse as the bridge: `export`, quotes and the last occurrence count.
[ -n "$(env_value "$HERMES_ENV" API_SERVER_KEY)" ] \
  || die "API_SERVER_KEY is not set in $HERMES_ENV (enable the Hermes api_server first)"
PORT="$(sed -n 's/^API_SERVER_PORT=\([0-9][0-9]*\).*/\1/p' "$HERMES_ENV" | tail -n 1)"
PORT="${PORT:-8642}"
UPSTREAM="http://127.0.0.1:$PORT"
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$UPSTREAM/health" || true)"
[ "$code" = 200 ] || die "the Hermes api_server does not answer on $UPSTREAM/health (got '$code')"
echo "Hermes api_server: $UPSTREAM"

CONFIG="$HERMES_HOME/config.yaml"
if [ -f "$CONFIG" ] && awk '/^approvals:/{f=1;next} f&&/^[^ ]/{f=0} f&&/^ +mode: *['"'"'"]?manual/{ok=1} END{exit !ok}' "$CONFIG"; then
  echo "Approvals: manual"
else
  die "set 'approvals: mode: manual' in $CONFIG first (otherwise Hermes may approve dangerous commands without asking)"
fi

if [ -z "${BRIDGE_PYTHON:-}" ]; then
  for v in 3.14 3.13 3.12 3.11; do
    if uv python find "$v" >/dev/null 2>&1; then BRIDGE_PYTHON="$v"; break; fi
  done
fi
[ -n "${BRIDGE_PYTHON:-}" ] || die "no Python 3.11 or newer found (try: uv python install 3.14)"
export BRIDGE_PYTHON
echo "Python: $BRIDGE_PYTHON"

if command -v loginctl >/dev/null && [ "$(loginctl show-user "$USER" -p Linger --value 2>/dev/null)" != yes ]; then
  echo "Note: without lingering the bridge stops when you log out. Fix once with: sudo loginctl enable-linger $USER"
fi

# Only build code from the official repository: an existing checkout that
# points elsewhere is refused instead of being fetched and installed.
check_origin() {
  local origin
  origin="$(git -C "$ORSIK_DIR" remote get-url origin 2>/dev/null || true)"
  case "$origin" in
    https://github.com/exclude-barrier/OrsikRelay | https://github.com/exclude-barrier/OrsikRelay.git | \
      git@github.com:exclude-barrier/OrsikRelay.git) ;;
    *) die "$ORSIK_DIR is not a checkout of $REPO_URL (origin: ${origin:-none}). Fix it with: git -C \"$ORSIK_DIR\" remote set-url origin $REPO_URL" ;;
  esac
}

# --- 2. Source of the release -----------------------------------------------
say "Getting the release"
if [ -d "$ORSIK_DIR/.git" ]; then
  check_origin
  git -C "$ORSIK_DIR" fetch --quiet --tags origin
  REF="${ORSIK_REF:-$(git -C "$ORSIK_DIR" describe --tags --abbrev=0 origin/main)}"
else
  git clone --quiet "$REPO_URL" "$ORSIK_DIR"
  REF="${ORSIK_REF:-$(git -C "$ORSIK_DIR" describe --tags --abbrev=0)}"
fi
git -C "$ORSIK_DIR" -c advice.detachedHead=false checkout --quiet "$REF"
echo "Release: $REF"

# --- 3. Install or update -----------------------------------------------------
UPDATE=()
if [ -x "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge" ]; then
  say "Updating the existing install"
  # The backup runs the OLD installed binary, whose env parser may keep quotes
  # and ignore `export`. Resolve BRIDGE_STATE_DB here, with the bridge's rules,
  # and pass it explicitly. BRIDGE_ENV_FILE is cleared because the binary's
  # loader prefers the file's value over the process environment.
  if [ -f "$ENV_FILE" ]; then
    STATE_DB="$(env_value "$ENV_FILE" BRIDGE_STATE_DB)"
    STATE_DB="${STATE_DB:-$BRIDGE_HOME/state.db}"
    STATE_DB="${STATE_DB/#\~/$HOME}"
    # Match the user service's working directory, not the caller's cwd.
    case "$STATE_DB" in
      /*) ;;
      *) STATE_DB="$HOME/$STATE_DB" ;;
    esac
    if [ -f "$STATE_DB" ]; then
      BACKUP_HERMES_ENV="$(env_value "$ENV_FILE" BRIDGE_HERMES_ENV)"
      [ -n "$BACKUP_HERMES_ENV" ] || BACKUP_HERMES_ENV="$HERMES_ENV"
      BACKUP="$BRIDGE_HOME/state.db.bak-$(date +%Y%m%d-%H%M%S)"
      if ! BRIDGE_ENV_FILE= BRIDGE_STATE_DB="$STATE_DB" \
        BRIDGE_HERMES_ENV="$BACKUP_HERMES_ENV" \
        "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge" admin backup "$BACKUP"; then
        die "could not back up the device store at $STATE_DB; aborting the update"
      fi
    else
      echo "No device store at $STATE_DB; skipping the pre-update backup."
    fi
  fi
  UPDATE=(--update)
else
  say "Installing the bridge"
fi
SYSTEMD=()
[ "$SERVICE" -eq 1 ] && SYSTEMD=(--systemd)
# An interrupted replacement must never leave a stale "already current" marker.
rm -f "$BRIDGE_HOME/installed-commit"
bash "$ORSIK_DIR/bridge/install.sh" "${UPDATE[@]}" "${SYSTEMD[@]}"

# --- 4. Configuration ---------------------------------------------------------
say "Writing $ENV_FILE"
set_env() {
  local key="$1" value="$2" tmp
  case "$value" in
    *$'\n'*) die "$key must not contain a newline" ;;
  esac
  # Write the line with awk instead of sed: the value is data (may contain
  # &, | or backslashes) and must never be interpreted as a sed expression.
  tmp="$(mktemp "$ENV_FILE.XXXXXX")"
  KEY="$key" VALUE="$value" awk '
    BEGIN { key = ENVIRON["KEY"]; value = ENVIRON["VALUE"] }
    {
      line = $0
      sub(/^[[:space:]]+/, "", line)
      sub(/[[:space:]]+$/, "", line)
      if (line == "" || line ~ /^#/) { print; next }
      if (line ~ /^export /) sub(/^export /, "", line)
      sub(/^[[:space:]]+/, "", line)
      eq = index(line, "=")
      if (eq > 0) {
        found_key = substr(line, 1, eq - 1)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", found_key)
        if (found_key == key) { print key "=" value; found = 1; next }
      }
      print
    }
    END { if (!found) print key "=" value }
  ' "$ENV_FILE" >"$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
}
# A rerun keeps the operator's own values; only missing or empty ones get
# the defaults (a custom store path or port must survive an update).
set_default() {
  local current
  current="$(env_value "$ENV_FILE" "$1")"
  [ -n "$current" ] || set_env "$1" "$2"
}
set_default BRIDGE_BIND 127.0.0.1
set_default BRIDGE_PORT 9842
# The upstream follows the Hermes api_server detected above (its port may
# change between runs).
set_env BRIDGE_UPSTREAM "$UPSTREAM"
set_env BRIDGE_HERMES_ENV "$HERMES_ENV"
set_default BRIDGE_STATE_DB "$BRIDGE_HOME/state.db"
PUBLIC_URL="$(env_value "$ENV_FILE" BRIDGE_PUBLIC_BASE_URL)"
if [ -z "$PUBLIC_URL" ] && command -v tailscale >/dev/null; then
  name="$(tailscale status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)"
  [ -n "$name" ] && PUBLIC_URL="https://$name:8443"
fi
[ -n "$PUBLIC_URL" ] && set_env BRIDGE_PUBLIC_BASE_URL "$PUBLIC_URL"
chmod 600 "$ENV_FILE"

# The hints below must name the configured listener, not the defaults.
BRIDGE_BIND_CFG="$(env_value "$ENV_FILE" BRIDGE_BIND)"
BRIDGE_BIND_CFG="${BRIDGE_BIND_CFG:-127.0.0.1}"
BRIDGE_PORT_CFG="$(env_value "$ENV_FILE" BRIDGE_PORT)"
BRIDGE_PORT_CFG="${BRIDGE_PORT_CFG:-9842}"
case "$BRIDGE_BIND_CFG" in
  *:*) BRIDGE_HOST_CFG="[$BRIDGE_BIND_CFG]" ;;
  *) BRIDGE_HOST_CFG="$BRIDGE_BIND_CFG" ;;
esac
BRIDGE_URL_CFG="http://$BRIDGE_HOST_CFG:$BRIDGE_PORT_CFG"

# --- 5. Check and start -------------------------------------------------------
say "Checking the install"
bash "$ORSIK_DIR/bridge/install.sh" --check
if [ "$SERVICE" -eq 1 ]; then
  systemctl --user daemon-reload
  systemctl --user enable --quiet "$UNIT"
  systemctl --user restart "$UNIT"
  systemctl --user is-active --quiet "$UNIT" || die "the bridge service did not start; see: journalctl --user -u $UNIT"
  echo "Service $UNIT is running on $BRIDGE_URL_CFG"
fi

# Record only a successfully installed and checked source revision. The local
# updater uses this marker to avoid reinstalling an unchanged release.
INSTALLED_COMMIT="$(git -C "$ORSIK_DIR" rev-parse HEAD)"
COMMIT_TMP="$(mktemp "$BRIDGE_HOME/.installed-commit.XXXXXX")"
printf '%s\n' "$INSTALLED_COMMIT" >"$COMMIT_TMP"
chmod 600 "$COMMIT_TMP"
mv "$COMMIT_TMP" "$BRIDGE_HOME/installed-commit"

# --- 6. Next steps ------------------------------------------------------------
say "Done"
TERMINAL_CFG="$(env_value "$ENV_FILE" BRIDGE_TERMINAL)"
if [[ "$TERMINAL_CFG" =~ ^[[:space:]]*1[[:space:]]*$ ]]; then
  echo
  echo "Terminal access requires a separate local grant for each device, including previously paired devices."
  echo "Review the device list and grant only the intended DEVICE_ID:"
  printf '  BRIDGE_ENV_FILE=%q %q admin list-devices\n' "$ENV_FILE" "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
  printf '  BRIDGE_ENV_FILE=%q %q admin grant-terminal DEVICE_ID\n' "$ENV_FILE" "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
  printf '  BRIDGE_ENV_FILE=%q %q admin revoke-terminal DEVICE_ID\n' "$ENV_FILE" "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge"
fi
if [ -n "$PUBLIC_URL" ]; then
  cat <<EOF
Private HTTPS (once, needs root; stays inside your tailnet, no Funnel):
  sudo tailscale serve --bg --https=8443 $BRIDGE_URL_CFG

The app connects to: $PUBLIC_URL
EOF
else
  cat <<EOF
Expose $BRIDGE_URL_CFG privately over HTTPS (e.g. Tailscale Serve, see docs/network-deployment.md),
then set BRIDGE_PUBLIC_BASE_URL in $ENV_FILE and run: systemctl --user restart $UNIT
EOF
fi
if ! command -v qrencode >/dev/null 2>&1; then
  echo
  echo "No 'qrencode' found: the pairing command prints the deep link but no QR code."
  echo "Install it with: $(qr_install_command)"
fi
printf -v PAIR_CMD 'BRIDGE_ENV_FILE=%q %q admin pairing-code --base-url %q' \
  "$ENV_FILE" "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge" "${PUBLIC_URL:-<https-url>}"
if [ "$PAIR" -eq 1 ] && [ -n "$PUBLIC_URL" ]; then
  say "Pairing code (works once, for 5 minutes)"
  BRIDGE_ENV_FILE="$ENV_FILE" "$BRIDGE_HOME/venv/bin/orsik-mobile-bridge" admin pairing-code --base-url "$PUBLIC_URL"
elif [ "$PAIR" -eq 1 ]; then
  echo
  echo "No public HTTPS URL is known yet, so no pairing link or QR was printed."
  echo "Set BRIDGE_PUBLIC_BASE_URL in $ENV_FILE, then run this script again with --pair."
else
  echo
  echo "Create a pairing code when the phone is ready (works once, for 5 minutes):"
  echo "  $PAIR_CMD"
fi
