# Hermes Fresh-Install Compatibility & Mobile-Bridge Design

As of: 2026-10-10. Goal: Orsik Relay should be able to connect to a **freshly
installed, unmodified official** Hermes version — without assuming a private
fork. `hermes-server` remained unchanged.

## 1. Method and Environment

- Official source: `git clone https://github.com/NousResearch/hermes-agent`
  (HEAD `f86276d2`, branch `main`), version `vgit.f86276d` (2026.9.24).
- Isolated installation on `build-host`: `~/hermes-official-src` with
  `uv venv --python 3.14` + `uv pip install -e . aiohttp` (Python 3.14.7).
  **No** `curl | bash`, **no** changes to the official source code.
- Isolated `HERMES_HOME=~/hermes-official-home`; `api_server` on
  `127.0.0.1:8899` with its own `API_SERVER_KEY` (0600, never logged).
- Evidence: `GET /health` → 200; unauth `GET /v1/capabilities` → 401;
  authenticated → 200 with the official capability response.
- PyPI (`hermes-agent` 0.19.0) is outdated and **not** the current source;
  the official source is the Git state.

## 2. Gap Matrix: Mobile-v1 (private) vs. Official Core

| Capability | Official Core (`f86276d2`) | Mobile-v1 (private) |
|---|---|---|
| Session CRUD / messages / chat / chat stream / fork / model lock | yes | yes |
| Run submit/status/events/stop/steer/approval | yes | yes |
| Approval choices | `once`/`session`/`always`/`deny` | narrowed to `once`/`deny` |
| `run_approval_response`, `approval_events`, `tool_progress_events` | yes | yes |
| **Device pairing** `POST /v1/devices/pair` | **missing** | yes |
| **Device self-revoke** `POST /v1/devices/self/revoke` | **missing** | yes |
| **Device credentials + mobile scope allowlist** | **missing** | yes |
| **`session_chat_interactive_approvals` + `X-Hermes-Interactive-Approvals`** | **missing** | yes |
| **Session-run re-attach `…/runs/{run_id}/stream?after_seq=N`** | **missing** | yes |
| **`protocol_version` negotiation in the capability object** | **missing** | yes |
| Browser control, artifacts, `reasoning_streaming` | yes | no (client ignores) |

The official `api_server` is a **built-in platform**
(`gateway/run.py:3321`, `APIServerAdapter._http_route_table`) — the
route table is fixed; the core does not provide additional paths for
device/reconnect features.

## 3. Confirmed Official Extension Points

- **Plugin entry points:** `hermes_agent.plugins` (loading),
  `hermes_agent.plugin_capabilities` (`hermes_cli/plugins_discovery.py:27-28`).
- **PluginContext** (`hermes_cli/plugins.py`): `register_platform`,
  `register_platform_handler`, `register_tool`, `register_cli_command`,
  `register_hook`, `register_middleware`, `register_memory_provider`,
  `register_approval_transport`, `register_system_prompt_section`,
  `register_skill`, `register_context_engine`, among others.
- **Platform registry** (`gateway/platform_registry.py`): "Adapters
  (built-in and plugin) self-register here … Plugins register via
  `PluginContext.register_platform()`; `GatewayRunner._create_adapter()`
  consults the registry first".
- **Official documentation** `gateway/platforms/ADDING_A_PLATFORM.md`: "Plugin Path …
  registers via `ctx.register_platform()` … This requires **zero changes to
  core Hermes code**" (adapters inherit from `BasePlatformAdapter`).
- **Empirical:** `hermes plugins list` and `hermes plugins validate` run;
  bundled plugins are listed.

**Can a plugin register HTTP endpoints?** Yes, through its own platform
adapter (`register_platform`), which runs its own HTTP server like `api_server`.
A plugin **cannot** attach routes to the existing `api_server` (no
`add_route` extension). The complete plugin-hosted HTTP adapter was **not**
executed end-to-end in this phase (see §5, chosen sidecar).

## 4. Architecture Variants

- **A – official API directly:** rejected. No device scoping, the
  master API key would reside on the device, no pairing/reconnect semantics.
- **B – Hermes plugin:** technically possible and core-free (register_platform).
  Drawback: third-party code runs in the core process (trust, update coupling).
- **C – standalone sidecar (chosen):** unmodified core, its own
  process/lifecycle, clean uninstall/rollback; talks to the official
  `api_server` over loopback and adds pairing/tokens/allowlist.
- **D – upstream contribution:** the best solution in the long term (Mobile-v1 in the
  core), but not available immediately.

**Decision:** PoC as a **sidecar (C)**; the bridge does not invent missing
core functions: mobile features that are not implemented are **omitted** from
the capability object (the client handles them fail-closed as not
supported) instead of simulating them. The target picture remains an upstream contribution
(D); variant B is a defensible alternative when no additional
process is desired.

## 5. Minimal, Working PoC (`bridge/`)

`bridge/` contains the "Orsik Mobile Bridge": a loopback-bound
aiohttp sidecar on top of the **unmodified** official Hermes.

- Reads `API_SERVER_KEY` from a 0600 env file; **never** exposes it to clients.
- Short-lived one-time pairing codes (24 characters, 300 s, single-use, SHA-256).
- Device tokens (form `hd_` + 43 base64url) with a narrow allowlist
  `{chat, runs, approvals}`.
- Public paths correspond exactly to the Mobile-v1 contract (`/health`,
  `/v1/capabilities`, `/v1/devices/pair`, `/v1/devices/self/revoke`,
  `/api/sessions…`, `/v1/runs…`) — the existing `HermesConnectionClient` is
  therefore drop-in; `device_id` is a UUID, the device key has the form
  `hd_` + 43 characters.
- Proxies **only** a closed path set (`core.PROXIED_ROUTES`:
  `/api/sessions…`, `/v1/runs…`); all other paths → `403`.
- Capabilities use the official envelope (`object`, `platform`,
  `runtime.mode` exactly) and omit the unimplemented interactive
  approval contract (the client handles it fail-closed as not
  supported).

Live acceptance test (`bridge/tests/acceptance_live.py`) against the fresh
official Hermes: **31/31 checks green**, including pairing 201 (exact fields,
UUID `device_id`, `hd_` key, permissions), the **complete** chat SSE
lifecycle (`run.started → message.started → assistant.completed →
run.completed → done`, each event explicitly checked), run status, out-of-scope
403 (jobs/cron/memory/skills/toolsets), missing token → 401, self-revoke 200
and afterwards 401 (revoked device). Additionally 29/29 unit/HTTP-layer tests
(`python -m unittest discover -s tests`).

**Honest limit:** the chat *transport* is demonstrated end to end; the
assistant *content* requires a configured provider. The fresh,
unmodified core has no provider (own choice, no model change);
`assistant.completed` therefore carries the official provider notice message.
No stub, no simulation.

## 6. Missing Server Capabilities (Blockers, Not Simulated)

The **official core** is missing:

1. Device pairing/credentials + mobile scope.
2. Interactive session approvals (`X-Hermes-Interactive-Approvals`).
3. Session-run re-attach with `after_seq` replay.
4. Explicit `protocol_version` negotiation in the capability object.

The bridge PoC **implements (1) itself** (pairing, device tokens, allowlist,
Mobile-v1 capability envelope with `protocol_version`) and deliberately omits
(2) and (3) — they do not appear in the capability object and are not
simulated; the client handles them fail-closed as not supported.

## 7. Security Assessment

- Bridge binds only loopback; TLS externally (e.g. Tailscale Serve) — no
  public port, no funnel.
- Master key exclusively server-side and only in the upstream header.
- Codes/tokens only as hashes; narrow, fixed permission allowlist; no
  admin/cron/memory/jobs surface. Bridge-owned errors use a
  closed body (`{"error":{"code":…}}`); upstream responses (including
  upstream error bodies) are passed through unchanged.
- **Device authorization model (single-owner delegation):** The official
  Hermes API has no resource ownership per device (one owner domain for
  sessions/runs). Paired devices therefore act as delegates of the one owner
  across **all** sessions/runs. The route allowlist limits the endpoints, not
  the individual resource; guaranteed per-device isolation is **not**
  provided and **not** pretended. Effective restrictions: pairing
  is admin-gated, tokens are individually revocable, the approval choice is narrowed to
  `once`/`deny`. Real isolation requires a core extension.
- Residual risk: the bridge process is privileged (holds the master key);
  protection through loopback binding, 0600 env, file system permissions and a minimal
  attack surface. A compromised bridge process would have Hermes API rights.

## 8. Installation and Rollback Concept

- Installation from a pinned source/wheel (`uv pip install`), **no**
  `curl | bash`, **no** patching of core files, own venv.
- `bridge/install.sh` creates a dedicated `BRIDGE_HOME` venv and provides
  a `bridge.env` template (loopback, admin token, Hermes env path).
- No public ports; TLS remains mandatory (serve/tunnel).
- Rollback: stop the bridge process and delete `BRIDGE_HOME`; Hermes and its
  models remain untouched.

## 9. Concrete Next Implementation Phase

1. **Upstream (target):** bring Mobile-v1 into the official core
   (`protocol_version`, device credentials, session approval opt-in,
   after-seq replay) — as a testable upstream PR.
2. **Extend the bridge (if upstream is not available soon):** after-seq replay
   and interactive approvals as bridge-owned, scoped endpoints (no
   core patch), including reconnect/approval tests.
3. **Client:** optionally point `HermesConnectionClient` at the bridge URL
   and keep the Mobile-v1 contract (fail-closed remains).
4. **Fresh-install path:** document bridge installation + Tailscale Serve as a
   verified, versioned package chain.

## 10. Mobile-Bridge Security Hardening

### Previously Demonstrable Weaknesses (Root Cause)
1. **Approval escalation:** `POST /v1/runs/{run_id}/approval` was forwarded with an
   unchanged body to the official core; the official
   choices `session`/`always` were therefore usable through the bridge (only
   `once`/`deny` are allowed according to Mobile-v1).
2. **Redirect credential leak:** aiohttp followed upstream redirects; the
   master key in the `Authorization` header could have reached a third-party host.
3. **Query strings unfiltered** passed to allowed endpoints.
4. **Body limit 4 MiB** would have silently broken the client-side supported 5 MiB image function
   (≤ 8 MiB base64 JSON).
5. **No hard token/code limit** (pruning only after exceeding) and no
   rate limits.

### Implemented Fixes
- Approval strictly validated (only `once`/`deny`; `request_id` not empty, ≤ 256,
  control-free; exactly `{choice, request_id}`); invalid requests → closed `400`,
  **no upstream POST**. Defense in depth: only the canonical path is served by the
  dedicated handler; trailing-slash variants (`…/approval/`, `…//`)
  and the generic proxy route reject approval paths (`403`). This
  bypass was found and fixed in the independent review.
- Upstream redirects: `allow_redirects=False`; `3xx` → closed `502
  upstream_redirect` (no location relay, no host change of the master key).
- Query allowlist (`limit`/`offset`/`order`/`include_children` only on GET
  session routes), otherwise `403 query_not_allowed`.
- Body limit `10_000_000` (like the official `api_server`); malformed JSON → `400`;
  oversize → `413` (not 400).
- Rate limits for pairing and admin code (admin only after auth, so that an
  unauthenticated caller does not lock out the operator); hard caps for
  token/code store (`mint`/`issue` refuse beyond the cap → `503`).
- Device authorization model documented as single-owner delegation (§7).

### Test Evidence
- Bridge unit (core + HTTP layer): **57 tests, 0 failures** — including
  approval negative (0 upstream POSTs), trailing-slash bypass, redirect fail-
  closed, query allowlist, rate limit, hard caps, oversize 413, secret non-leak.
- Live acceptance against the fresh official Hermes: **34/34 green** (including
  approval escalation local `400`, `once` reaches the core, `?evil=` → `403`,
  pairing/capabilities/chat SSE/run status/self-revoke unchanged).
- Fresh-install compatibility preserved (unmodified core, `f86276d2`).

### Remaining Residual Risks
- No guaranteed per-device isolation (official API without ownership) — §7.
- Bridge is privileged (holds master key); protection only through loopback/OS rights.
- Token/code stores are volatile (process restart loses them).

### Open Tasks
- Upstream: `protocol_version` + device credentials + **resource ownership**
  (real per-device isolation) + after-seq replay + interactive
  session approval opt-in.
- Bridge alternative: `after_seq` replay and interactive approvals as
  bridge-owned, scoped endpoints (no core patch).
- Persistent, durable device management (encrypted, across restarts).

## 11. Mobile Bridge (2026-10-10)

Branch `p13/full-mobile-bridge` (base `f339343`). Goal: from the hardened
prototype to the complete mobile connection of a fresh, unmodified
official Hermes installation. No core patch, no hermes-server intervention.

### 11.1 Updated Core Analysis (official `f86276d2`)

The official core has evolved; **newly evidenced** by
read-only source analysis and live acceptance:

- `POST /api/sessions/{id}/chat/stream` assigns a `run_id` (`run_<uuid>`),
  registers it in `_run_owners`/`_run_statuses` and stamps **every** event
  with `session_id`/`run_id`/`seq`/`ts` (`_SessionEventQueue`).
- The session chat stream registers an approval notifier
  (`_register_session_stream_approval`, `_run_approval_sessions[run_id]=run_id`)
  and emits `approval.request` for a flagged action. `api_server`
  formally remains "unattended", but is **interactive** as long as a notifier is
  registered (`_presence` discriminator, `approval_context._NOTIFIER_BLIND_APPROVAL_PLATFORMS`
  contains only webhook/msgraph). → Interactive session approvals are present in the core.
- `POST /v1/runs/{run_id}/approval` accepts exactly the
  `run_id` for a session run; choices in the core are otherwise `once/session/always/deny`.
- `GET /v1/runs/{run_id}` returns the persisted, pollable status of a
  session run.
- `GET /v1/runs/{run_id}/events` has a **replay buffer** for `/v1/runs` runs
  (`_RunStream`, `Last-Event-ID`/`last_seq`, `replay.truncated`) — **but not**
  for the session chat stream (no entry in `_run_streams`).

### 11.2 Updated Gap Matrix

| Capability | Official core `f86276d2` | Bridge |
|---|---|---|
| Session CRUD/messages/chat/stream (+`seq`) | yes | proxied |
| Interactive session approvals `approval.request` | **yes** | advertises v1 contract, narrowed to `once`/`deny` |
| `POST /v1/runs/{run_id}/approval` for session run | yes | validated + proxied |
| `GET /v1/runs/{run_id}` status | yes | proxied |
| Session-run re-attach `…/runs/{run_id}/stream?after_seq=N` | **missing** | **bridge-owned implementation** (takeover + replay buffer) |
| Device pairing/credentials/scope | missing | **bridge-owned** |
| `protocol_version` negotiation | missing | **bridge-owned** (capability envelope) |
| Persistent device management | missing | **bridge-owned** (SQLite, 0600) |
| Per-device resource ownership | missing | **not possible** (single-owner delegation) |
| Run discovery (session→active run) | missing | not implemented (no endpoint) |

### 11.3 Implementation (Bridge)

- **Reconnect/run ownership:** The chat stream is taken over server-side; the
  upstream SSE continues independently of the client and is buffered in a
  bounded way per `run_id`/`seq`. `GET …/runs/{run_id}/stream?after_seq=N` replays exclusively
  and follows live; gaps → `409 resume_gap`, unknown → `404`; never invented
  events, never a second chat POST.
- **Interactive approvals:** capability `session_chat_interactive_approvals`
  exactly v1 (`once`/`deny`), pass-through of the `approval.request` events,
  local validation of the response.
- **Persistent devices:** SQLite store (hashes only, TTL, caps, atomic
  single-use redeem, migration, fail-closed) → pairing/revoke survive the
  bridge restart.
- **Installation/operator:** `install.sh` (dedicated venv, `--update`,
  `--systemd`, `--uninstall`), user unit, **local** operator CLI
  (`admin pairing-code/list-devices/revoke-device`), deep link + optional QR.
  No HTTP admin anymore.

### 11.4 Remaining Core Limits (Not Simulated)

1. **Per-device isolation** is missing in the core → single-owner delegation (documented).
2. **Run discovery** (session→active run) is missing → without a confirmed `run_id`
   the outcome remains undetermined.
3. **Assistant content** cannot be verified for real without a configured provider
   (transport fully verified).

### 11.5 Live Evidence

Fresh official core `f86276d2` + bridge: `bridge/tests/acceptance_live.py`
**37/37 green** (health, pairing 201 with exact fields, capabilities with
`device_credentials` + `session_chat_interactive_approvals` exactly,
approval escalation local `400`, `once` reaches the core, query `403`,
out-of-scope `403`, missing token `401`, session create/messages,
complete chat SSE lifecycle including `run.completed`, **re-attach 200 with
replay**, unknown run `404`, self-revoke `200` + afterwards `401`).

## 12. Hermes Compatibility Matrix (2026-10-16)

Each state runs isolated on `build-host` (own Git worktree, own
`HERMES_HOME`, own loopback port, own bridge instance and `state.db`;
the productive gateway remains untouched). Venv per state: `uv sync --frozen
--extra messaging` (without `messaging`, `aiohttp` is missing and `api_server`
then does not start). Bridge: state `or-night-integration`. No model provider
configured (assistant content still not verifiable, cf. 11.4).

| Hermes state | Version (`hermes --version`) | `acceptance_live.py` | Capabilities vs. `f86276d2` |
|---|---|---|---|
| `f86276d2` (tested base) | – | **37/37** | – |
| `v2026.9.24` (latest release tag) | v0.21.5 (2026.9.24) | **37/37** | identical (112 keys) |
| `f974134f` (`main`, 2026-10-16) | v0.22.1+682 | **37/37** | +3 keys, see below |

Capability difference `main`: `features.run_unknown_resolution = true` and
`endpoints.run_unknown_resolution = POST /v1/runs/{run_id}/resolve-unknown`.

Fail-closed evidence (live against `main`): The bridge generates
`/v1/capabilities` itself and does not pass through the new feature (0 matches);
`POST /v1/runs/{id}/resolve-unknown` lies outside `PROXIED_ROUTES` and
ends at the bridge with a valid device token with **403**. Using it
(potentially relevant for the run-discovery limit 11.4/2) requires a
deliberate extension of the bridge allowlist, capability envelope and
client contract; not part of this compatibility matrix.
