# Orsik Mobile Bridge – Installation & Operation

As of: 2026-10-10. Target path: **fresh, unmodified official Hermes installation**
→ install bridge → provide private HTTPS → create pairing → scan QR/deep link
→ use chat. No Hermes fork, no core patches, no public port.

## 1. Prerequisites

- Official Hermes (Git state, tested here: `NousResearch/hermes-agent`
  `f86276d2`, Python 3.14) runs with an active `api_server` on **loopback**
  (Hermes default `127.0.0.1:8642`, see `API_SERVER_PORT` in `~/.hermes/.env`),
  `API_SERVER_KEY` in a 0600 env.
- A private HTTPS access path to the server (see §4): Tailscale Serve,
  WireGuard+reverse proxy or LAN with a valid certificate.
- `uv` and a Python ≥ 3.11 (bridge venv separate from the Hermes venv).
- **Approval mode `manual`** in the `config.yaml` of the Hermes home behind the
  bridge (`approvals:` / `mode: manual`). With the core default `smart`, a
  helper LLM approves dangerous commands classified as harmless without
  `approval.request`; the device is then not asked (proven live, see
  `bridge-security.md` §4.1).
- Hermes venv with the `messaging` extra (`uv sync --frozen --extra messaging`);
  without it, `aiohttp` is missing and `api_server` does not start.

## 2. Installation

Get the source of the latest release as the user that runs Hermes, then run
the installer from it (without a path argument it installs the `bridge/`
directory it lives in):

```sh
git clone https://github.com/exclude-barrier/OrsikRelay.git ~/OrsikRelay
cd ~/OrsikRelay && git checkout "$(git describe --tags --abbrev=0)"
bridge/install.sh [--update] [--systemd] [/path/to/bridge/source]
```

- Creates `$BRIDGE_HOME` (default `~/.local/orsik-mobile-bridge`, 0700) and a
  **dedicated venv**; installs the dependencies **hash-pinned** from
  the checked-in `bridge/uv.lock` (`uv export --frozen` with
  `--require-hashes`, then the bridge itself with `--no-deps`), **no**
  `curl | bash`. If `uv.lock` is missing, the installer aborts.
- If an installation already exists, `install.sh` aborts without `--update`
  (existing installations/ports are not overwritten).
- Writes `$BRIDGE_HOME/bridge.env` (0600) as a template.

This makes the installation reproducible: the same source state delivers exactly the same
dependency versions on any day and on any machine. Dependencies
change only deliberately — `uv lock --upgrade-package <pkg>` plus review of the
lock diff (`uv lock --check` in CI fails on an outdated lock). CI
additionally builds a wheel, `SHA256SUMS` and a CycloneDX SBOM and stores them as
the artifact `bridge-dist`.

`bridge.env`:

```
# loopback only; the bridge enforces this
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
# official api_server; loopback HTTP IP with port only, no hostname,
# no path — otherwise the bridge does not start
BRIDGE_UPSTREAM=http://127.0.0.1:8642
# file with API_SERVER_KEY (0600)
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
BRIDGE_STATE_DB=/home/USER/.local/orsik-mobile-bridge/state.db
# for deep link/QR
BRIDGE_PUBLIC_BASE_URL=https://<host>
```

Comments only on their own lines: `bridge.env` is read as `KEY=VALUE`,
an inline comment would become part of the value (and, for example, make
`BRIDGE_BIND` / `BRIDGE_UPSTREAM` invalid).

## 2a. Preflight: `./install.sh --check`

Checks an existing installation **without creating or changing anything**,
reading `$BRIDGE_HOME/bridge.env` line by line as data (`KEY=VALUE`,
comments/blank lines ignored; no `source`, no `eval`). Output per
check exactly one line `OK <name>` / `WARN <name>: <reason>` /
`FAIL <name>: <reason>`; key/token values are never output. Exit code 1
as soon as one check is FAIL, otherwise 0; at the end `PREFLIGHT: PASS` or
`PREFLIGHT: FAIL (<n> failed)`.

```sh
./install.sh --check
```

| Check | Expectation |
|---|---|
| `home_mode` | `$BRIDGE_HOME` exists, mode 700 |
| `env_mode` | `bridge.env` exists, mode 600 |
| `venv` | `$BRIDGE_HOME/venv/bin/orsik-mobile-bridge` executable |
| `bind` | `BRIDGE_BIND` empty, `127.0.0.1` or `::1` |
| `upstream` | `BRIDGE_UPSTREAM` (default `http://127.0.0.1:8642`, the Hermes default port) matches `^http://(127.x.x.x\|[::1]):port$` |
| `hermes_env` | `BRIDGE_HERMES_ENV` set, file 0600, with `API_SERVER_KEY=…` |
| `approvals_mode` | `config.yaml` next to the Hermes env file sets `approvals: mode: manual`; if the file is missing or another mode is set there → WARN (core default `smart` sometimes approves without asking) |
| `public_base_url` | empty → WARN (no deep link/QR); otherwise it must start with `https://` |
| `port` | `BRIDGE_PORT` (default 9842): `ss` missing → WARN; listener on loopback/`0.0.0.0` → WARN ("port in use") |
| `hermes_health` | `curl` to `$UPSTREAM/health` == 200; only if `upstream` was OK, otherwise WARN |

In addition, the normal installation mode checks **before** any change whether `uv`
is present and `uv python find ${BRIDGE_PYTHON:-3.14}` works; otherwise it
aborts with a note. After an installation it reminds you of
`./install.sh --check`.

## 3. Operator Commands (No HTTP Admin)

Pairing codes are generated **exclusively locally** — the file system permissions on
the store are the authorization boundary; devices never get an admin path.

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code \
  --base-url https://<host>
# -> "Pairing code: XXXX-XXXX-XXXX-XXXX-XXXX-XXXX  (valid 300s, single-use)"
# -> "Deep link:    orsik://pair?server=...&code=..."
# -> ASCII QR (if 'qrencode' is installed)

... admin list-devices
... admin revoke-device <device_id>
```

Security properties: 24 characters, single-use, TTL 300 s, server-bound,
only SHA-256 hash at rest; **no** master key and **no** device key in the link.

## 4. Private HTTPS (One Path Is Enough)

- **Tailscale Serve** (in the tailnet, no funnel):
  `tailscale serve --bg --https=8443 http://127.0.0.1:9842`
  → `https://<host>.<tailnet>:8443`
- **WireGuard + reverse proxy**: Hermes/bridge remain on loopback; the proxy
  terminates a publicly trusted certificate and forwards only the
  source allowlist.
- **LAN**: valid certificate via split DNS to the private address.

No plaintext HTTP, no TLS bypass, no public port. The Android app
checks certificate + contract; it aborts on an invalid certificate.

## 5. Start / Stop / Status

Without systemd:

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env $BRIDGE_HOME/venv/bin/orsik-mobile-bridge
```

With `--systemd` (user unit `orsik-mobile-bridge.service`):

```sh
systemctl --user enable --now orsik-mobile-bridge.service
systemctl --user status  orsik-mobile-bridge.service
systemctl --user restart orsik-mobile-bridge.service
```

## 6. Update / Rollback / Backup / Restore / Uninstall

- **Update:** back up the store (see below), switch the checkout to the new
  release and rebuild the venv; `bridge.env` and the store are retained,
  pairing/tokens survive:

  ```sh
  cd ~/OrsikRelay && git fetch --tags
  git checkout "$(git describe --tags --abbrev=0 origin/main)"
  bridge/install.sh --update --systemd
  systemctl --user restart orsik-mobile-bridge.service
  bridge/install.sh --check
  ```

  `--update` rebuilds the venv from scratch (`uv venv --clear`).
- **Uninstall:** `./install.sh --uninstall` (stop/remove the unit) and
  `rm -rf $BRIDGE_HOME` (venv + store). Hermes and its models remain untouched.

### Backup / Restore

Operator backup of the store (atomic via a temp file + hardlink, target
0600; an already existing target is never overwritten):

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin backup $BRIDGE_HOME/state.db.bak
# -> "backup written: /home/USER/.local/orsik-mobile-bridge/state.db.bak"
# -> on error: "error: ..." on stderr, exit 1
```

- **Pre-migration backup (automatic):** If the bridge opens a store with an
  older schema (1 ≤ `user_version` < current version), it creates
  `state.db.pre-v<version>.bak` **before** the migration (0600, likewise atomic).
  A fresh DB (`user_version` 0) gets no backup. An already existing
  target is not overwritten but stored as
  `state.db.pre-v<version>.<timestamp>.bak`. If the backup fails,
  the bridge does **not** open the store and migrates nothing.
- **Restore:** stop the bridge, remove any existing `state.db-wal` and
  `state.db-shm` next to the target (otherwise SQLite mixes the old WAL into
  the restored backup), copy the backup over `$BRIDGE_STATE_DB` (or
  set the backup itself as `BRIDGE_STATE_DB`), start the bridge, then
  check `admin list-devices`.
- **Rollback after a schema migration:** only by restoring the
  pre-migration backup (stop the bridge → copy the backup over `BRIDGE_STATE_DB`
  → start the old bridge version). An older bridge cannot open an already
  migrated store: it refuses a newer schema
  fail-closed. Without a pre-migration backup there is no rollback path.

⚠️ **WARNING:** A restore restores the **authorization state at the
backup time**. Revokes are deleted rows in the store, not a
revocation log; therefore devices that were revoked **after** the backup are
valid again after the restore. After **every** restore, check `admin list-devices`
and revoke affected devices again with `admin revoke-device <device_id>`.

## 7. Verified Path (build-host, 2026-10-10)

Fresh official core (`f86276d2`) → bridge installed (source checkout,
`uv` venv) → loopback proxy → Tailscale Serve `:8443` → emulator API 36 →
live acceptance **37/37 green** (`bridge/tests/acceptance_live.py`). Details:
`docs/bridge-e2e.md`.
