# Hermes Fresh-Install-Kompatibilität & Mobile-Bridge-Design (P12)

Stand: 2026-10-10. Ziel: Orsik Relay soll mit einer **frisch installierten,
unveränderten offiziellen** Hermes-Version verbunden werden können — ohne
private Fork vorauszusetzen. `hermes-server` blieb unverändert.

## 1. Methode und Umgebung

- Offizielle Quelle: `git clone https://github.com/NousResearch/hermes-agent`
  (HEAD `f86276d2`, Branch `main`), Version `vgit.f86276d` (2026.9.24).
- Isolierte Installation auf `build-host`: `~/hermes-official-src` mit
  `uv venv --python 3.14` + `uv pip install -e . aiohttp` (Python 3.14.7).
  **Kein** `curl | bash`, **keine** Änderung am offiziellen Quellcode.
- Isoliertes `HERMES_HOME=~/hermes-official-home`; `api_server` auf
  `127.0.0.1:8899` mit eigenem `API_SERVER_KEY` (0600, nie geloggt).
- Nachweise: `GET /health` → 200; unauth `GET /v1/capabilities` → 401;
  authentifiziert → 200 mit der offiziellen Capability-Antwort.
- PyPI (`hermes-agent` 0.19.0) ist veraltet und **nicht** die aktuelle Quelle;
  offiziell ist der Git-Stand.

## 2. Gap-Matrix: Mobile-v1 (privat) vs. offizieller Core

| Fähigkeit | Offizieller Core (`f86276d2`) | Mobile-v1 (privat) |
|---|---|---|
| Session-CRUD / Messages / Chat / Chat-Stream / Fork / Modell-Lock | ja | ja |
| Run submit/status/events/stop/steer/approval | ja | ja |
| Approval-Choices | `once`/`session`/`always`/`deny` | auf `once`/`deny` verengt |
| `run_approval_response`, `approval_events`, `tool_progress_events` | ja | ja |
| **Device-Pairing** `POST /v1/devices/pair` | **fehlt** | ja |
| **Device-Self-Revoke** `POST /v1/devices/self/revoke` | **fehlt** | ja |
| **Device-Credentials + Mobile-Scope-Allowlist** | **fehlt** | ja |
| **`session_chat_interactive_approvals` + `X-Hermes-Interactive-Approvals`** | **fehlt** | ja |
| **Session-Run-Re-Attach `…/runs/{run_id}/stream?after_seq=N`** | **fehlt** | ja |
| **`protocol_version`-Negotiation im Capability-Objekt** | **fehlt** | ja |
| Browser-Control, Artifacts, `reasoning_streaming` | ja | nein (Client ignoriert) |

Der offizielle `api_server` ist eine **Built-in-Plattform**
(`gateway/run.py:3321`, `APIServerAdapter._http_route_table`) — die
Routentabelle ist fest; zusätzliche Pfade für Device-/Reconnect-Funktionen
liefert der Core nicht.

## 3. Bestätigte offizielle Erweiterungspunkte

- **Plugin-Entry-Points:** `hermes_agent.plugins` (Laden),
  `hermes_agent.plugin_capabilities` (`hermes_cli/plugins_discovery.py:27-28`).
- **PluginContext** (`hermes_cli/plugins.py`): `register_platform`,
  `register_platform_handler`, `register_tool`, `register_cli_command`,
  `register_hook`, `register_middleware`, `register_memory_provider`,
  `register_approval_transport`, `register_system_prompt_section`,
  `register_skill`, `register_context_engine`, u. a.
- **Plattform-Registry** (`gateway/platform_registry.py`): „Adapters
  (built-in and plugin) self-register here … Plugins register via
  `PluginContext.register_platform()`; `GatewayRunner._create_adapter()`
  consults the registry first“.
- **Offizielle Doku** `gateway/platforms/ADDING_A_PLATFORM.md`: „Plugin Path …
  registers via `ctx.register_platform()` … This requires **zero changes to
  core Hermes code**“ (Adapter erben von `BasePlatformAdapter`).
- **Empirisch:** `hermes plugins list` und `hermes plugins validate` laufen;
  gebündelte Plugins werden gelistet.

**Kann ein Plugin HTTP-Endpunkte registrieren?** Ja, über einen eigenen
Plattform-Adapter (`register_platform`), der wie `api_server` einen eigenen
HTTP-Server betreibt. Ein Plugin kann **keine** Routen in den bestehenden
`api_server` einhängen (keine `add_route`-Erweiterung). Der vollständige
plugin-gehostete HTTP-Adapter wurde in dieser Phase **nicht** end-to-end
ausgeführt (siehe §5, gewählter Sidecar).

## 4. Architekturvarianten

- **A – offizielle API direkt:** verworfen. Kein Device-Scoping, der
  Master-API-Key läge auf dem Gerät, keine Pairing-/Reconnect-Semantik.
- **B – Hermes-Plugin:** technisch möglich und kern-frei (register_platform).
  Nachteil: Fremd-Code läuft im Core-Prozess (Vertrauen, Update-Kopplung).
- **C – eigenständiger Sidecar (gewählt):** unveränderter Core, eigener
  Prozess/Lebenszyklus, klare Deinstallation/Rollback; spricht den offiziellen
  `api_server` über Loopback und ergänzt Pairing/Tokens/Allowlist.
- **D – Upstream-Beitrag:** langfristig die beste Lösung (Mobile-v1 in den
  Core), aber nicht sofort verfügbar.

**Entscheidung:** PoC als **Sidecar (C)**; die Bridge erfindet fehlende
Core-Funktionen nicht: nicht implementierte Mobile-Features lässt sie im
Capability-Objekt **weg** (der Client handelt sie fail-closed als nicht
unterstützt aus), statt sie zu simulieren. Zielbild bleibt ein Upstream-Beitrag
(D); Variante B ist eine vertretbare Alternative, wenn kein zusätzlicher
Prozess gewünscht ist.

## 5. Minimaler, funktionsfähiger PoC (`bridge/`)

`bridge/` enthält die „Orsik Mobile Bridge“: ein loopback-gebundener
aiohttp-Sidecar über dem **unveränderten** offiziellen Hermes.

- Liest `API_SERVER_KEY` aus einer 0600-Env-Datei; gibt ihn **nie** an Clients.
- Kurzlebige Einmal-Pairing-Codes (24 Zeichen, 300 s, single-use, SHA-256).
- Device-Tokens (Form `hd_` + 43 base64url) mit enger Allowlist
  `{chat, runs, approvals}`.
- Öffentliche Pfade entsprechen exakt dem Mobile-v1-Vertrag (`/health`,
  `/v1/capabilities`, `/v1/devices/pair`, `/v1/devices/self/revoke`,
  `/api/sessions…`, `/v1/runs…`) — der bestehende `HermesConnectionClient` ist
  damit Drop-in; `device_id` ist eine UUID, der Device-Key hat die Form
  `hd_` + 43 Zeichen.
- Proxyt **nur** eine geschlossene Pfadmenge (`core.PROXIED_ROUTES`:
  `/api/sessions…`, `/v1/runs…`); alle anderen Pfade → `403`.
- Capabilities nutzen die offizielle Hülle (`object`, `platform`,
  `runtime.mode` exakt) und lassen den nicht implementierten interaktiven
  Approval-Kontrakt weg (der Client handelt ihn fail-closed als nicht
  unterstützt aus).

Live-Akzeptanztest (`bridge/tests/acceptance_live.py`) gegen den frischen
offiziellen Hermes: **31/31 Checks grün**, u. a. Pairing 201 (exakte Felder,
UUID `device_id`, `hd_`-Key, Permissions), der **vollständige** Chat-SSE-
Lebenszyklus (`run.started → message.started → assistant.completed →
run.completed → done`, jeder Event explizit geprüft), Run-Status, Out-of-Scope
403 (Jobs/Cron/Memory/Skills/Toolsets), fehlendes Token → 401, Self-Revoke 200
und danach 401 (revoked device). Ergänzend 29/29 Unit-/HTTP-Layer-Tests
(`python -m unittest discover -s tests`).

**Ehrliche Grenze:** Der Chat-*Transport* ist durchgängig nachgewiesen; der
Assistant-*Inhalt* benötigt einen konfigurierten Provider. Der frische,
unveränderte Core hat keinen Provider (eigene Wahl, keine Modelländerung);
`assistant.completed` trägt daher die offizielle Provider-Hinweis-Meldung.
Kein Stub, keine Simulation.

## 6. Fehlende Serverfähigkeiten (Blocker, nicht simuliert)

Im **offiziellen Core** fehlen:

1. Device-Pairing/-Credentials + Mobile-Scope.
2. Interaktive Session-Approvals (`X-Hermes-Interactive-Approvals`).
3. Session-Run-Re-Attach mit `after_seq`-Replay.
4. Explizite `protocol_version`-Negotiation im Capability-Objekt.

Der Bridge-PoC **implementiert (1) selbst** (Pairing, Device-Tokens, Allowlist,
Mobile-v1-Capability-Hülle mit `protocol_version`) und lässt (2) und (3)
bewusst weg — sie erscheinen nicht im Capability-Objekt und werden nicht
simuliert; der Client handelt sie fail-closed als nicht unterstützt aus.

## 7. Sicherheitsbewertung

- Bridge bindet nur Loopback; TLS extern (z. B. Tailscale Serve) — kein
  öffentlicher Port, kein Funnel.
- Master-Key ausschließlich serverseitig und nur im Upstream-Header.
- Codes/Tokens nur als Hashes; enge, feste Rechte-Allowlist; keine
  Admin-/Cron-/Memory-/Jobs-Oberfläche. Bridge-eigene Fehler nutzen einen
  geschlossenen Körper (`{"error":{"code":…}}`); Upstream-Antworten (inkl.
  Upstream-Fehlerkörper) werden unverändert weitergegeben.
- **Geräte-Autorisierungsmodell (Single-Owner-Delegation):** Die offizielle
  Hermes-API kennt keine Ressourcen-Ownership pro Gerät (eine Owner-Domäne für
  Sessions/Runs). Gepaarte Geräte wirken daher als Delegierte des einen Owners
  über **alle** Sessions/Runs. Die Route-Allowlist begrenzt die Endpunkte, nicht
  die einzelne Ressource; garantierte Per-Device-Isolation wird **nicht**
  bereitgestellt und **nicht** vorgetäuscht. Wirksame Einschränkungen: Pairing
  ist admin-gated, Tokens sind einzeln widerrufbar, die Approval-Auswahl ist auf
  `once`/`deny` verengt. Für echte Isolation ist eine Core-Erweiterung nötig (P13).
- Restrisiko: Der Bridge-Prozess ist privilegiert (hält den Master-Key);
  Schutz durch Loopback-Bindung, 0600-Env, Dateisystem-Rechte und minimale
  Angriffsfläche. Ein kompromittierter Bridge-Prozess hätte Hermes-API-Rechte.

## 8. Installations- und Rollback-Konzept

- Installation aus gepinnter Quelle/Wheel (`uv pip install`), **kein**
  `curl | bash`, **kein** Patchen von Core-Dateien, eigener venv.
- `bridge/install.sh` legt einen dedizierten `BRIDGE_HOME`-venv an und liefert
  eine `bridge.env`-Vorlage (Loopback, Admin-Token, Hermes-Env-Pfad).
- Keine öffentlichen Ports; TLS bleibt Pflicht (Serve/Tunnel).
- Rollback: Bridge-Prozess stoppen und `BRIDGE_HOME` löschen; Hermes und seine
  Modelle bleiben unberührt.

## 9. Konkrete nächste Implementierungsphase (P13)

1. **Upstream (Ziel):** Mobile-v1 in den offiziellen Core einbringen
   (`protocol_version`, Device-Credentials, Session-Approval-Opt-in,
   After-Seq-Replay) — als testbarer Upstream-PR.
2. **Bridge ausbauen (falls Upstream nicht kurzfristig):** After-Seq-Replay
   und interaktive Approvals als Bridge-eigene, gescopte Endpunkte (kein
   Core-Patch), inkl. Reconnect-/Approval-Tests.
3. **Client:** `HermesConnectionClient` optional gegen die Bridge-URL richten
   und den Mobile-v1-Vertrag beibehalten (Fail-Closed bleibt).
4. **Fresh-Install-Pfad:** Bridge-Installation + Tailscale Serve als
   verifizierte, versionierte Paketkette dokumentieren.

## 10. P12.1 – Mobile-Bridge Security Hardening

### Vorher nachweisbare Schwächen (Root Cause)
1. **Approval-Eskalation:** `POST /v1/runs/{run_id}/approval` wurde mit
   unverändertem Body an den offiziellen Core weitergeleitet; die offiziellen
   Choices `session`/`always` waren damit über die Bridge nutzbar (nur
   `once`/`deny` sind laut Mobile-v1 erlaubt).
2. **Redirect-Credential-Leak:** aiohttp folgte Upstream-Redirects; der
   Master-Key im `Authorization`-Header hätte an einen Fremd-Host gelangen können.
3. **Query-Strings ungefiltert** an erlaubte Endpunkte weitergegeben.
4. **Body-Limit 4 MiB** hätte die clientseitig unterstützte 5-MiB-Bildfunktion
   (≤ 8 MiB base64-JSON) unbemerkt gebrochen.
5. **Kein hartes Token-/Code-Limit** (Prune erst nach Überschreitung) und keine
   Rate-Limits.

### Implementierte Fixes
- Approval strikt validiert (nur `once`/`deny`; `request_id` nicht leer, ≤ 256,
  control-frei; exakt `{choice, request_id}`); ungültige Requests → closed `400`,
  **kein Upstream-POST**. Defense-in-depth: nur der kanonische Pfad wird vom
  dedizierten Handler bedient; Trailing-Slash-Varianten (`…/approval/`, `…//`)
  und die generische Proxy-Route lehnen Approval-Pfade ab (`403`). Dieser
  Bypass wurde im unabhängigen Review gefunden und behoben.
- Upstream-Redirects: `allow_redirects=False`; `3xx` → closed `502
  upstream_redirect` (kein Location-Relay, kein Host-Wechsel des Master-Keys).
- Query-Allowlist (`limit`/`offset`/`order`/`include_children` nur auf GET-
  Session-Routen), sonst `403 query_not_allowed`.
- Body-Limit `10_000_000` (wie offizieller `api_server`); malformed JSON → `400`;
  Oversize → `413` (nicht 400).
- Rate-Limits für Pairing und Admin-Code (Admin erst nach Auth, damit ein
  unauthentifizierter Aufrufer den Operator nicht aussperrt); harte Caps für
  Token-/Code-Store (`mint`/`issue` verweigern jenseits des Caps → `503`).
- Device-Autorisierungsmodell als Single-Owner-Delegation dokumentiert (§7).

### Testnachweise
- Bridge-unit (Core + HTTP-Layer): **57 Tests, 0 Fehler** — inkl.
  Approval-Negativ (0 Upstream-POSTs), Trailing-Slash-Bypass, Redirect-Fail-
  Closed, Query-Allowlist, Rate-Limit, harte Caps, Oversize-413, Secret-Nicht-Leak.
- Live-Akzeptanz gegen den frischen offiziellen Hermes: **34/34 grün** (u. a.
  Approval-Eskalation lokal `400`, `once` erreicht den Core, `?evil=` → `403`,
  Pairing/Capabilities/Chat-SSE/Run-Status/Self-Revoke unverändert).
- Fresh-Install-Kompatibilität erhalten (unveränderter Core, `f86276d2`).

### Verbleibende Restrisiken
- Keine garantierte Per-Device-Isolation (offizielle API ohne Ownership) — §7.
- Bridge ist privilegiert (hält Master-Key); Schutz nur über Loopback/OS-Rechte.
- Token-/Code-Stores sind flüchtig (Prozessneustart verliert sie) — P13.

### Offene P13-Aufgaben
- Upstream: `protocol_version` + Device-Credentials + **Ressourcen-Ownership**
  (echte Per-Device-Isolation) + After-Seq-Replay + interaktives
  Session-Approval-Opt-in.
- Bridge-Alternative: `after_seq`-Replay und interaktive Approvals als
  Bridge-eigene, gescopte Endpunkte (kein Core-Patch).
- Persistente, dauerhafte Geräteverwaltung (verschlüsselt, über Neustart).

## 11. P13 – Full Mobile Bridge (2026-10-10)

Branch `p13/full-mobile-bridge` (Basis `f339343`). Ziel: vom abgesicherten
P12.1-Prototyp zur vollständigen mobilen Anbindung einer frischen, unveränderten
offiziellen Hermes-Installation. Kein Core-Patch, kein hermes-server-Eingriff.

### 11.1 Aktualisierte Core-Analyse (offiziell `f86276d2`)

Der offizielle Core hat sich seit P12 weiterentwickelt; **neu belegt** durch
read-only Quellanalyse und Live-Akzeptanz:

- `POST /api/sessions/{id}/chat/stream` vergibt eine `run_id` (`run_<uuid>`),
  registriert sie in `_run_owners`/`_run_statuses` und stempelt **jedes** Event
  mit `session_id`/`run_id`/`seq`/`ts` (`_SessionEventQueue`).
- Der Session-Chat-Stream registriert einen Approval-Notifier
  (`_register_session_stream_approval`, `_run_approval_sessions[run_id]=run_id`)
  und emittiert bei einer geflaggten Aktion `approval.request`. `api_server`
  bleibt formal „unattended", ist aber **interaktiv**, solange ein Notifier
  registriert ist (`_presence`-Diskriminator, `approval_context._NOTIFIER_BLIND_APPROVAL_PLATFORMS`
  enthält nur webhook/msgraph). → Interaktive Session-Approvals sind im Core
  vorhanden.
- `POST /v1/runs/{run_id}/approval` akzeptiert für einen Session-Run genau die
  `run_id`; Choices im Core sonst `once/session/always/deny`.
- `GET /v1/runs/{run_id}` liefert den persistierten, pollbaren Status eines
  Session-Runs.
- `GET /v1/runs/{run_id}/events` hat für `/v1/runs`-Runs einen **Replay-Puffer**
  (`_RunStream`, `Last-Event-ID`/`last_seq`, `replay.truncated`) — **nicht** aber
  für den Session-Chat-Stream (kein Eintrag in `_run_streams`).

### 11.2 Aktualisierte Gap-Matrix (P13)

| Fähigkeit | Offizieller Core `f86276d2` | Bridge (P13) |
|---|---|---|
| Session-CRUD/Messages/Chat/Stream (+`seq`) | ja | proxied |
| Interaktive Session-Approvals `approval.request` | **ja** | bewirbt v1-Vertrag, verengt auf `once`/`deny` |
| `POST /v1/runs/{run_id}/approval` für Session-Run | ja | validiert + proxied |
| `GET /v1/runs/{run_id}` Status | ja | proxied |
| Session-Run-Re-Attach `…/runs/{run_id}/stream?after_seq=N` | **fehlt** | **bridge-eigen implementiert** (Takeover + Replay-Buffer) |
| Device-Pairing/Credentials/Scope | fehlt | **bridge-eigen** |
| `protocol_version`-Negotiation | fehlt | **bridge-eigen** (Capability-Hülle) |
| Persistente Geräteverwaltung | fehlt | **bridge-eigen** (SQLite, 0600) |
| Per-Device-Ressourcen-Ownership | fehlt | **nicht möglich** (Single-Owner-Delegation) |
| Run-Discovery (Session→aktiver Run) | fehlt | nicht implementiert (kein Endpunkt) |

### 11.3 Umsetzung (Bridge)

- **Reconnect/Run-Ownership:** Der Chat-Stream wird serverseitig übernommen; der
  Upstream-SSE läuft unabhängig vom Client weiter und wird pro `run_id`/`seq`
  begrenzt gepuffert. `GET …/runs/{run_id}/stream?after_seq=N` replayt exklusiv
  und folgt live; Lücken → `409 resume_gap`, unbekannt → `404`; nie erfundene
  Events, nie ein zweiter Chat-POST.
- **Interaktive Approvals:** Capability `session_chat_interactive_approvals`
  exakt v1 (`once`/`deny`), Durchreichen der `approval.request`-Events,
  lokale Validierung der Antwort.
- **Persistente Geräte:** SQLite-Store (nur Hashes, TTL, Caps, atomarer
  Single-Use-Redeem, Migration, fail-closed) → Pairing/Revoke überleben den
  Bridge-Neustart.
- **Installation/Operator:** `install.sh` (dedizierter venv, `--update`,
  `--systemd`, `--uninstall`), User-Unit, **lokale** Operator-CLI
  (`admin pairing-code/list-devices/revoke-device`), Deep-Link + optionaler QR.
  Kein HTTP-Admin mehr.

### 11.4 Verbleibende Core-Grenzen (nicht simuliert)

1. **Per-Device-Isolation** fehlt im Core → Single-Owner-Delegation (dokumentiert).
2. **Run-Discovery** (Session→aktiver Run) fehlt → ohne bestätigte `run_id`
   bleibt der Ausgang unbestimmt (P7-Invariante).
3. **Assistant-Inhalt** ist ohne konfigurierten Provider nicht real prüfbar
   (Transport vollständig verifiziert).

### 11.5 Live-Nachweis

Frischer offizieller Core `f86276d2` + Bridge: `bridge/tests/acceptance_live.py`
**37/37 grün** (Health, Pairing 201 mit exakten Feldern, Capabilities mit
`device_credentials` + `session_chat_interactive_approvals` exakt,
Approval-Eskalation lokal `400`, `once` erreicht den Core, Query-`403`,
Out-of-Scope-`403`, fehlendes Token `401`, Session-Create/-Messages,
vollständiger Chat-SSE-Lebenszyklus inkl. `run.completed`, **Re-Attach 200 mit
Replay**, unbekannter Run `404`, Self-Revoke `200` + danach `401`).

## 12. OR-17 – Hermes-Kompatibilitätsmatrix (2026-10-16)

Jeder Stand läuft isoliert auf `build-host` (eigener Git-Worktree, eigenes
`HERMES_HOME`, eigener Loopback-Port, eigene Bridge-Instanz und `state.db`;
das produktive Gateway bleibt unberührt). Venv je Stand: `uv sync --frozen
--extra messaging` (ohne `messaging` fehlt `aiohttp`, der `api_server` startet
dann nicht). Bridge: Stand `or-night-integration`. Kein Modellprovider
konfiguriert (Assistant-Inhalt weiterhin nicht prüfbar, vgl. 11.4).

| Hermes-Stand | Version (`hermes --version`) | `acceptance_live.py` | Capabilities ggü. `f86276d2` |
|---|---|---|---|
| `f86276d2` (getestete Basis) | – | **37/37** | – |
| `v2026.9.24` (neuestes Release-Tag) | v0.21.5 (2026.9.24) | **37/37** | identisch (112 Schlüssel) |
| `f974134f` (`main`, 2026-10-16) | v0.22.1+682 | **37/37** | +3 Schlüssel, s. u. |

Capability-Differenz `main`: `features.run_unknown_resolution = true` und
`endpoints.run_unknown_resolution = POST /v1/runs/{run_id}/resolve-unknown`.

Fail-closed-Nachweis (live gegen `main`): Die Bridge erzeugt
`/v1/capabilities` selbst und reicht das neue Feature nicht durch (0 Treffer);
`POST /v1/runs/{id}/resolve-unknown` liegt außerhalb von `PROXIED_ROUTES` und
endet mit einem gültigen Device-Token an der Bridge mit **403**. Eine Nutzung
(potenziell relevant für die Run-Discovery-Grenze 11.4/2) erfordert eine
bewusste Erweiterung von Bridge-Allowlist, Capability-Envelope und
Client-Vertrag; nicht Teil von OR-17.
