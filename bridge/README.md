# Orsik Mobile Bridge

A minimal, **installable** sidecar that exposes the Orsik Mobile Protocol v1
surface in front of an **unmodified official Hermes** API server.

It is the sidecar chosen in the architecture comparison
(`docs/hermes-fresh-compat.md`). It does not patch Hermes, does not run inside
the Hermes process, and does not expose the Hermes API key to clients.

Public paths mirror the api_server namespace the shipped `HermesConnectionClient`
already speaks, so the client is a **drop-in**: it can point at the bridge base
URL unchanged.

## What it does

- Runs on `127.0.0.1` only (TLS is provided out-of-band, e.g. Tailscale Serve).
- Reads the Hermes `API_SERVER_KEY` from a 0600 env file and keeps it server-side.
- Mints **device tokens** from short-lived, single-use **pairing codes**
  (24 chars, 300 s TTL, stored as SHA-256 hashes) issued by a **local operator
  CLI** — there is no HTTP admin surface.
- Gives every device token a fixed allowlist: `chat`, `runs`, `approvals`.
- Persists paired devices in a **SQLite store (0600)** so pairing and revoke
  survive a bridge restart.
- Proxies **only** a closed set of paths upstream (`core.PROXIED_ROUTES`):
  `/api/sessions…` and `/v1/runs…`. No admin, cron, memory, jobs, skills or
  artifact surface.
- Takes over the session chat stream **server-side** so a lost client
  connection does not interrupt the Hermes run, and exposes an `after_seq`
  re-attach endpoint.
- Advertises the Mobile Protocol v1 capability envelope, including the
  `session_chat_interactive_approvals` contract the official core now supports,
  narrowed to the least-privilege choices `once`/`deny`.

## Security hardening

- **Files and projects, read-only, off by default.** With `BRIDGE_FILES=1` and
  `BRIDGE_FILES_ROOT=<absolute folder>` the bridge answers `GET
  /v1/files/list?path=`, `GET /v1/files/content?path=` and `GET /v1/projects`
  itself (device token, only the `path` query, no body, GET only). Paths are
  relative to the root and must stay inside it after symlinks are resolved
  (`403 path_not_allowed`; a link pointing outside is listed as `other` and
  never opened). The root may not contain the bridge home, `bridge.env` or
  the Hermes home with the API key – the bridge and `install.sh --check`
  refuse such a root. Only regular files up to 8 MiB are sent (`413
  file_too_large`), as an image, UTF-8 text or `application/octet-stream`,
  with `nosniff` and `no-store`; nothing is ever written. Projects are git
  working trees up to two levels below the root (at most 50): branch,
  changed/clean and the last commit, read with fixed git arguments and
  without the repository's fsmonitor or hooks; one snapshot per 10 s. A file
  read is audited (`file_read` with path and size). Advertised as `files`
  and `projects`.
- **Terminal, off by default.** With `BRIDGE_TERMINAL=1` (and `tmux` plus
  util-linux `setsid` on the PATH) `GET /v1/terminal` upgrades to a WebSocket
  that runs `setsid --ctty tmux new-session -A -s orsik` on a fresh PTY:
  binary frames carry the bytes both ways, a text frame
  `{"type":"resize","cols":C,"rows":R}` the size (1..500 × 1..300). It is a
  shell as the bridge user **without Hermes approvals** (a user in the
  `docker` group is root-equivalent); the systemd unit's
  `NoNewPrivileges=true` keeps `sudo` out. Device token, no `Origin` header
  (a browser always sends one), no query, at most 4 terminals, 64 KiB per
  message; the child gets a minimal environment (never the Hermes key or a
  bridge setting); a revoked device loses an open terminal within 15 s
  (close `4401`); open and close are audited (`terminal_open` /
  `terminal_close` with the reason), never input or output. Closing ends
  only the tmux client: the session keeps running until it is exited or the
  bridge restarts. Advertised as `terminal` only when enabled.
- **Host metrics, read-only.** `GET /v1/host/metrics` (device token, no query,
  no body) is answered by the bridge itself, never proxied: CPU share from two
  `/proc/stat` samples, RAM from `/proc/meminfo`, the first NVIDIA GPU via
  `nvidia-smi` with fixed arguments (when installed) and `systemctl --user
  is-active` for the operator's fixed unit list `BRIDGE_HOST_SERVICES`
  (comma-separated, at most 8 plain names; default `hermes-gateway,
  orsik-mobile-bridge`; an unsafe name stops the bridge at start). No shell and
  no request-chosen argument; any failing source reads as "no value" (a
  service systemd could not be asked about is `active: null`, not "stopped").
  One snapshot serves all requests for 5 s.
  Advertised as `host_metrics`.
- **Lost-run resolution least-privilege.** `POST /v1/runs/{id}/resolve-unknown`
  (newer Hermes cores report a run they lost, e.g. after a crash, as
  `unknown`) accepts exactly `{"admission_id": <string>, "execution_generation":
  <int>}` and re-serialises it; any other field is refused with `400`. The core
  closes the lost turn as interrupted and never re-runs it. Audited as
  `resolve_unknown`, advertised as `run_unknown_resolution`.
- **Chat management least-privilege.** `PATCH /api/sessions/{id}` accepts
  exactly `{"archived": <bool>}` and re-serialises it; title, pin, hidden,
  unread and `end_reason` are refused with a closed `400` and never reach
  Hermes. `DELETE /api/sessions/{id}` takes no body (`400 body_not_allowed`
  otherwise); a running turn is answered by the core with `409
  session_active_turn`. Both are audited (`session_archive` /
  `session_delete` with the session ID) and advertised as the
  `session_archive` / `session_delete` features.
- **Approval least-privilege.** `POST /v1/runs/{run_id}/approval` is validated
  locally: only `once`/`deny` are accepted, `request_id` must be a non-blank
  string ≤ 256 chars (control-free), and the body must contain exactly
  `{choice, request_id}`. `session`/`always`/unknown/missing/wrong-typed/extra
  fields are refused with a closed `400` and **never reach Hermes**. A core
  with the session authority (Hermes 0.22+) also requires the run's current
  `execution_generation`; the bridge reads it with one `GET /v1/runs/{run_id}`
  and adds it upstream. The device never sends it, and an older core gets the
  plain `{choice, request_id}` body.
- **No redirect following.** The upstream client never follows redirects
  (`allow_redirects=False`) and a `3xx` answer fails closed (`502
  upstream_redirect`) with the `Location` dropped.
- **Query allowlist.** Only `limit`/`offset`/`order`/`include_children` on GET
  session routes are permitted; any other key (or any query on a write/run
  route) is refused (`403 query_not_allowed`).
- **Body bound.** The request limit is `10_000_000` bytes (matching the official
  api_server) so the client's supported ≤ 8 MiB encoded image bodies are not
  silently rejected. Malformed JSON yields a closed `400`, never a `500`.
- **Rate limits + true caps.** Pairing allows 60 attempts per minute per transport
  peer. The in-memory identity table is capped at 1024 live windows; a full table
  refuses new identities without evicting another client’s active budget. The
  store also caps live codes and active devices.
- **Durable, hashed credentials.** Device tokens and pairing codes are stored
  only as SHA-256 hashes in SQLite; the file is 0600 in a 0700 directory. Any
  store error fails **closed** (`503 store_unavailable`), never open.
- **No admin surface.** Pairing codes are issued only through the local CLI
  (`orsik-mobile-bridge admin pairing-code`); admin/device authority is
  strictly separated. `/admin/*` is out of scope for HTTP clients.
- **No credential leakage.** Bridge-origin errors use a closed body
  (`{"error":{"code":…}}`); upstream response headers are not relayed; the
  master key is used only in the upstream `Authorization` header.

### Pairing behind Tailscale Serve

A reverse proxy makes several devices appear as the same loopback peer. For a
backend reached **only through trusted Tailscale Serve and trusted local
processes**, set `BRIDGE_TRUST_TAILSCALE_IDENTITY=1` in `bridge.env`. This opts into
separate pairing budgets per `Tailscale-User-Login`, received from a loopback peer.
It never replaces the one-time pairing code or device-token authentication.
The default is off; arbitrary `X-Forwarded-For` headers are always ignored.

[Tailscale’s Serve documentation](https://tailscale.com/docs/features/tailscale-serve#identity-headers)
states that Serve strips incoming copies of its identity headers and adds the
requester’s user identity. All devices of one user share a budget. Tagged devices
have no user identity and fall back to the shared transport-peer budget, as do
missing, duplicate or oversized identity headers. A different proxy needs its
own verified identity contract; do not enable this option for it. Local callers
can forge these headers, so the opt-in requires a trusted local host. Identity
values are hashed in memory and never added to audit logs.

## Endpoints

| Method | Path | Auth |
|---|---|---|
| GET | `/health` | none (proxied) |
| GET | `/v1/capabilities` | device token |
| POST | `/v1/devices/pair` | pairing code (rate-limited) |
| POST | `/v1/devices/self/revoke` | device token |
| POST | `/v1/runs/{run_id}/approval` | device token (validated: once/deny) |
| GET/POST | `/api/sessions` | device token |
| GET | `/api/sessions/{id}` | device token |
| GET | `/api/sessions/{id}/messages` | device token |
| POST | `/api/sessions/{id}/chat/stream` | device token (server-side takeover) |
| GET | `/api/sessions/{id}/runs/{run_id}/stream?after_seq=N` | device token (re-attach/replay) |
| GET | `/v1/runs/{run_id}` | device token |
| POST | `/v1/runs/{run_id}/stop` `/steer` | device token |

Anything else returns `403 endpoint_out_of_scope`.

## Reconnect / run ownership

`POST /api/sessions/{id}/chat/stream` is consumed by the bridge, not streamed
through blindly: the bridge holds the upstream SSE connection independently of
the calling client, buffers each run's frames by `seq`, and survives a
downstream disconnect (the run is not interrupted). A client re-attaches with
`GET /api/sessions/{id}/runs/{run_id}/stream?after_seq=N`, which replays exactly
`seq > N` then follows live.

- A requested `after_seq` older than the retained backlog fails closed with
  `409 resume_gap`; the bridge never fabricates events and never re-issues the
  originating chat POST.
- A run the bridge cannot reconstruct (e.g. after a bridge restart) is reported
  as indeterminate: `409 resume_gap` when the authoritative upstream status is
  still known, else `404 run_not_found`. The client then falls back to its own
  `GET /v1/runs/{run_id}` status recovery.

### Memory bounds and backpressure

Every accumulator is capped; the limits are module constants in
`sse.py`/`runhub.py`:

- One SSE frame block from upstream may be at most `MAX_FRAME_BYTES` (1 MiB,
  measured after CR/LF normalisation). A larger frame is never relayed and the
  upstream is closed: before the first frame the POST answers
  `502 upstream_frame_too_large`; afterwards the frames received so far are
  relayed and the stream ends with a `: bridge_upstream_frame_too_large`
  comment — never with a fabricated `done`.
- The core echoes the whole request (base64 image included) in the first
  `run.started` frame, so an image turn legitimately exceeds 1 MiB. The session
  chat pump therefore decodes with a body-coupled bound
  (`_MAX_BODY_BYTES + 64 KiB`), removes the `user_message` field from that one
  frame (the only place the bridge re-serialises upstream content instead of
  relaying it byte-for-byte) and then applies the 1 MiB relay bound above to
  what is actually sent. The bound itself (`MAX_FRAME_BYTES`) is unchanged.
- A non-SSE upstream error body is read up to 64 KiB; larger →
  `502 upstream_error_too_large`.
- Per-run backlog: 1000 frames and 4 MiB (oldest evicted first). Once frames
  were evicted, any `after_seq` below the last evicted seq — including an
  omitted `after_seq` — fails closed with `409 resume_gap`.
- Per-subscriber queue: 256 items and 2 MiB, at most 4 subscribers per run
  (the oldest is dropped). A slow subscriber is dropped with a
  `: bridge_overflow` comment instead of blocking the pump; it re-attaches with
  `after_seq`.
- At most 16 active runs; a further chat POST gets `503 bridge_busy` with
  `Retry-After: 5` **before** any upstream POST. Finished runs are retained up
  to 64 MiB in total (oldest evicted first) besides the 256-run / 600 s
  retention.

## Device authorization model (explicit)

The official Hermes API has **no per-device resource ownership**. The bridge
implements a deliberate **single-owner delegation** model — every paired device
acts as a delegate of the one owner across all sessions/runs, exactly as an
operator's own client would.

Consequences, documented as residual risk:
- The route allowlist bounds the *endpoint* surface, not *which* session/run a
  device may touch.
- Guaranteed per-device isolation is **not** provided and is **not** faked. It
  would require a Hermes core extension.
- Effective constraints: pairing is operator-gated (local CLI), device tokens are
  individually revocable (durably), and the approval choice is narrowed to the
  least-privilege set.

## Install / run

Dependencies are pinned, not resolved on install day: `install.sh` exports the
frozen `uv.lock` (hashes included) and installs it with `--require-hashes`,
then installs the bridge itself with `--no-deps`. A dependency therefore only
changes when someone does it on purpose, via
`uv lock --upgrade-package <pkg>` and a review of the lock diff (`uv lock
--check` in CI fails on a stale lock). CI also builds the wheel, a
`SHA256SUMS` file and a CycloneDX SBOM and uploads them as the
`bridge-dist` artifact. See `docs/bridge-installation.md`. Quick start:

```sh
./install.sh --systemd /path/to/bridge/source
# edit $BRIDGE_HOME/bridge.env, then:
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env $BRIDGE_HOME/venv/bin/orsik-mobile-bridge   # serve
```

`bridge.env`:

```
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8642
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
BRIDGE_STATE_DB=/home/USER/.local/orsik-mobile-bridge/state.db
BRIDGE_PUBLIC_BASE_URL=https://<host>
```

### Preflight: `./install.sh --check`

Verifies an existing install and changes nothing. It parses
`$BRIDGE_HOME/bridge.env` line by line as data (`KEY=VALUE`, comments and blank
lines ignored — never sourced, never eval'd) and prints one line per check
(`OK`/`WARN`/`FAIL`), never a key or token value. Exit code is 1 if anything
FAILed, with a closing `PREFLIGHT: PASS` or `PREFLIGHT: FAIL (<n> failed)`.

Checks: `home_mode` (0700) · `env_mode` (0600) · `venv` (CLI executable) ·
`bind` (loopback) · `upstream` (loopback plain HTTP with port) ·
`hermes_env` (set, 0600, non-empty `API_SERVER_KEY`) · `public_base_url`
(empty is a WARN: no deep link/QR, otherwise `https://`) · `port` (listener on
loopback/`0.0.0.0` is a WARN: perhaps a running bridge) · `hermes_health`
(GET `$UPSTREAM/health` == 200).

The install path refuses to start before touching `$BRIDGE_HOME` when `uv` or a
usable `uv python find ${BRIDGE_PYTHON:-3.14}` is missing.

## Tests

```sh
python -m unittest discover -s tests -v         # core + HTTP-layer + store + reconnect
python tests/acceptance_live.py ./bridge.env    # live end-to-end (needs a bridge)
python tests/adversarial_live.py ./bridge.env   # live attack probe: auth, scope, bodies, revoke, leaks
python tests/approval_live.py ./bridge.env 3    # live deny: a denied dangerous command never runs
python tests/soak_live.py ./bridge.env 6 40     # live soak: parallel devices, disconnects, re-attach
```

## Rollback

`./install.sh --uninstall` stops/removes the user unit; delete `$BRIDGE_HOME`
(venv + state DB). Hermes is untouched.
