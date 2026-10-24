# Orsik Mobile Bridge – Security Threat Model

As of: 2026-10-10.

## 1. Protected Asset

The Hermes master API key indirectly grants access to powerful tools. The
bridge holds it server-side (loopback process) and **never** gives it to clients.
Devices receive only short-lived, individually revocable device tokens with the fixed
permission allowlist `{chat, runs, approvals}`.

## 2. Trust Boundaries

- **Android device**: untrusted; device token only, no master key, no admin.
- **TLS terminator** (Tailscale Serve / reverse proxy): trusted for
  transport, but the bridge does not validate it — it binds only loopback.
- **Bridge process**: privileged (holds master key). Protection through loopback
  binding, 0600 secrets, minimal attack surface, closed route/query allowlist.
- **Official Hermes core**: authoritative for run status and approvals; unchanged.

## 3. Threats & Measures

| Threat | Measure |
|---|---|
| Master key leak to device/log | key only server-side; never in body/response; closed errors; buffered (non-SSE) upstream bodies have the exact master key replaced with `[redacted]` |
| Master key exfiltration through misconfigured `BRIDGE_UPSTREAM` | target is checked at startup: only loopback HTTP **IP** with explicit port (no hostname, no path/query/fragment, no userinfo); otherwise startup aborts without repeating the value |
| Approval escalation (`session`/`always`) | local validation allows only `once`/`deny`; invalid → `400`, **no** upstream POST |
| Redirect credential hop | `allow_redirects=False`; `3xx` → `502`, `Location` discarded |
| SSRF/path traversal | closed route allowlist; segment validation; exact match |
| Query injection | query allowlist per route; otherwise `403` |
| Body overflow | limit `10_000_000`; oversize → `413`; malformed JSON → `400` |
| Brute-force pairing | rate limit; hard code/device caps; single-use atomic |
| Token/code theft at rest | SHA-256 hashes only; DB 0600/0700, sidecars and `-journal` too; process umask `0077`; a failed chmod aborts the start instead of being ignored; never logged |
| Insecure secret file | `BRIDGE_HERMES_ENV` / `BRIDGE_ENV_FILE` must be regular files owned by the bridge user with no group/other bits (`0600`); checked before reading, otherwise startup aborts with the path and the `chmod 600` hint (never the content) |
| Pairing code in process list | the `orsik://pair` payload goes to `qrencode` on stdin, never in argv |
| Unauthenticated request body | `/health` rejects any body (`Content-Length > 0` or `Transfer-Encoding`) with `400 body_not_allowed`; it is never read or forwarded |
| Persistent credential compromised | individual revocation durable across restarts; an open run stream re-checks its token every 15 s and ends with `: bridge_revoked` once the device is revoked (from the phone or `admin revoke-device`) |
| Oversized history pages / tool output in history | `GET /api/sessions/{id}/messages` pages (≤ 64 MiB upstream) are slimmed to what the app reads: inline image data removed (the image parts stay without data so the app can count them), tool messages without `content`, no `tool_calls` and no reasoning fields. Other buffered bodies ≤ 16 MiB |
| Tool output/commands on the phone | the relay drops fields the app never reads: the turn transcript (`messages`), `usage` and `runtime` on `run.*`, `preview`/`args` on `tool.*`, the reasoning text (`delta`) on `tool.progress` and the text of `assistant.commentary`; this also keeps the terminal frame within the relay bound |
| Restoring an old store state | backup/restore operator-side only (file system permission); a restore restores the authorization state at the backup time — devices revoked afterwards are valid again (check `admin list-devices`, revoke affected devices again); pre-migration backup automatic, no down-migrate |
| Store failure | fail-closed (`503 store_unavailable`), never fail-open |
| Blocking store access | store calls run serially in a dedicated DB thread and never block the aiohttp event loop |
| Public admin surface | no HTTP admin; codes only via local operator CLI |
| Run loss/duplicate on disconnect | server-side stream takeover + `after_seq` replay; no second POST |
| Fabricated run completion | never invented events; not reconstructible → `409`/`404` |
| Memory exhaustion through upstream/runs | frame ≤ 1 MiB **after** redaction (otherwise `502 upstream_frame_too_large` or abort comment, no `done`; image echo see §3.1); error body ≤ 64 KiB; backlog per run 1000 frames/4 MiB; subscriber queue 256/2 MiB, max. 4 per run (drop instead of block); max. 16 active runs (`503 bridge_busy` before the upstream POST); terminal runs ≤ 64 MiB |
| Reading files through the browser | off unless `BRIDGE_FILES=1` with `BRIDGE_FILES_ROOT`; a root that contains the home folder, the bridge home, the state DB, `bridge.env`, the Hermes home or the Hermes env file (also behind a symlink), or lies inside the bridge or Hermes home, is refused at start; every path resolved with symlinks and checked against the root (`403 path_not_allowed`), then opened component by component with `O_NOFOLLOW` (no swap to a symlink in between) and `O_NONBLOCK`; only regular files ≤ 8 MiB, closed content types with `nosniff`; listing ≤ 2000 entries, names that are not UTF-8 skipped; no write route; projects: git with an empty tree as attribute source (no `.gitattributes` filter, textconv or other driver runs), fsmonitor and hooks off, no system/global config, pinned work tree, submodules ignored, its whole process group killed after 2 s or 4 KiB of output, 10 s per scan; only real `.git` folders inside the root; file reads audited with control characters escaped. **Residual risk:** whatever the operator puts below the root is readable by every paired device; a hard link inside the root to a file elsewhere is read like any file (only someone who can write into the root can create one) |
| Shell access through the terminal | off unless `BRIDGE_TERMINAL=1`; not advertised when off (`403 endpoint_out_of_scope`); device token checked before anything starts and every 15 s while open (revoked → close `4401`); a browser `Origin` header is refused (cross-site WebSocket hijacking); no query; ≤ 4 terminals, 64 KiB per message, a slow client throttles the PTY instead of buffering; fixed argv without a shell, minimal child environment (no Hermes key, no `BRIDGE_*`); `NoNewPrivileges` keeps `sudo` out; audited without content. The app adds the device credential on every visit, never retries, and renders in a WebView that loads only its own assets. **Residual risk:** the shell has every right of the bridge user (it can read the Hermes env file and the bridge's own `/proc/<pid>/environ`) and bypasses Hermes approvals by design; every paired device shares one tmux session, and revoking a device ends its connection, not the session or what runs in it; the device permissions (`chat`, `runs`, `approvals`) do not scope the terminal, only `BRIDGE_TERMINAL` does |
| Public API port | bind only a literal loopback IP (`127.0.0.1` or `::1`); `localhost` and any non-loopback value are refused; TLS external; no funnel |
| TLS bypass | bridge does not terminate TLS; app enforces HTTPS + system trust |

## 3.1 Image Echo in `run.started`

In the first `run.started` frame, the core echoes the complete request including
`user_message` (among others `content[1].image_url.url` as
`data:image/...;base64,...`). A raw image up to 5 MiB (`MAX_IMAGE_BYTES` of the app)
thus results in a frame well over 1 MiB, although the request is permissible
(base64 body up to 8 MiB, bridge body limit 10 MB). Without a countermeasure,
the bridge responded to every image turn with `502 upstream_frame_too_large`.

The session chat pump (`_pump_chat_stream`) therefore decodes **only there** with
`_MAX_ECHO_FRAME_BYTES = _MAX_BODY_BYTES + 64 KiB` (the core echoes at most the
request body plus envelope). For every `run.started` frame whose `data` JSON is
an object with `user_message`, the bridge removes exactly this key; all
other fields (`seq`, `run_id`, …), existing `id:`/`event:` lines and their
order are preserved. This is the **only** place where the bridge
re-serializes upstream content instead of passing it through byte-identically.

**After** redaction, `sse.MAX_FRAME_BYTES` applies unchanged to every frame
(1 MiB, measured on `len(frame.raw)`): a larger frame leads to the same
behavior as before (`502 upstream_frame_too_large` before the first frame, otherwise
abort comment `: bridge_upstream_frame_too_large`, never an invented `done`).
`sse.MAX_FRAME_BYTES` and all buffer/queue limits in `runhub.py` remain
unchanged. The core's raw `GET /v1/runs/{run_id}/events` is not proxied: runs are
followed only through the filtered session stream.

## 4. Single-Owner Delegation (Residual Risk)

The official Hermes API has **no** resource ownership per device. Every
paired device acts as a delegate of the one owner across **all**
sessions/runs. The route allowlist limits endpoints, **not** individual
resources. Guaranteed per-device isolation is not provided and not
pretended; it requires a Hermes core extension.

Effective restrictions: pairing is operator-gated (local CLI), every token
is durably individually revocable, the approval choice is narrowed to
`once`/`deny`.

## 4.1 Revalidation Against Real Positive Approvals (2026-10-16)

Live on `build-host`: official core `f86276d2` with a real provider
(DeepSeek, `deepseek-flash`, only in the isolated test home), bridge
`or-night-integration`, real tool request (`rm -rf` on a previously created
marker directory under `/tmp`).

| Device response | Bridge | Core / effect |
|---|---|---|
| `once` | `200`, forwarded | exactly one `approval.request`, `tool.completed`, marker deleted, run `completed` |
| `deny` | `200`, forwarded | `tool.failed`, marker unchanged, run `completed` |
| `always` / `session` | **`400` local**, no upstream POST | only the following `deny` resolves, marker unchanged |

In `approval.request`, the core offers `choices = [once, session, always, deny]`;
the narrowing to `once`/`deny` is performed exclusively by the bridge — locally
on the response **and** in the stream: The bridge converts every `approval.request`
to the Mobile-v1 form (`session_id`, `run_id`, `seq`, `ts`, `request_id`,
fixed `description`, `choices = [once, deny]`) and discards all other fields
(`command`, `pattern_keys`, `allow_session`, `allow_permanent`, …). Previously
(until 2026-10-16) it passed the core frame through raw; the app rejects it strictly
as a protocol error, so a real approval would have thrown the turn on the device
into UNCERTAIN. If a v1 field is missing, the frame remains unchanged (the app
rejects it, the bridge invents nothing). Consequence for the product: The device
shows only the fixed notice text, **not** the command (deliberate product decision).

**Finding: core approval mode.** With the core default `approvals.mode:
smart`, a helper LLM evaluates dangerous commands and approves those classified as
harmless **without** `approval.request`: live, `rm -rf /tmp/<marker>` was executed
without asking. The mobile approval surface sees only what the core
escalates; the bridge cannot enforce this. **Operational requirement:** The
Hermes home behind the bridge sets `approvals.mode: manual` (see
`bridge-installation.md`). With `manual`, every execution occurred only after
`approval.request` (table above).

**Auditability.** Every state-changing device request creates an
audit line (logger `orsik_mobile_bridge.audit`):
`audit action=<chat|approval|stop|steer|session_create|session_archive|session_delete|resolve_unknown|pair|revoke>
device=<device_id|-> [run=/session=<id>] [choice=<once|deny|rejected>]
[status=<http>]`. Rejected choices are recorded as
`rejected` (raw value = client input, never logged); tokens,
master key and message content never appear. Pairing failures and
unauthenticated revoke attempts use `device=-`. The line is created as soon as
the upstream status (or the local rejection) is determined, i.e. before the
response to the device.

**Chat management.** `PATCH /api/sessions/{id}` reaches Hermes only as exactly
`{"archived": <bool>}` (title, pin, hidden, unread and `end_reason` are refused
locally with `400`); `DELETE /api/sessions/{id}` only without a body. Deleting is
irreversible on the server; the app asks for confirmation first. Like
approvals, any paired device can archive or delete any chat of the owner (§4).

§4 continues to apply unchanged: Every paired device can send approvals/stop/steer for
**any** run of the owner (no per-device isolation); the audit makes
this attributable but does not prevent it. **Proven live (2026-10-16):** Device B
reads the run status and session history of device A (`200`) and answers its
`approval.request` with `once` (`200`, command executed); the audit records
`chat` under device A and `approval … choice=once` under device B.

## 5. Non-Goals / Limits

- No protection against a compromised bridge process (it would have
  Hermes API rights) — mitigated by loopback, OS rights, minimal surface.
- No protection against a compromised operator account (CLI + store access).
- The bridge proves nothing about the trustworthiness of the TLS terminator;
  the **client** aborts on an invalid certificate.

## 6. Secrets Handling

- `API_SERVER_KEY` read only from a `0600` regular env file owned by the bridge
  user; `BRIDGE_HERMES_ENV` and `BRIDGE_ENV_FILE` are checked before reading and
  an insecure file aborts the start.
- Pairing codes/tokens: one-time output, hash at rest only, never in logs,
  error bodies, URLs (except the `orsik://pair` link) or reports. The deep link
  is handed to `qrencode` via stdin, not argv.
- No credentials in Git; `bridge.env`, `state.db` are excluded.
