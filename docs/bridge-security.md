# Orsik Mobile Bridge – Security Threat Model (P13)

Stand: 2026-10-10.

## 1. Schutzobjekt

Der Hermes-Master-API-Key gewährt mittelbar Zugriff auf mächtige Tools. Die
Bridge hält ihn serverseitig (Loopback-Prozess) und gibt ihn **nie** an Clients.
Geräte erhalten nur kurzlebige, einzeln widerrufbare Device-Tokens mit der festen
Rechte-Allowlist `{chat, runs, approvals}`.

## 2. Trust Boundaries

- **Android-Gerät**: untrusted; nur Device-Token, kein Master-Key, kein Admin.
- **TLS-Terminator** (Tailscale Serve / Reverse-Proxy): vertrauenswürdig für
  Transport, aber die Bridge prüft nicht auf ihn — sie bindet nur Loopback.
- **Bridge-Prozess**: privilegiert (hält Master-Key). Schutz durch Loopback-
  Bindung, 0600-Secrets, minimale Angriffsfläche, geschlossene Route-/Query-Allowlist.
- **Offizieller Hermes-Core**: autoritativ für Run-Status und Approvals; unverändert.

## 3. Bedrohungen & Maßnahmen

| Bedrohung | Maßnahme |
|---|---|
| Master-Key-Leak an Gerät/Log | Key nur serverseitig; nie im Body/Response; geschlossene Fehler |
| Master-Key-Exfiltration durch fehlkonfiguriertes `BRIDGE_UPSTREAM` | Ziel wird beim Start geprüft: nur Loopback-HTTP-**IP** mit explizitem Port (kein Hostname, kein Pfad/Query/Fragment, kein Userinfo); sonst bricht der Start ab, ohne den Wert zu wiederholen |
| Approval-Eskalation (`session`/`always`) | Lokale Validierung erlaubt nur `once`/`deny`; ungültig → `400`, **kein** Upstream-POST |
| Redirect-Credential-Hop | `allow_redirects=False`; `3xx` → `502`, `Location` verworfen |
| SSRF/Path Traversal | Geschlossene Route-Allowlist; Segment-Validierung; Exact-Match |
| Query-Injection | Query-Allowlist pro Route; sonst `403` |
| Body-Overflow | Limit `10_000_000`; Oversize → `413`; malformed JSON → `400` |
| Brute-Force Pairing | Rate-Limit; harte Code-/Device-Caps; Single-Use atomar |
| Token-/Code-Diebstahl at rest | Nur SHA-256-Hashes; DB 0600/0700; nie geloggt |
| Persistentes Credential kompromittiert | Einzelwiderruf durabel über Neustart |
| Restore alter Store-Stand | Backup/Restore nur Operator-seitig (Dateisystemrecht); ein Restore stellt den Autorisierungszustand zum Backup-Zeitpunkt wieder her — danach widerrufene Geräte sind wieder gültig (`admin list-devices` prüfen, betroffene Geräte erneut widerrufen); Pre-Migration-Backup automatisch, kein Down-Migrate |
| Store-Ausfall | Fail-closed (`503 store_unavailable`), nie fail-open |
| Blockierender Store-Zugriff | Store-Aufrufe laufen seriell in einem eigenen DB-Thread und blockieren den aiohttp-Eventloop nie |
| Öffentliche Admin-Oberfläche | Kein HTTP-Admin; Codes nur lokale Operator-CLI |
| Run-Verlust/Duplikat bei Disconnect | Server-seitiger Stream-Takeover + `after_seq`-Replay; kein zweiter POST |
| Fingierte Run-Completion | Nie erfundene Events; nicht rekonstruierbar → `409`/`404` |
| Speichererschöpfung durch Upstream/Runs | Frame ≤ 1 MiB **nach** Redaktion (sonst `502 upstream_frame_too_large` bzw. Abbruchkommentar, kein `done`; Bild-Echo siehe §3.1); Fehlerkörper ≤ 64 KiB; Backlog je Run 1000 Frames/4 MiB; Subscriber-Queue 256/2 MiB, max. 4 je Run (Drop statt Block); max. 16 aktive Runs (`503 bridge_busy` vor dem Upstream-POST); terminale Runs ≤ 64 MiB |
| Öffentlicher API-Port | Bind nur `127.0.0.1`; TLS extern; kein Funnel |
| TLS-Bypass | Bridge terminiert kein TLS; App erzwingt HTTPS + Systemtrust |

## 3.1 Bild-Echo in `run.started` (OR-10a)

Der Core echot im ersten `run.started`-Frame den kompletten Request inklusive
`user_message` (u. a. `content[1].image_url.url` als
`data:image/...;base64,...`). Ein Rohbild bis 5 MiB (`MAX_IMAGE_BYTES` der App)
ergibt dabei einen Frame deutlich über 1 MiB, obwohl der Request zulässig ist
(base64-Body bis 8 MiB, Bridge-Body-Limit 10 MB). Ohne Gegenmaßnahme antwortete
die Bridge auf jeden Bildturn mit `502 upstream_frame_too_large`.

Der Session-Chat-Pump (`_pump_chat_stream`) dekodiert deshalb **nur dort** mit
`_MAX_ECHO_FRAME_BYTES = _MAX_BODY_BYTES + 64 KiB` (der Core echot höchstens den
Request-Body plus Envelope). Für jeden `run.started`-Frame, dessen `data`-JSON ein
Objekt mit `user_message` ist, entfernt die Bridge genau diesen Schlüssel; alle
übrigen Felder (`seq`, `run_id`, …), vorhandene `id:`/`event:`-Zeilen und deren
Reihenfolge bleiben erhalten. Dies ist die **einzige** Stelle, an der die Bridge
Upstream-Inhalt re-serialisiert statt byte-identisch weiterzureichen.

**Nach** der Redaktion gilt für jeden Frame unverändert `sse.MAX_FRAME_BYTES`
(1 MiB, gemessen an `len(frame.raw)`): ein größerer Frame führt zum selben
Verhalten wie zuvor (`502 upstream_frame_too_large` vor dem ersten Frame, sonst
Abbruchkommentar `: bridge_upstream_frame_too_large`, nie ein erfundenes `done`).
`sse.MAX_FRAME_BYTES` und alle Puffer-/Queue-Grenzen in `runhub.py` bleiben
unverändert. Der roh proxied `GET /v1/runs/{run_id}/events` läuft nicht über den
SSEDecoder und hat keinen Frame-Grenzwert; dort greift der Defekt nicht.

## 4. Single-Owner-Delegation (Restrisiko)

Die offizielle Hermes-API kennt **keine** Ressourcen-Ownership pro Gerät. Jedes
gepairte Gerät handelt als Delegierter des einen Owners über **alle**
Sessions/Runs. Die Route-Allowlist begrenzt Endpunkte, **nicht** einzelne
Ressourcen. Garantierte Per-Device-Isolation wird nicht bereitgestellt und nicht
vorgetäuscht; sie erfordert eine Hermes-Core-Erweiterung.

Wirksame Einschränkungen: Pairing ist Operator-gated (lokale CLI), jedes Token
ist durabel einzeln widerrufbar, die Approval-Auswahl ist auf `once`/`deny`
verengt.

## 4.1 Revalidierung gegen reale positive Approvals (OR-14, 2026-10-16)

Live auf `build-host`: offizieller Core `f86276d2` mit echtem Provider
(DeepSeek, `deepseek-flash`, nur im isolierten Test-Home), Bridge
`or-night-integration`, echter Tool-Request (`rm -rf` auf ein vorher angelegtes
Marker-Verzeichnis unter `/tmp`).

| Antwort des Geräts | Bridge | Core / Wirkung |
|---|---|---|
| `once` | `200`, weitergeleitet | genau ein `approval.request`, `tool.completed`, Marker gelöscht, Run `completed` |
| `deny` | `200`, weitergeleitet | `tool.failed`, Marker unverändert, Run `completed` |
| `always` / `session` | **`400` lokal**, kein Upstream-POST | erst das folgende `deny` löst auf, Marker unverändert |

Der Core bietet im `approval.request` `choices = [once, session, always, deny]`
an; die Verengung auf `once`/`deny` leistet ausschließlich die Bridge.

**Befund: Approval-Modus des Cores.** Mit dem Core-Default `approvals.mode:
smart` bewertet ein Hilfs-LLM gefährliche Kommandos und genehmigt als harmlos
eingestufte **ohne** `approval.request`: live wurde `rm -rf /tmp/<marker>` ohne
Rückfrage ausgeführt. Die mobile Approval-Fläche sieht nur, was der Core
eskaliert; die Bridge kann das nicht erzwingen. **Betriebsanforderung:** Das
Hermes-Home hinter der Bridge setzt `approvals.mode: manual` (siehe
`bridge-installation.md`). Mit `manual` kam jede Ausführung nur nach
`approval.request` zustande (Tabelle oben).

**Nachvollziehbarkeit.** Jede zustandsändernde Gerätanfrage erzeugt eine
Audit-Zeile (Logger `orsik_mobile_bridge.audit`):
`audit action=<chat|approval|stop|steer> device=<device_id> run=/session=<id>
[choice=<once|deny|rejected>] [status=<http>]`. Abgelehnte Choices werden als
`rejected` geführt (Rohwert = Client-Eingabe, nie geloggt); Tokens,
Master-Key und Nachrichteninhalte erscheinen nie. Die Zeile entsteht, sobald
der Upstream-Status feststeht, also vor der Antwort an das Gerät.

Unverändert gilt §4: Jedes gepairte Gerät kann Approvals/Stop/Steer für
**jeden** Run des Owners senden (keine Per-Device-Isolation); das Audit macht
das zurechenbar, verhindert es aber nicht. Nicht live geprüft: Antwort eines
zweiten Geräts auf den Run eines ersten.

## 5. Nicht-Ziele / Grenzen

- Kein Schutz gegen einen kompromittierten Bridge-Prozess (der hätte
  Hermes-API-Rechte) — gemildert durch Loopback, OS-Rechte, Minimalfläche.
- Kein Schutz gegen ein kompromittiertes Operator-Konto (CLI + Store-Zugriff).
- Die Bridge beweist nichts über die Vertrauenswürdigkeit des TLS-Terminators;
  bei ungültigem Zertifikat bricht der **Client** ab.

## 6. Secrets-Handling

- `API_SERVER_KEY` nur aus 0600-Env gelesen, nur im Upstream-Header.
- Pairing-Codes/Tokens: einmalige Ausgabe, nur Hash at rest, nie in Logs,
  Fehlerkörpern, URLs (außer dem `orsik://pair`-Link) oder Reports.
- Keine Credentials in Git; `bridge.env`, `state.db` sind ausgeschlossen.
