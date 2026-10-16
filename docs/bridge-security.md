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
| Store-Ausfall | Fail-closed (`503 store_unavailable`), nie fail-open |
| Öffentliche Admin-Oberfläche | Kein HTTP-Admin; Codes nur lokale Operator-CLI |
| Run-Verlust/Duplikat bei Disconnect | Server-seitiger Stream-Takeover + `after_seq`-Replay; kein zweiter POST |
| Fingierte Run-Completion | Nie erfundene Events; nicht rekonstruierbar → `409`/`404` |
| Speichererschöpfung durch Upstream/Runs | Frame ≤ 1 MiB (sonst `502 upstream_frame_too_large` bzw. Abbruchkommentar, kein `done`); Fehlerkörper ≤ 64 KiB; Backlog je Run 1000 Frames/4 MiB; Subscriber-Queue 256/2 MiB, max. 4 je Run (Drop statt Block); max. 16 aktive Runs (`503 bridge_busy` vor dem Upstream-POST); terminale Runs ≤ 64 MiB |
| Öffentlicher API-Port | Bind nur `127.0.0.1`; TLS extern; kein Funnel |
| TLS-Bypass | Bridge terminiert kein TLS; App erzwingt HTTPS + Systemtrust |

## 4. Single-Owner-Delegation (Restrisiko)

Die offizielle Hermes-API kennt **keine** Ressourcen-Ownership pro Gerät. Jedes
gepairte Gerät handelt als Delegierter des einen Owners über **alle**
Sessions/Runs. Die Route-Allowlist begrenzt Endpunkte, **nicht** einzelne
Ressourcen. Garantierte Per-Device-Isolation wird nicht bereitgestellt und nicht
vorgetäuscht; sie erfordert eine Hermes-Core-Erweiterung.

Wirksame Einschränkungen: Pairing ist Operator-gated (lokale CLI), jedes Token
ist durabel einzeln widerrufbar, die Approval-Auswahl ist auf `once`/`deny`
verengt.

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
