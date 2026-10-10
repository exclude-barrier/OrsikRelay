# Orsik Mobile Bridge (PoC)

A minimal, **installable** bridge that exposes the Orsik Mobile Protocol v1
surface in front of an **unmodified official Hermes** API server.

It is the sidecar chosen in the P12 architecture comparison
(`docs/hermes-fresh-compat.md`). It does not patch Hermes, does not run inside
the Hermes process, and does not expose the Hermes API key.

Public paths mirror the api_server namespace the shipped `HermesConnectionClient`
already speaks, so the client is a **drop-in**: it can point at the bridge base
URL unchanged.

## What it does

- Runs on `127.0.0.1` only (TLS is provided out-of-band, e.g. Tailscale Serve).
- Reads the Hermes `API_SERVER_KEY` from a 0600 env file and keeps it server-side.
- Mints **device tokens** from short-lived, single-use **pairing codes**
  (24 chars, 300 s TTL, stored as SHA-256).
- Gives every device token a fixed allowlist: `chat`, `runs`, `approvals`.
- Proxies **only** a closed set of paths upstream (`core.PROXIED_ROUTES`):
  `/api/sessions…` and `/v1/runs…`. No admin, cron, memory, jobs, skills or
  artifact surface.
- Emits the exact Mobile Protocol v1 capability envelope; features the
  official core lacks (interactive session approvals) are omitted so the client
  negotiates them unsupported (fail-closed), never simulated.

## Security hardening (P12.1)

- **Approval least-privilege.** `POST /v1/runs/{run_id}/approval` is validated
  locally: only `once`/`deny` are accepted, `request_id` must be a non-blank
  string ≤ 256 chars (control-free), and the body must contain exactly
  `{choice, request_id}`. `session`/`always`/unknown/missing/wrong-typed/extra
  fields are refused with a closed `400` and **never reach Hermes**. A canonical
  body is forwarded only on a valid request (a positive decision stays explicit).
- **No redirect following.** The upstream client never follows redirects
  (`allow_redirects=False`) and a `3xx` answer fails closed (`502
  upstream_redirect`) with the `Location` dropped, so the master key can never
  hop to another host.
- **Query allowlist.** Only `limit`/`offset`/`order`/`include_children` on GET
  session routes are permitted; any other key (or any query on a write/run
  route) is refused (`403 query_not_allowed`) and never forwarded.
- **Body bound.** The request limit is `10_000_000` bytes (matching the official
  api_server) so the client's supported ≤ 8 MiB encoded image bodies are not
  silently rejected. Malformed JSON yields a closed `400`, never a `500`.
- **Rate limits + true caps.** Pairing (`POST /v1/devices/pair`) and code
  issuance (`POST /admin/pairing-code`) are rate-limited; the token and code
  stores enforce hard caps (`mint`/`issue` refuse beyond the cap) — no unbounded
  growth.
- **No credential leakage.** Bridge-origin errors use a closed body
  (`{"error":{"code":…}}`); upstream response headers are not relayed; the master
  key is used only in the upstream `Authorization` header.

## Endpoints

| Method | Path | Auth |
|---|---|---|
| GET | `/health` | none (proxied) |
| GET | `/v1/capabilities` | device token |
| POST | `/v1/devices/pair` | pairing code (rate-limited) |
| POST | `/v1/devices/self/revoke` | device token |
| POST | `/v1/runs/{run_id}/approval` | device token (validated: once/deny) |
| POST | `/admin/pairing-code` | admin token (operator only, rate-limited) |
| GET/POST | `/api/sessions` | device token |
| GET | `/api/sessions/{id}` | device token |
| GET | `/api/sessions/{id}/messages` | device token |
| POST | `/api/sessions/{id}/chat/stream` | device token |
| GET | `/v1/runs/{run_id}` `/events` | device token |
| POST | `/v1/runs/{run_id}/stop` `/steer` | device token |

Anything else returns `403 endpoint_out_of_scope`.

## Device authorization model (explicit)

The official Hermes API has **no per-device resource ownership**: it exposes one
owner's sessions and runs. The bridge therefore implements a deliberate
**single-owner delegation** model — every paired device acts as a delegate of
the one owner across all sessions/runs, exactly as an operator's own client
would. This is required so Orsik Relay can list and resume *existing* sessions.

Consequences, documented as residual risk:
- The route allowlist bounds the *endpoint* surface, not *which* session/run a
  device may touch. Any paired device can read/act on any session or run of the
  owner (including ones created before it was paired).
- Guaranteed per-device isolation is **not** provided and is **not** faked. It
  would require a Hermes core extension (see P13 in `docs/hermes-fresh-compat.md`).
- Effective constraints: pairing is admin-gated (only the operator issues codes),
  device tokens are individually revocable, and the approval choice is narrowed
  to the least-privilege set.

## Run

```sh
python3 -m venv .venv && . .venv/bin/activate
pip install -e .
BRIDGE_ENV_FILE=./bridge.env orsik-mobile-bridge
```

`bridge.env`:

```
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8899
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
BRIDGE_ADMIN_TOKEN=<openssl rand -hex 32>
```

## Tests

```sh
python -m unittest discover -s tests -v       # core + HTTP-layer tests
python tests/acceptance_live.py ./bridge.env   # live end-to-end (needs a bridge)
```

## Rollback

Stop the process and delete `$BRIDGE_HOME`. Hermes is untouched.
