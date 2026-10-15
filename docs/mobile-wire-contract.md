# Hermes Mobile Wire Contract (P0)

**Status:** Stabilisierter, versionierter Client-Vertrag für Orsik Relay.
**Client:** `HermesConnectionClient` (Capability-Negotiation), `HermesDevicePairClient`,
`HermesDeviceSelfRevokeClient`, `HermesRunApprovalClient`, `HermesSessionStreamClient`.
**Ziel:** Orsik Relay hängt nicht dauerhaft von einem privaten, inkompatiblen
Hermes-Sonderstand ab, sondern spricht einen explizit versionierten, fail-closed
verhandelbaren Mobile-Vertrag.

---

## 1. Protocol-Versionierung (explizit)

- **Eine** Mobile-Protocol-Version versioniert alle weichen Mobile-Fähigkeiten
  (session-chat interactive approvals **und** device credentials).
- Client spricht **Mobile Protocol Version 1**:
  `HermesConnectionClient.MOBILE_PROTOCOL_VERSION = 1`.
- Verhandelbar ist ausschließlich die geschlossene Menge
  `SUPPORTED_MOBILE_PROTOCOL_VERSIONS = { 1 }`.
- **Fail-closed-Regel** (zentral in `isSupportedMobileProtocolVersion`):
  Ein Vertrag wird nur akzeptiert, wenn `protocol_version`
  ein **Integer** aus `SUPPORTED_MOBILE_PROTOCOL_VERSIONS` ist.
  - fehlt (`null`) → **closed** (Legacy, kann nicht verhandelt werden)
  - nicht-Integer (String `"1"`, Float `1.0`, Array) → **closed**
  - Integer außerhalb der Menge (`0`, `2`, `3`, …) → **closed**
- Implizite Versionsannahmen: **keine.** Die Version wird nicht aus der
  Dokument-Form, der `/health`-`version`-String oder Endpunkt-Anzahl
  abgeleitet, sondern ausschließlich aus dem expliziten Feld.

> `/health.version` (z. B. `0.20-test`) ist ein **freier, nicht-versionsbindender**
> String. Er wird nur zur Anzeige gespeichert und steuert **keine**
> Feature-Freischaltung.

## 2. Vertragsschichten

### 2.1 `GET /health` (hart, fail-closed → `INCOMPATIBLE_API`)
```json
{ "status": "ok", "platform": "hermes-agent", "version": "<non-blank string>" }
```
- `status` exakt `ok`, `platform` exakt `hermes-agent`, `version` nicht-leer.
- Jede Abweichung → Probe schlägt fehl (`INCOMPATIBLE_API`).

### 2.2 `GET /v1/capabilities` (hart + weich)

**Harter Kern (fail-closed → `INCOMPATIBLE_API`):**
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
- Alle Felder sind **Pflicht** und werden strikt auf exakten Wert geprüft.
- Die drei Kern-Endpoints werden auf exakte `method`+`path` geprüft.

**Weiche Mobile-Fähigkeiten (fail-closed → `false`, Verbindung bleibt nutzbar):**

1. `session_chat_interactive_approvals`
   - Gate: `features.run_approval_response == true`.
   - `features.session_chat_interactive_approvals`:
     exakt `{ enabled:true, protocol_version:1, event:"approval.request",
     choices:["once","deny"], response_endpoint:"run_approval" }`.
     `choices` exakt zwei Einträge, in dieser Reihenfolge.
   - `endpoints.run_approval`: `{ method:"POST", path:"/v1/runs/{run_id}/approval" }`.
   - Ergebnis `Success.sessionChatInteractiveApprovals`; persistiert pro Profil
     (`SharedPreferencesRunApprovalCapabilityStore`); bei `true` sendet der
     Stream-Client das Header-Flag `X-Hermes-Interactive-Approvals: 1`.

2. `device_credentials` (Protocol v1)
   - `features.device_credentials`:
     exakt `{ enabled:true, protocol_version:1,
     pair_endpoint:"device_pair", self_revoke_endpoint:"device_self_revoke" }`.
   - `endpoints.device_pair`: `{ method:"POST", path:"/v1/devices/pair" }`.
   - `endpoints.device_self_revoke`: `{ method:"POST", "path": "/v1/devices/self/revoke" }`.
   - Ergebnis `Success.deviceCredentialsSupported`.

> **Feld-Striktheit:** Die weichen Vertrag-Objekte werden auf **exakt die
> erwartete Feldmenge** geprüft (fehlendes **oder** zusätzliches Feld → closed).
> Das ist bewusst konservativ: der Client aktiviert eine Mobile-Fähigkeit nur,
> wenn der Server exakt die v1-Form bewirbt. Siehe §4 „Upgrade-Regeln“.

### 2.3 `POST /v1/devices/pair` (unauthenticated)
- Body exakt `{ "code": "<24-Zeichen-Code>" }`, `Content-Type: application/json`,
  **kein** `Authorization`.
- Code wird lokal normalisiert (trim, uppercase, `-`/Leerzeichen/`_` entfernt)
  und muss exakt 24 Zeichen aus dem Alphabet (A–Z ohne I/L/O/U + 1–9) sein;
  sonst lokal abgelehnt (kein Request).
- Erfolg 201, exakt:
  ```jsonc
  { "object": "hermes.device.credential",
    "device_id": "<uuid>",
    "device_key": "hd_<43 base64url>",
    "profile": "<string>", "label": "<string>",
    "permissions": ["approvals","chat","runs"],   // exakt diese Menge, jede Reihenfolge
    "issued_at": <finite number> }
  ```
- **Keine Ausweitung:** `permissions` muss exakt `{ approvals, chat, runs }`
  sein; jedes zusätzliche Recht (z. B. `admin`, `jobs`, `memory`, `cron`) →
  `IncompatibleApi` (closed). Mobile-Credentials reichen nie über
  `chat`/`runs`/`approvals` hinaus.
- Fehlercodes 400/409/429/500 → geschlossene Reason; jeder andere Code/
  -Zustand → closed.

### 2.4 `POST /v1/devices/self/revoke` (Bearer Device-Key)
- Body exakt 0 Bytes, kein `Content-Type`.
- Erfolg 200, exakt `{ "revoked": true|false, "device_id": "<uuid>" }`
  (`revoked=true` frisch, `revoked=false` bereits widerrufen).
- 401 → closed (unterscheidet nicht API-Key / unbekannt / widerrufen).

### 2.5 `POST /v1/runs/{run_id}/approval` (Bearer)
- Body `{ "choice": "once"|"deny", "request_id": "<id>" }`.
- **Entscheidung ausschließlich `once` / `deny`** (`RunApprovalChoice`).
  `session`/`always` sind im Client nicht darstellbar.
- Erfolg 200, exakt:
  `{ "object":"hermes.run.approval_response", "run_id":<requested>,
  "choice":<sent>, "resolved":<n>=1> }`.
- 409 (`approval_not_pending`/`approval_not_active`) → `NoPending`, nie
  neu abgesendet. **Approvals werden niemals automatisch bestätigt.**

## 3. Capability-Negotiation & Fail-Closed

| Situation | Ergebnis |
|---|---|
| harter Kern inkorrekt/fehlend | Probe `Failure(INCOMPATIBLE_API)` |
| weicher Vertrag: fehlt / teilweise / malformed | Feature `false`, Verbindung `Success` |
| `protocol_version` fehlt / nicht-Integer / außerhalb `{1}` | Feature `false` |
| `permissions` ≠ `{approvals,chat,runs}` | `pair` → `IncompatibleApi` |
| nicht-JSON / oversized / falsches `object`/`platform` | Probe `Failure(INCOMPATIBLE_API)` |
| 401/403 | `INVALID_CREDENTIALS` |
| 429 | `RATE_LIMITED` (nie retry) |
| Redirect / TLS-Fehler / Timeout / Netzwerk | geschlossene Reason, nie gefolgt |

Invarianten (invariante Sicherheitsgrenzen, nicht verhandelbar):
- HTTPS-only, TLS aktiv, kein Klartext, keine Redirects, keine
  Verbindungs-/Write-Retries.
- Kein Zugriff auf Admin, Jobs, Memory, Cron oder andere privilegierte APIs.
- Keine Secrets oder sensitiven Tool-Payloads in Mobile-Responses; Fehler
  enthalten keine Body-/URL-/Key-/Server-Details.
- Approvals nie automatisch.

## 4. Upgrade- / Kompatibilitätsregeln

- **Rückwärtskompatibel:** Ein Server, der exakt den v1-Vertrag bewirbt, wird
  akzeptiert. Der Client hängt nicht an einem privaten Sonderstand, sondern an
  der **dokumentierten v1-Form**.
- **Kein implizites Weiterschalten:** Ein Server, der die v1-Form ändert
  (Feld ergänzt/umbenennt) oder eine andere `protocol_version` bewirbt, wird
  **fail-closed** abgelehnt. Es gibt keinen stillen Fallback.
- **Zukunftsversion (v2):** Um v2 zu unterstützen, (a) `SUPPORTED_MOBILE_PROTOCOL_VERSIONS`
  auf `{ 1, 2 }` erweitern **und** (b) ein v2-Feldschema ergänzen. Die v1-Form
  wird dabei **nicht** stillerweitert. Bis dahin bleibt v2 closed.
- **Keine Desktop-/CLI-Regressions:** Die Änderungen liegen ausschließlich im
  Android-Client; die Hermes-Server-Konfiguration (Loopback/LAN-VPN,
  `127.0.0.1:8642`) und die Desktop-/CLI-Nutzung bleiben unberührt.

## 5. Gap-Analyse (Stand dieser Session)

> **Quellen-Basis:** dokumentierter Live-Status (`docs/api-capability-matrix.md`,
> auf dem Hermes-Server am 30.08.2026 verifiziert) + Client-Code. Die Hermes-
> Quellcode-Basis selbst ist **nicht** lokal und von diesem Rechner nicht
> erreichbar (Hermes-Server, `127.0.0.1:8642`); eine frische Gegenlektüre des
> Hermes-Quellcodes erfolgte in dieser Session **nicht**.

1. **Was Orsik Relay erwartet:** exakt der v1-Vertrag in §2 (harter Kern +
   zwei weiche Fähigkeiten + pair/revoke/approval-Endpoints). Fail-closed.
2. **Was Hermes laut dokumentiertem Live-Status anbietet:** Chat/Streaming,
   Runs, Approval, Sessions, Skills, Toolsets, Modelle; **nicht** Admin-Write,
   Jobs-Admin, Memory-Write, Audio/Realtime, CORS. `jobs_admin=false`.
3. **Private Hermes-Änderungen, die momentan benötigt werden:** Die
   Mobile-Endpoints (`/v1/devices/pair`, `/v1/devices/self/revoke`,
   `session_chat_interactive_approvals`, `X-Hermes-Interactive-Approvals`) sind
   Teil des privaten Hermes-Server-Stands. Sie sind im Client-Vertrag jetzt
   explizit versioniert, sodass sie **1:1 in einen offiziellen, versionierten
   Hermes-Mobile-Vertrag überführbar** sind, ohne dass der Client an den
   privaten Stand gekoppelt bleibt.
4. **Was für einen stabilen, versionierten, upstream-fähigen Vertrag nötig ist:**
   - (erledigt) explizite `protocol_version`-Negotiation im Client;
   - (erledigt) dokumentierter Wire Contract + Upgrade-Regeln (dieses Dokument);
   - (offen, Server-seitig) Hermes soll `protocol_version` als stabile,
     dokumentierte Größe ausliefern und bei Formänderungen bumpen;
   - (offen, Server-seitig) ein optionales top-level `protocol_version` in
     `/v1/capabilities`, das die Mobile-Protocol-Version unabhängig von den
     per-Feature-Blöcken kennzeichnet (Client-optional; kein harten
     Requirement, um Kompatibilität zum aktuellen Stand zu halten).

## 6. Verifikation (diese Session)

- `./gradlew test` — bestehende + neue Compatibility-Tests.
- Neue Tests: `MobileProtocolCompatibilityTest` (v1 offen; 0/2/3/100/fehlend/
  nicht-Integer closed; pro-Fähigkeit-Independenz; malformed → `INCOMPATIBLE_API`).
- Unverändert: `HermesConnectionClientTest`, `DeviceCredentialCapabilityTest`
  (exakt-Feld-Striktheit bleibt bestehen → alle bisherigen Tests grün).

## 7. Pairing-Deep-Link / Bootstrap-Contract (Zero-Config)

**Status:** Client-seitiges Bootstrap-Contract für das Orsik-Relay-Onboarding.
Der Deep-Link ist der **einzige** Input, den eine frische App braucht, um
Server-Base-URL + Pairing-Code zu erhalten — ohne manuelles Base-URL- oder
API-Key-Eingeben (Product Gate).

### 7.1 Format (exakt)

```
orsik://pair?server=<urlencoded-HTTPS-BaseURL>&code=<24-Char-Code>
```

- `scheme` exakt `orsik` (Client case-insensitive), `host` exakt `pair`.
- Query: **exakt zwei** Parameter, beide Pflicht: `server` + `code`.
- `server`: percent-encodierte (UTF-8) HTTPS-Base-URL (`:`→`%3A`,
  `/`→`%2F`); der Client dekodiert robust (bereits-plain-Werte akzeptiert).
- `code`: der rohe 24-Char-Code verbatim (Großbuchstaben/Ziffern, keine
  Sonderzeichen → Percent-Encoding wirkt hier als no-op). **Keine**
  Anzeige-Dashes im Link (die gibt es nur im CLI-Output).

### 7.2 Fail-closed-Parser (Client: `PairingDeepLink.parse`)

Liefert `null`, wirft nie (auch bei malformedem Percent-Encoding).

| Regel | Ergebnis |
|---|---|
| blank / falsches `scheme` oder `host` / fehlende Query | `null` |
| fehlendes oder leeres `server` bzw. `code` | `null` |
| `#`-Fragment | `null` |
| `server` nicht absolute HTTPS oder mit `user:pass@` | `null` |
| `code` verletzt den `DevicePairCode`-Contract (≠24 / ∉31-Alphabet) | `null` |
| malformedes Percent-Encoding (z. B. `%ZZ`) | `null` (kein Exception) |

### 7.3 Code-Regeln

- Länge **exakt 24**, Alphabet **exakt 31** Zeichen: `A–Z` ohne `I/L/O/U`
  + `1–9`.
- Anzeige (CLI): 6 Gruppen à 4 Zeichen, durch `-` getrennt (29 Zeichen).
- Client-Normalisierung: trim → uppercase → `-`/`_`/Whitespace entfernen →
  exakt-24- + Alphabet-Check; Abweichung wird lokal abgelehnt (kein Request).
- **TTL 300 s, Single-Use, SHA-256-Hash at Rest.** Nach Redeem oder Ablauf
  unbrauchbar.
- **Device-Key (`hd_`) nie im Link** — erst nach erfolgreichem Redeem.

### 7.4 Server-URL-Regeln

- Absolute HTTPS-URL, Host erforderlich, **keine** User-Info, kein Klartext.
- Fail-closed: `http://`, relative/schemeless, `user:pass@host` → abgelehnt.
- Server-seitige Quelle: `API_SERVER_PUBLIC_BASE_URL` (`.env`/Environment).

### 7.5 Zero-Config-Flow (Client)

1. `hermes devices pair --label X` erzeugt den 24-Char-Code; mit gesetztem
   `API_SERVER_PUBLIC_BASE_URL` zusätzlich Deep-Link + ASCII-QR.
2. App öffnet `orsik://pair?…` (Tap oder QR-Scan; `BROWSABLE`-intent-filter).
3. `MainActivity` (`onCreate`/`onNewIntent`) parst den Link fail-closed →
   `PairingBootstrap`.
4. Onboarding pre-füllt Profilname (Host-Label), Base-URL + Pairing-Code.
5. Nutzer tippt **Connect** → `DevicePairingCoordinator.connect` (re-validiert
   URL + Code) → `POST /v1/devices/pair`.
6. `201` → Device-Key im Keystore, Verbindung aktiv.
- **Kein Auto-Connect:** das Pairing erfordert die explizite Connect-Zustimmung
  des Nutzers (Pre-Fill ja, Auto-Submit nein).

### 7.6 Protocol-Version

Pairing gehört zu **Mobile Protocol v1** (Feature `device_credentials`,
`protocol_version=1`, §2.2). Der Deep-Link selbst ist versionsfrei (nur
Client-Bootstrap); die Vertragssicherheit kommt aus den Pair-/Revoke-Endpoints
(§2.3/§2.4) und dem Code-Contract (§7.3).

### 7.7 Server-seitige Generierung (Private Extension)
Die **kanonische** Mobile-v1-Quelle (`hermes devices pair`) gibt **nur** den
24-Char-Code aus. Die Deep-Link- + ASCII-QR-Generierung
(`_build_bootstrap_link` / `_render_qr_ascii` / `_public_base_url`) ist eine
**private Server-Erweiterung** (dev-laptop-Teststand), die
`API_SERVER_PUBLIC_BASE_URL` + Code zum obigen Link bündelt. Sie ist **nicht**
Teil des kanonischen Mobile-v1-Commits und wird nicht als stehender
Vertrag vorausgesetzt — der Client-Contract (§7.1–§7.6) bleibt davon unabhängig.

Der **Orsik Mobile Bridge**-Sidecar (P13) implementiert denselben Pairing-Vertrag
für eine frische, unveränderte offizielle Hermes-Installation; dort erzeugt die
lokale Operator-CLI (`orsik-mobile-bridge admin pairing-code`) Code, Deep-Link und
optional einen QR-Code (`docs/bridge-installation.md`).

## 8. Run-Autorität & Recovery (P7)

**Status:** Read-only gegen den lokal vorhandenen Mobile-v1-Quellstand geprüft
(`/home/user/hermes-mobile-v1-src/gateway/platforms/api_server.py`,
`api_server_runs.py`, `api_server_run_idempotency.py`). P7 stützt die Freigabe
der Single-Slot-Run-Ownership auf diese Aussagen. Kein E2E-Lauf gegen einen
echten Server (nicht verifiziert, s. unten).

### 8.1 Run-Unabhängigkeit vom SSE-Socket

`POST /api/sessions/{session_id}/chat/stream` führt den Run in einer
Hintergrund-Task aus. Bei einem Socket-Disconnect:

- **mit** Header `X-Hermes-Interactive-Approvals` (Client sendet ihn nur bei
  bestätigter Fähigkeit `session_chat_interactive_approvals`): der Run läuft
  weiter, der begrenzte Replay-Puffer (`_SessionEventQueue`, nicht
  destruktiv) bewahrt den Tail inkl. terminalem `run.completed`/`error` und
  `done`;
- **ohne** diesen Header: `_drain_session_stream_task_on_disconnect`
  unterbricht den laufenden Run.

Die P4/P7-Annahme „ein Hermes-Run ist unabhängig vom Socket" gilt also nur für
das Mobile-v1-Opt-in. Ein terminaler Abschluss ist `assistant.completed` →
`run.completed` → `done` bzw. `error` → `done` (Server setzt den Status auf
`failed`).

### 8.2 Re-Attach (bestehend, P4)

`GET /api/sessions/{session_id}/runs/{run_id}/stream?after_seq=N`:
streng exklusives Replay (`seq > N`), danach Follow bis zum Terminal. Nicht
replaybarer Puffer → 409 `resume_gap`; unbekannter/fremder Run bzw.
Session-Mismatch → 404. Client: ungültige `after_seq` wird lokal abgelehnt.

### 8.3 Run-Status lesen (P7-Recovery, `_handle_get_run`)

`GET /v1/runs/{run_id}` (Bearer, `permission="status"`) liefert das pollable
Statusobjekt `{"object":"hermes.run","run_id":<id>,"status":<s>, …}` für den
eigenen Run. Geschlossene Statusmenge (`_set_run_status`):

```
queued, running, waiting_for_approval, stopping,
completed, failed, cancelled, interrupted
```

`TERMINAL_STATUSES` (`api_server_run_idempotency.py`) =
`{completed, failed, cancelled, interrupted}`. Terminale Status werden bis
`_RUN_STATUS_TTL = 3600 s` zum Polling aufbewahrt
(`_RUN_STREAM_TTL = 300 s` betrifft nur den Transport-Puffer). Fremder/
unbekannter/abgelaufener Run → 404 `run_not_found`.

Client-Vertrag (P7): **nur** ein terminaler Status gibt die Run-Ownership
autoritativ frei; `queued`/`running`/`waiting_for_approval`/`stopping` oder ein
Lesefehler ändern nichts. Der Client liest ausschließlich `run_id` und
`status`; `output`, `error`, `usage`, `session_id`, `pending_steer` usw. werden
weder gelesen noch propagiert.

> **Vertragslücke:** `GET /v1/runs/{run_id}` ist (wie `stop`/`steer`) **nicht**
> Teil der verhandelten Capability-Menge aus §2.2; der Client nutzt es wie die
> übrigen `/v1/runs/{run_id}/*`-Endpunkte aus demselben authentifizierten
> Namensraum. Für einen vollständig upstream-fähigen, fail-closed
> verhandelten Vertrag sollte der Server diese Run-Status-Fähigkeit (inkl.
> `protocol_version`) explizit ausweisen.

### 8.4 Grenze ohne bestätigte Run-ID

Fehlt nach dem initialen POST eine bestätigte Run-ID (`run.started` nie
gesehen), existiert **kein** Discovery-/Reconciliation-Endpunkt
(`session → run`). Der POST kann serverseitig trotzdem akzeptiert worden sein.
P7 gibt die Ownership dann **nicht** frei und sendet **keinen** neuen POST;
der Ausgang wird ehrlich als unbestimmt dargestellt. Eine sichere Recovery
erfordert eine zusätzliche Server-Fähigkeit (z. B. Session→aktiver-Run-
Discovery oder eine idempotente Startbestätigung).

Auch ein 5xx auf den initialen Streaming-POST gilt als unbestimmt (ein
Gateway/Upstream kann ihn nach bereits erfolgter Admission liefern); nur eine
3xx/4xx-Antwort belegt eine Ablehnung vor dem Run.

### 8.5 E2E-Vorbehalt

Es wurde **kein** echter Hermes-Mobile-v1-E2E-Test ausgeführt. Die
Korrektheit der Endpunkt-/Status-Annahmen stützt sich auf die lokale
Quelle und MockWebServer-Tests, nicht auf einen Live-Server.

## 9. P13-Status (2026-10-10)

Der Client-Vertrag dieses Dokuments ist unverändert gültig. Neu ist, dass der
**Server** (Orsik Mobile Bridge, Branch `p13/full-mobile-bridge`) ihn jetzt
vollständig in Front eines **unveränderten** offiziellen Hermes (`f86276d2`)
erfüllt:

- `session_chat_interactive_approvals` wird exakt v1 beworben (`once`/`deny`);
  `approval.request` erreicht den Client auf dem Session-Chat-Stream, die Antwort
  erfolgt über `POST /v1/runs/{run_id}/approval`. Der offizielle Core emittiert
  die Events (nicht simuliert).
- Der in §8.2 beschriebene Re-Attach
  `GET /api/sessions/{session_id}/runs/{run_id}/stream?after_seq=N` ist jetzt
  **server-seitig verfügbar**: Die Bridge übernimmt den Chat-Stream, puffert
  Events pro `run_id`/`seq` und replayt exklusiv ab `after_seq`, dann Live-Follow.
  Lücke → `409 resume_gap`; unbekannter Run → `404 run_not_found`. Kein
  erneuter Chat-POST, keine erfundenen Events.
- Persistente Device-Credentials: Pairing und (individueller) Revoke überleben
  den Bridge-Neustart; Tokens/Code nur als SHA-256-Hash at rest.

Grenzen bleiben: **keine** Per-Device-Ressourcen-Isolation (Single-Owner-
Delegation) und **kein** Session→Run-Discovery-Endpunkt.
