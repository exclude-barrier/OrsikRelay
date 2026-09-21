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
