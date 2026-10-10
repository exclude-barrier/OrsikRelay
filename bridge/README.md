# Orsik Mobile Bridge

A minimal, **installable** sidecar that exposes the Orsik Mobile Protocol v1
surface in front of an **unmodified official Hermes** API server.

It is the sidecar chosen in the P12 architecture comparison
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
  survive a bridge restart (P13).
- Proxies **only** a closed set of paths upstream (`core.PROXIED_ROUTES`):
  `/api/sessions…` and `/v1/runs…`. No admin, cron, memory, jobs, skills or
  artifact surface.
- Takes over the session chat stream **server-side** so a lost client
  connection does not interrupt the Hermes run, and exposes an `after_seq`
  re-attach endpoint (P13).
- Advertises the Mobile Protocol v1 capability envelope, including the
  `session_chat_interactive_approvals` contract the official core now supports,
  narrowed to the least-privilege choices `once`/`deny`.

## Security hardening (P12.1 + P13)

- **Approval least-privilege.** `POST /v1/runs/{run_id}/approval` is validated
  locally: only `once`/`deny` are accepted, `request_id` must be a non-blank
  string ≤ 256 chars (control-free), and the body must contain exactly
  `{choice, request_id}`. `session`/`always`/unknown/missing/wrong-typed/extra
  fields are refused with a closed `400` and **never reach Hermes**.
- **No redirect following.** The upstream client never follows redirects
  (`allow_redirects=False`) and a `3xx` answer fails closed (`502
  upstream_redirect`) with the `Location` dropped.
- **Query allowlist.** Only `limit`/`offset`/`order`/`include_children` on GET
  session routes are permitted; any other key (or any query on a write/run
  route) is refused (`403 query_not_allowed`).
- **Body bound.** The request limit is `10_000_000` bytes (matching the official
  api_server) so the client's supported ≤ 8 MiB encoded image bodies are not
  silently rejected. Malformed JSON yields a closed `400`, never a `500`.
- **Rate limits + true caps.** Pairing is rate-limited; the store enforces hard
  caps for live codes and active devices.
- **Durable, hashed credentials.** Device tokens and pairing codes are stored
  only as SHA-256 hashes in SQLite; the file is 0600 in a 0700 directory. Any
  store error fails **closed** (`503 store_unavailable`), never open.
- **No admin surface.** Pairing codes are issued only through the local CLI
  (`orsik-mobile-bridge admin pairing-code`); admin/device authority is
  strictly separated. `/admin/*` is out of scope for HTTP clients.
- **No credential leakage.** Bridge-origin errors use a closed body
  (`{"error":{"code":…}}`); upstream response headers are not relayed; the
  master key is used only in the upstream `Authorization` header.

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
| GET | `/v1/runs/{run_id}` `/events` | device token |
| POST | `/v1/runs/{run_id}/stop` `/steer` | device token |

Anything else returns `403 endpoint_out_of_scope`.

## Reconnect / run ownership (P13)

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

See `docs/bridge-installation.md`. Quick start:

```sh
./install.sh --systemd /path/to/bridge/source
# edit $BRIDGE_HOME/bridge.env, then:
$BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code --base-url https://<host>
$BRIDGE_HOME/venv/bin/orsik-mobile-bridge              # serve
```

`bridge.env`:

```
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8899
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
BRIDGE_STATE_DB=/home/USER/.local/orsik-mobile-bridge/state.db
BRIDGE_PUBLIC_BASE_URL=https://<host>
```

## Tests

```sh
python -m unittest discover -s tests -v         # core + HTTP-layer + store + reconnect
python tests/acceptance_live.py ./bridge.env    # live end-to-end (needs a bridge)
```

## Rollback

`./install.sh --uninstall` stops/removes the user unit; delete `$BRIDGE_HOME`
(venv + state DB). Hermes is untouched.
