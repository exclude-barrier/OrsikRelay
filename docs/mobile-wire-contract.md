# Hermes Mobile Wire Contract

**Status:** Stabilized, versioned client contract for Orsik Relay.
**Client:** `HermesConnectionClient` (capability negotiation), `HermesDevicePairClient`,
`HermesDeviceSelfRevokeClient`, `HermesRunApprovalClient`, `HermesSessionStreamClient`.
**Goal:** Orsik Relay does not depend permanently on a private, incompatible
Hermes special build, but speaks an explicitly versioned, fail-closed,
negotiable mobile contract.

---

## 1. Protocol versioning (explicit)

- **One** mobile protocol version versions all soft mobile capabilities
  (session-chat interactive approvals **and** device credentials).
- The client speaks **Mobile Protocol Version 1**:
  `HermesConnectionClient.MOBILE_PROTOCOL_VERSION = 1`.
- The only negotiable set is the closed set
  `SUPPORTED_MOBILE_PROTOCOL_VERSIONS = { 1 }`.
- **Fail-closed rule** (central in `isSupportedMobileProtocolVersion`):
  A contract is accepted only if `protocol_version`
  is an **integer** from `SUPPORTED_MOBILE_PROTOCOL_VERSIONS`.
  - missing (`null`) → **closed** (legacy, cannot be negotiated)
  - non-integer (string `"1"`, float `1.0`, array) → **closed**
  - integer outside the set (`0`, `2`, `3`, …) → **closed**
- Implicit version assumptions: **none.** The version is not derived from the
  document shape, the `/health` `version` string, or the number of endpoints,
  but exclusively from the explicit field.

> `/health.version` (e.g. `0.20-test`) is a **free, non-version-binding**
> string. It is stored for display only and controls **no**
> feature enablement.

## 2. Contract layers

### 2.1 `GET /health` (hard, fail-closed → `INCOMPATIBLE_API`)
```json
{ "status": "ok", "platform": "hermes-agent", "version": "<non-blank string>" }
```
- `status` exactly `ok`, `platform` exactly `hermes-agent`, `version` non-empty.
- Any deviation → probe fails (`INCOMPATIBLE_API`).

### 2.2 `GET /v1/capabilities` (hard + soft)

**Hard core (fail-closed → `INCOMPATIBLE_API`):**
```jsonc
{
  "object": "hermes.api_server.capabilities",
  "platform": "hermes-agent",
  "auth":    { "type": "bearer", "required": true },
  "runtime": { "mode": "server_agent", "tool_execution": "server" },
  "features":{ "session_resources": true, "session_chat_streaming": true },
  "endpoints": {
    "health":              { "method": "GET",  "path": "/health" },
    "sessions":            { "method": "GET",  "path": "/api/sessions" },
    "session_chat_stream": { "method": "POST", "path": "/api/sessions/{session_id}/chat/stream" }
  }
}
```
- All fields are **required** and are strictly checked for exact value.
- The three core endpoints are checked for exact `method`+`path`.

**Soft mobile capabilities (fail-closed → `false`, connection remains usable):**

1. `session_chat_interactive_approvals`
   - Gate: `features.run_approval_response == true`.
   - `features.session_chat_interactive_approvals`:
     exactly `{ enabled:true, protocol_version:1, event:"approval.request",
     choices:["once","deny"], response_endpoint:"run_approval" }`.
     `choices` exactly two entries, in this order.
   - `endpoints.run_approval`: `{ method:"POST", path:"/v1/runs/{run_id}/approval" }`.
   - Result `Success.sessionChatInteractiveApprovals`; persisted per profile
     (`SharedPreferencesRunApprovalCapabilityStore`); when `true`, the
     stream client sends the header flag `X-Hermes-Interactive-Approvals: 1`.

2. `device_credentials` (Protocol v1)
   - `features.device_credentials`:
     exactly `{ enabled:true, protocol_version:1,
     pair_endpoint:"device_pair", self_revoke_endpoint:"device_self_revoke" }`.
   - `endpoints.device_pair`: `{ method:"POST", path:"/v1/devices/pair" }`.
   - `endpoints.device_self_revoke`: `{ method:"POST", "path": "/v1/devices/self/revoke" }`.
   - Result `Success.deviceCredentialsSupported`.

3. Chat management (archive / delete, Orsik bridge)
   - `features.session_archive == true` and `features.session_delete == true`.
   - `endpoints.session_update`: `{ method:"PATCH", path:"/api/sessions/{session_id}" }`,
     `endpoints.session_delete`: `{ method:"DELETE", path:"/api/sessions/{session_id}" }`.
   - Read by the Agents list when it opens (not persisted); only then does a
     long press offer archive and delete. The bridge forwards PATCH only with
     exactly `{ "archived": <bool> }` and DELETE only without a body; a running
     turn answers `409 session_active_turn`, a confirmed absent chat `404`.

4. Lost-run resolution (newer Hermes cores, Orsik bridge)
   - `features.run_unknown_resolution == true`,
     `endpoints.run_unknown_resolution`: `{ method:"POST", path:"/v1/runs/{run_id}/resolve-unknown" }`.
   - Used only after the manual status read of the chat's own uncertain run
     returned `status:"unknown"` with `admission_id` (string) and
     `execution_generation` (int); the chat then offers "close as cancelled".
     One tap sends exactly those two fields once; success (200 for the same
     `run_id`) frees the chat, `stale_generation` drops the offer. A core
     without the `unknown` status never triggers it.

5. Slash commands in the chat (Orsik bridge)
   - Read when a chat opens (not persisted). A command is offered only when
     its endpoint is advertised with exact `method`+`path`:
     `/new` → `session_create`, `/stop` → `run_stop`, `/steer` → `run_steer`,
     `/title` → `session_update` plus `features.session_title == true`,
     `/branch` → `endpoints.session_fork`: `{ method:"POST", path:"/api/sessions/{session_id}/fork" }`,
     `/model` → `endpoints.session_model_lock`: `{ method:"POST", path:"/api/sessions/{session_id}/model" }`
     plus `endpoints.model_options`: `{ method:"GET", path:"/api/model/options" }`.
   - The bridge forwards PATCH title only as exactly `{ "title": <1–512 chars,
     no control characters> }`, fork only with the body `{}`, and the model
     lock only as exactly `{ "provider", "model" }`; all three need the
     `chat` permission and are audited without their values.
   - Command POSTs are never retried; an unclear result is shown as failed.
     A leading `/word` never reaches the model; a path such as
     `/var/log/syslog` stays ordinary text.

> **Field strictness:** The soft contract objects are checked against **exactly
> the expected field set** (missing **or** additional field → closed).
> This is deliberately conservative: the client enables a mobile capability only
> when the server advertises exactly the v1 shape. See §4 “Upgrade rules”.

### 2.3 `POST /v1/devices/pair` (unauthenticated)
- Body exactly `{ "code": "<24-character code>" }`, `Content-Type: application/json`,
  **no** `Authorization`.
- The code is normalized locally (trim, uppercase, remove `-`/whitespace/`_`)
  and must be exactly 24 characters from the alphabet (A–Z without I/L/O/U + 1–9);
  otherwise rejected locally (no request).
- Success 201, exactly:
  ```jsonc
  { "object": "hermes.device.credential",
    "device_id": "<uuid>",
    "device_key": "hd_<43 base64url>",
    "profile": "<string>", "label": "<string>",
    "permissions": ["approvals","chat","runs"],   // exactly this set, any order
    "issued_at": <finite number> }
  ```
- **No expansion:** `permissions` must be exactly `{ approvals, chat, runs }`;
  every additional right (e.g. `admin`, `jobs`, `memory`, `cron`) →
  `IncompatibleApi` (closed). Mobile credentials never exceed
  `chat`/`runs`/`approvals`.
- Error codes 400/409/429/500 → closed reason; every other code/
  state → closed.

### 2.4 `POST /v1/devices/self/revoke` (Bearer device key)
- Body exactly 0 bytes, no `Content-Type`.
- Success 200, exactly `{ "revoked": true|false, "device_id": "<uuid>" }`
  (`revoked=true` fresh, `revoked=false` already revoked). Once the bridge
  store has pruned the revoked device, its key is unknown and the answer is
  `401`, like for any other unknown key. A non-empty body is `400
  body_not_allowed`.
- 401 → closed (does not distinguish API key / unknown / revoked).

### 2.5 `POST /v1/runs/{run_id}/approval` (Bearer)
- Body `{ "choice": "once"|"deny", "request_id": "<id>" }`.
- **Decision exclusively `once` / `deny`** (`RunApprovalChoice`).
  `session`/`always` cannot be represented in the client.
- Success 200, exactly:
  `{ "object":"hermes.run.approval_response", "run_id":<requested>,
  "choice":<sent>, "resolved":<n>=1> }`.
- 409 (`approval_not_pending`/`approval_not_active`) → `NoPending`, never
  resubmitted. **Approvals are never confirmed automatically.**

### 2.6 `GET /v1/terminal` (Bearer, WebSocket; only with `features.terminal`)
- Advertised as `features.terminal: true` plus `endpoints.terminal`
  `{"method":"GET","path":"/v1/terminal"}` only when the operator enabled it;
  otherwise `403 endpoint_out_of_scope`.
- Upgrade request without `Origin` and without a query (`403`); no upgrade
  `400 websocket_required`; more than 4 open `429 terminal_limit`; the PTY
  cannot start `503 terminal_unavailable`; 401 as everywhere.
- Frames: binary = PTY bytes in both directions (UTF-8 and escape sequences,
  split anywhere); text client → bridge exactly
  `{"type":"resize","cols":1..500,"rows":1..300}`. Any other text frame closes
  with `1008`; a message over 64 KiB with `1009`.
- Close: `1000` the shell (tmux client) ended, `4401` the device was revoked,
  `1008` protocol error. The tmux session survives a close.
- The client never reconnects on its own; opening a shell is not an
  idempotent read.

### 2.7 Read-only files (Bearer; only with `features.files` / `features.projects`)
- `GET /v1/files/list?path=<rel>` → `{"object":"orsik.files.list","root":<absolute
  root>,"path":<rel>,
  "entries":[{"name","type":"dir"|"file"|"other","size":<int>|null,
  "modified_at":<int>|null}],"truncated":<bool>}` (folders first, ≤ 2000).
- `GET /v1/files/content?path=<rel>` → the bytes with `Content-Type`
  `image/png|jpeg|webp|gif`, `text/plain; charset=utf-8` or
  `application/octet-stream`; ≤ 8 MiB.
- `GET /v1/projects` → `{"object":"orsik.projects.list","projects":[{"name",
  "path","branch":<str>|null,"dirty":<bool>|null,"last_commit":{"short",
  "committed_at","subject"}|null}],"truncated":<bool>}`.
- `path` is relative to the operator's root ("" or missing = the root), the
  only query key, once. Errors: `403 path_not_allowed` (leaves the root),
  `403 endpoint_out_of_scope` (switched off), `404 not_found`, `413
  file_too_large`. Reads only; the client may retry them.

## 3. Capability negotiation & fail-closed

| Situation | Result |
|---|---|
| hard core incorrect/missing | probe `Failure(INCOMPATIBLE_API)` |
| soft contract: missing / partial / malformed | feature `false`, connection `Success` |
| `protocol_version` missing / non-integer / outside `{1}` | feature `false` |
| `permissions` ≠ `{approvals,chat,runs}` | `pair` → `IncompatibleApi` |
| non-JSON / oversized / wrong `object`/`platform` | probe `Failure(INCOMPATIBLE_API)` |
| 401/403 | `INVALID_CREDENTIALS` |
| 429 | `RATE_LIMITED` (never retry) |
| redirect / TLS error / timeout / network | closed reason, never followed |

Invariants (invariant security boundaries, non-negotiable):
- HTTPS-only, TLS active, no cleartext, no redirects, no
  connection/write retries.
- No access to admin, jobs, memory, cron, or other privileged APIs.
- No secrets or sensitive tool payloads in mobile responses; errors
  contain no body/URL/key/server details.
- Approvals never automatically.

## 4. Upgrade / compatibility rules

- **Backward compatible:** A server that advertises exactly the v1 contract is
  accepted. The client does not depend on a private special build but on
  the **documented v1 shape**.
- **No implicit stepping forward:** A server that changes the v1 shape
  (adds/renames a field) or advertises a different `protocol_version` is
  **fail-closed** rejected. There is no silent fallback.
- **Future version (v2):** To support v2, (a) extend `SUPPORTED_MOBILE_PROTOCOL_VERSIONS`
  to `{ 1, 2 }` **and** (b) add a v2 field schema. The v1 shape
  is **not** silently extended in the process. Until then, v2 remains closed.
- **No desktop/CLI regressions:** The changes are exclusively in the
  Android client; the Hermes server configuration (loopback/LAN-VPN,
  `127.0.0.1:8642`) and desktop/CLI usage remain untouched.

## 5. Gap analysis

> **Source basis:** documented live status (verified on the Hermes server on
> 30.08.2026) + client code. The Hermes
> source base itself is **not** local and is not reachable from this machine
> (Hermes server, `127.0.0.1:8642`); a fresh cross-reading of the
> Hermes source code did **not** take place here.

1. **What Orsik Relay expects:** exactly the v1 contract in §2 (hard core +
   two soft capabilities + pair/revoke/approval endpoints). Fail-closed.
2. **What Hermes offers according to the documented live status:** chat/streaming,
   runs, approval, sessions, skills, toolsets, models; **not** admin write,
   jobs admin, memory write, audio/realtime, CORS. `jobs_admin=false`.
3. **Private Hermes changes that are currently required:** The
   mobile endpoints (`/v1/devices/pair`, `/v1/devices/self/revoke`,
   `session_chat_interactive_approvals`, `X-Hermes-Interactive-Approvals`) are
   part of the private Hermes server build. They are now
   explicitly versioned in the client contract, so that they can be
   **transferred 1:1 into an official, versioned
   Hermes mobile contract** without the client remaining coupled to the
   private build.
4. **What is needed for a stable, versioned, upstream-capable contract:**
   - (done) explicit `protocol_version` negotiation in the client;
   - (done) documented wire contract + upgrade rules (this document);
   - (open, server-side) Hermes should expose `protocol_version` as a stable,
     documented quantity and bump it on shape changes;
   - (open, server-side) an optional top-level `protocol_version` in
     `/v1/capabilities` that identifies the mobile protocol version independently of the
     per-feature blocks (client-optional; no hard
     requirement, in order to keep compatibility with the current state).

## 6. Verification

- `./gradlew test` — existing + new compatibility tests.
- New tests: `MobileProtocolCompatibilityTest` (v1 open; 0/2/3/100/missing/
  non-integer closed; per-capability independence; malformed → `INCOMPATIBLE_API`).
- Unchanged: `HermesConnectionClientTest`, `DeviceCredentialCapabilityTest`
  (exact-field strictness remains → all previous tests green).

## 7. Pairing deep link / bootstrap contract (zero-config)

**Status:** Client-side bootstrap contract for Orsik Relay onboarding.
The deep link is the **only** input a fresh app needs in order to obtain
the server base URL + pairing code — without manually entering a base URL or
API key (product gate).

### 7.1 Format (exact)

```
orsik://pair?server=<urlencoded-HTTPS-BaseURL>&code=<24-Char-Code>
```

- `scheme` exactly `orsik` (client case-insensitive), `host` exactly `pair`.
- Query: **exactly two** parameters, both required: `server` + `code`.
- `server`: percent-encoded (UTF-8) HTTPS base URL (`:`→`%3A`,
  `/`→`%2F`); the client decodes robustly (already-plain values accepted).
- `code`: the raw 24-char code verbatim (uppercase letters/digits, no
  special characters → percent-encoding is a no-op here). **No**
  display dashes in the link (those exist only in the CLI output).

### 7.2 Fail-closed parser (client: `PairingDeepLink.parse`)

Returns `null`, never throws (even on malformed percent-encoding).

| Rule | Result |
|---|---|
| blank / wrong `scheme` or `host` / missing query | `null` |
| missing or empty `server` or `code` | `null` |
| `#` fragment | `null` |
| `server` not absolute HTTPS or with `user:pass@` | `null` |
| `code` violates the `DevicePairCode` contract (≠24 / ∉31 alphabet) | `null` |
| malformed percent-encoding (e.g. `%ZZ`) | `null` (no exception) |

### 7.3 Code rules

- Length **exactly 24**, alphabet **exactly 31** characters: `A–Z` without `I/L/O/U`
  + `1–9`.
- Display (CLI): 6 groups of 4 characters, separated by `-` (29 characters).
- Client normalization: trim → uppercase → remove `-`/`_`/whitespace →
  exact-24 + alphabet check; deviation is rejected locally (no request).
- **TTL 300 s, single-use, SHA-256 hash at rest.** Unusable after redeem or expiry.
- **Device key (`hd_`) never in the link** — only after a successful redeem.

### 7.4 Server URL rules

- Absolute HTTPS URL, host required, **no** user info, no cleartext.
- Fail-closed: `http://`, relative/schemeless, `user:pass@host` → rejected.
- Server-side source: `API_SERVER_PUBLIC_BASE_URL` (`.env`/environment).

### 7.5 Zero-config flow (client)

1. `hermes devices pair --label X` generates the 24-char code; with
   `API_SERVER_PUBLIC_BASE_URL` set, additionally a deep link + ASCII QR.
2. The app opens `orsik://pair?…` (tap or QR scan; `BROWSABLE` intent filter).
3. `MainActivity` (`onCreate`/`onNewIntent`) parses the link fail-closed →
   `PairingBootstrap`.
4. Onboarding pre-fills the profile name (host label), base URL + pairing code.
5. The user taps **Connect** → `DevicePairingCoordinator.connect` (re-validates
   URL + code) → `POST /v1/devices/pair`.
6. `201` → device key in the Keystore, connection active.
- **No auto-connect:** pairing requires the user's explicit connect consent
  (pre-fill yes, auto-submit no).

### 7.6 Protocol version

Pairing belongs to **Mobile Protocol v1** (feature `device_credentials`,
`protocol_version=1`, §2.2). The deep link itself is version-free (only
client bootstrap); contract security comes from the pair/revoke endpoints
(§2.3/§2.4) and the code contract (§7.3).

### 7.7 Server-side generation (private extension)
The **canonical** mobile-v1 source (`hermes devices pair`) outputs **only** the
24-char code. The deep-link + ASCII QR generation
(`_build_bootstrap_link` / `_render_qr_ascii` / `_public_base_url`) is a
**private server extension** (dev-laptop test setup) that bundles
`API_SERVER_PUBLIC_BASE_URL` + code into the link above. It is **not**
part of the canonical mobile-v1 commit and is not assumed as a standing
contract — the client contract (§7.1–§7.6) remains independent of it.

The **Orsik Mobile Bridge** sidecar implements the same pairing contract
for a fresh, unmodified official Hermes installation; there the
local operator CLI (`orsik-mobile-bridge admin pairing-code`) generates the code, deep link, and
optionally a QR code (`docs/bridge-installation.md`).

## 8. Run authority & recovery

**Status:** Checked read-only against the locally available mobile-v1 source state
(in the Hermes mobile-v1 source state: `gateway/platforms/api_server.py`,
`api_server_runs.py`, `api_server_run_idempotency.py`). The approval of
single-slot run ownership relies on these statements. No E2E run against a
real server (not verified, see below).

### 8.1 Run independence from the SSE socket

`POST /api/sessions/{session_id}/chat/stream` executes the run in a
background task. On a socket disconnect:

- **with** header `X-Hermes-Interactive-Approvals` (the client sends it only when the
  `session_chat_interactive_approvals` capability is confirmed): the run continues,
  the bounded replay buffer (`_SessionEventQueue`, not
  destructive) preserves the tail including the terminal `run.completed`/`error` and
  `done`;
- **without** this header: `_drain_session_stream_task_on_disconnect`
  interrupts the running run.

The assumption “a Hermes run is independent of the socket” therefore applies only to
the mobile-v1 opt-in. A terminal completion is `assistant.completed` →
`run.completed` → `done` or `error` → `done` (the server sets the status to
`failed`).

### 8.1.1 Events of the session chat stream (checked 2026-10-20)

Checked read-only against the official core (`f86276d2`,
`gateway/platforms/api_server.py`, session chat stream):

- every frame carries `session_id`, `run_id`, `seq` and `ts` (`_SessionEventQueue.payload`);
- reasoning is **not** sent as `reasoning.*` but as `tool.progress` with
  `tool_name: "_thinking"` and the text in `delta`; the app shows only "thinking"
  and drops the text, the bridge drops `delta`;
- Codex-style models send `assistant.commentary` (`text`, `already_streamed`) inside the
  message window; the app accepts and drops it, the bridge drops `text`;
- `run.completed` / `run.failed` / `run.cancelled` carry the whole turn transcript in
  `messages` plus `usage`/`runtime`. The app reads only `pending_steer`; the bridge drops
  the rest, because the transcript alone can exceed the 1 MiB relay bound;
- `assistant.completed` carries the whole final answer in `content` on one line, so the
  app's SSE line and frame bound equals the bridge relay bound (1 MiB).

History (`GET /api/sessions/{id}/messages`): a turn with an image is stored and
returned as its multimodal part list (`text` / `image_url` parts, see
`agent/session_persistence.py`), not as a string. The app joins the text parts and
counts the image parts; the bridge removes the inline image data, the `content` of
tool messages, `tool_calls` and the reasoning fields.

### 8.2 Re-attach (existing)

`GET /api/sessions/{session_id}/runs/{run_id}/stream?after_seq=N`:
strictly exclusive replay (`seq > N`), then follow until terminal. Non-
replayable buffer → 409 `resume_gap`; unknown/foreign run or
session mismatch → 404. Client: an invalid `after_seq` is rejected locally.

### 8.3 Reading run status (`_handle_get_run`)

`GET /v1/runs/{run_id}` (Bearer, `permission="status"`) returns the pollable
status object `{"object":"hermes.run","run_id":<id>,"status":<s>, …}` for the
client's own run. Closed status set (`_set_run_status`):

```
queued, running, waiting_for_approval, stopping,
completed, failed, cancelled, interrupted
```

`TERMINAL_STATUSES` (`api_server_run_idempotency.py`) =
`{completed, failed, cancelled, interrupted}`. Terminal statuses are retained
until `_RUN_STATUS_TTL = 3600 s` for polling
(`_RUN_STREAM_TTL = 300 s` concerns only the transport buffer). Foreign/
unknown/expired run → 404 `run_not_found`.

Client contract: **only** a terminal status authoritatively releases
run ownership; `queued`/`running`/`waiting_for_approval`/`stopping` or a
read error change nothing. The client reads exclusively `run_id` and
`status`; `output`, `error`, `usage`, `session_id`, `pending_steer`, etc. are
neither read nor propagated.

> **Contract gap:** `GET /v1/runs/{run_id}` is (like `stop`/`steer`) **not**
> part of the negotiated capability set from §2.2; the client uses it like the
> other `/v1/runs/{run_id}/*` endpoints from the same authenticated
> namespace. For a fully upstream-capable, fail-closed
> negotiated contract, the server should explicitly expose this run-status capability (including
> `protocol_version`).

### 8.4 Boundary without a confirmed run ID

If no confirmed run ID exists after the initial POST (`run.started` never
seen), there is **no** discovery/reconciliation endpoint
(`session → run`). The POST may nevertheless have been accepted server-side.
The client then **does not** release ownership and sends **no** new POST;
the outcome is honestly presented as undetermined. Safe recovery
requires an additional server capability (e.g. session→active-run
discovery or an idempotent start confirmation).

A 5xx on the initial streaming POST also counts as undetermined (a
gateway/upstream may deliver it after admission has already occurred); only a
3xx/4xx response proves a rejection before the run.

### 8.5 E2E caveat

**No** real Hermes mobile-v1 E2E test was executed. The
correctness of the endpoint/status assumptions relies on the local
source and MockWebServer tests, not on a live server.

## 9. Status of the Mobile Bridge (2026-10-10)

The client contract of this document remains valid unchanged. What is new is that the
**server** (Orsik Mobile Bridge, branch `p13/full-mobile-bridge`) now
fully satisfies it in front of an **unmodified** official Hermes (`f86276d2`):

- `session_chat_interactive_approvals` is advertised exactly as v1 (`once`/`deny`);
  `approval.request` reaches the client on the session chat stream, the response
  is sent via `POST /v1/runs/{run_id}/approval`. The official core emits
  the events (not simulated).
- The re-attach described in §8.2
  `GET /api/sessions/{session_id}/runs/{run_id}/stream?after_seq=N` is now
  **available server-side**: the bridge takes over the chat stream, buffers
  events per `run_id`/`seq`, and replays exclusively from `after_seq`, then live-follows.
  Gap → `409 resume_gap`; unknown run → `404 run_not_found`. No
  renewed chat POST, no invented events.
- Persistent device credentials: pairing and (individual) revoke survive
  the bridge restart; tokens/code only as SHA-256 hash at rest.

Limits remain: **no** per-device resource isolation (single-owner
delegation) and **no** session→run discovery endpoint.
