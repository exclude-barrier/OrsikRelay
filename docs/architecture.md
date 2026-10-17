# Architecture (current state)

This document describes the **actually implemented** architecture of
Orsik Relay and explicitly marks the places where the
original (internal) overall plan (target) deviates from the code (actual). The
code is authoritative; deviations are partly deliberate, partly open.

Substantially revised for `v0.1.0-alpha.6`. Later components – Mobile Bridge,
run lease after process death, release pipeline – are described in
`docs/bridge-security.md`, `docs/mobile-wire-contract.md`, `docs/bridge-e2e.md`
and `docs/release.md`.

## 1. Module and build structure

- **A single Gradle module** `app` (namespace `io.github.excludebarrier.orsikrelay`).
  The original plan envisioned multi-module (`core/`, `data/`, `feature/`) and
  explicitly allowed starting as a single `app` module. Package boundaries
  remain the layer boundaries.
- No DI framework. The production graph is built by hand in
  `di/AppDependencies.kt` and cached process-wide in `AppDependenciesHolder`.
  `HermesNavHost` resolves `AppDependenciesHolder.get(...)` as needed;
  test injections bypass the cache and build an isolated graph.
- Wrapper `9.8.1`, Kotlin `2.4.21`, AGP `9.3.1`, Compose BOM `2025.12.00`,
  `minSdk 26`, `compileSdk 37`, `targetSdk 36`, JDK 17.

## 2. Layers

```
Compose UI  →  ViewModel  →  Domain-Seams (Use Cases)  →  Data-Repositories  →  REST/SSE + Platform/Security
```

- `ui/` knows no network DTOs; `ChatViewModel` holds an immutable
  `ChatUiState`.
- `domain/` contains closed result/error types and seams
  (`RunStopRequester`, `RunSteerRequester`, `RunApprovalRequester`, …).
- `data/` implements the seams against `network/` and the stores.
- `network/` contains one client per endpoint plus `SseFrameParser` and
  `ChatStreamEventPipeline`.
- `security/credential/` (Keystore) and `platform/` (attachment, notification,
  foreground service) are the Android adapters.

## 3. Deviations target → actual

| Plan (target) | Actual | Assessment |
|---|---|---|
| Multi-module `core/`/`data/`/`feature/` | One `app` module | deliberate (the plan allowed it) |
| Room for profiles/cache/drafts | SharedPreferences with versioned keys | deliberate: Room is not a dependency; the repo pattern is SharedPreferences + versioned key |
| DataStore for drafts | SharedPreferences (`chat.draft.v1.*`) | deliberate, see above |
| `docs/security-threat-model.md`, `docs/testing.md`, `docs/release-checklist.md` | not present | open |
| Network Security Config (file) | `android:usesCleartextTraffic="false"` + HTTPS-only in the client | deliberate/minimal: cleartext is blocked anyway |
| Certificate pinning | not implemented | open (plan: “optional after a stable strategy”) |
| Release signing / AAB / CI | signing configuration via environment variables present (`docs/release.md`); CI present (`.github/workflows/android.yml`: unit tests, lint, `assembleDebug`, instrumented API 36) | signing/AAB present; productive key and pipeline open |
| `HermesApplication.kt` | not present (application class unnecessary) | deliberate |

## 4. Known, deliberately accepted limits

- **Process-death recovery only as a run lease.** The live run binding
  (`AppDependenciesHolder`, `RunSessionController.ActiveRun`) survives
  activity/ViewModel recreation within the same process. A
  process kill terminates the SSE socket but does **not** prove a terminal
  run end on the server (a mobile-v1 run with the
  `X-Hermes-Interactive-Approvals` opt-in continues independently of the socket,
  see §5). Therefore the controller persists exactly one minimal lease for a
  **server-confirmed** run (`profileId`, `sessionId`,
  `runId`; `SharedPreferencesRunLeaseStore`, no text, no tool data,
  no credentials). After a restart the slot comes back as `UNCERTAIN`:
  no foreground service, no re-attach, **no second POST**;
  sending remains blocked until the existing manual reconciliation
  (`GET /v1/runs/{run_id}`) proves a terminal end. As long as the lease
  is still being read, `startTurn` reports a conflict. A lease of a
  deleted profile is discarded (cannot be reconciled without a credential); an
  unreadable store counts as “no lease”. Limits: a run whose
  `RunStarted` had not yet arrived before process death has no `runId` and
  is not restored; there is no automatic `after_seq` re-attach after
  process death.
- **Test seam in production code.** `MainActivity.sessionPageSourceForTests`
  (`@VisibleForTesting`, default `null`) skips the
  session list in `androidTest`. In production it is always `null`; the seam is deliberate and
  documented as acceptable, because a real activity recreation test would otherwise need a
  real network path.
- **Server contract not public (mobile-v1 extensions).** The
  device- and reconnect-specific mobile-v1 extensions
  (`device_credentials`, `protocol_version` negotiation including the capability
  `session_chat_interactive_approvals`, session-run re-attach with `after_seq`)
  are **not** contained in the public Hermes version. For a fresh,
  unmodified official Hermes installation, the **Orsik Mobile
  Bridge** provides them as a sidecar (`docs/bridge-installation.md`, `docs/bridge-e2e.md`); the
  core remains unpatched. The underlying core functions (session chat/
  stream, interactive session approvals `approval.request`) are present and
  are passed through or narrowed to `once`/`deny`; whether the core processes multimodal
  content parts is not verified on the bridge path. The
  client opt-in header `X-Hermes-Interactive-Approvals` of the private
  mobile-v1 build is not needed on the bridge path (the core is
  interactive as soon as the stream notifier is registered). Without the bridge or
  an equivalent server build, the app is not usable. The
  publication of the server contract in the official core (upstream) is
  a prerequisite for a generally usable release without the bridge (see
  `README.md`).

## 5. Run lifecycle invariants

Orsik has exactly **one** app-wide run slot. Its release is based
exclusively on **authoritative** server information; a client-side
transport, timeout, TLS, or protocol error must never count as a
server terminal.

Classification of a failed turn (`RunTermination`, closed):

- **`AuthoritativeTerminal`** – the server has demonstrably
  confirmed the run end: `run.completed` (success) or the server's own `error` frame
  (`ChatStreamError.ServerError`, always followed by `done`; the server sets
  a terminal status in the process). Only here is the slot released.
- **`TransportOutcomeUnknown`** – confirmed run + local
  transport/protocol error, **or** unconfirmed start after the initial
  POST (`Network`/`Timeout`/`Tls`/`IncompatibleApi`/5xx `ServerUnavailable`):
  The POST may already have been accepted server-side. Ownership is
  retained (`ActiveRun.Status.UNCERTAIN`), **no** second POST is
  sent, no implicit server stop is triggered, and no automatic
  approval action is performed. The FGS is stopped (no active transport),
  logical ownership remains separate from it.
- **`LocalRequestRejected`** – the start was demonstrably rejected before
  a run came into existence (local validation or a 3xx/4xx HTTP response; **no
  5xx**, which a gateway may deliver after admission has already occurred).
  The slot is released; a new, deliberate turn is possible.

Further invariants:

- **`RunStopResult.Accepted` is not terminal.** Only
  `AlreadyTerminal` triggers the authoritative release (`cancelTurn`); the
  stream continues until its own terminal.
- **Local contradictions (duplicate approval message) do not release the slot.**
  `RunSessionController.markTurnUncertain` discards the dead local
  stream but retains ownership; `once`/`deny` is never sent automatically.
- **Recovery is explicit and read-only.** A confirmed,
  uncertain run can be reconciled manually via `GET /v1/runs/{run_id}`
  (`RunStatusRequester`): a terminal server status releases the slot
  authoritatively and reloads the history; `running`/`queued`/
  `waiting_for_approval`/`stopping` or a read error change nothing. Without a
  confirmed run ID there is **no** safe reconciliation; the state is
  honestly shown as undetermined (see §6, server contract gap).
- **No automatic release through `cancelTurn` semantics alone.** The
  collector `finally` no longer resets the slot; only the
  terminal handlers, `cancelTurn` (authoritative), `markTurnUncertain`,
  `confirmTerminalForUncertainRun`, and `close` change it.

## 6. Security invariants (verified in code)

- Credentials only in the Android Keystore (`AndroidKeystoreCredentialStore`,
  AES-256-GCM, key per profile, versioned payload).
- `allowBackup=false`, no cleartext, no TLS exceptions, no redirects,
  no retry of writing requests.
- Approvals exclusively `once`/`deny`, never automatically.
- Error surfaces are closed and contain no body/URL/key echo.
- Tool activity is redacted (no commands/paths/URLs/tool outputs).
