# Orsik Mobile Bridge – Installation & Betrieb (P13)

Stand: 2026-10-10. Zielpfad: **frische, unveränderte offizielle Hermes-Installation**
→ Bridge installieren → privates HTTPS bereitstellen → Pairing erzeugen → QR/Deep-Link
scannen → Chat nutzen. Kein Hermes-Fork, keine Core-Patches, kein öffentlicher Port.

## 1. Voraussetzungen

- Offizielles Hermes (Git-Stand, hier getestet: `NousResearch/hermes-agent`
  `f86276d2`, Python 3.14) läuft mit aktivem `api_server` auf **Loopback**
  (Default `127.0.0.1:8899` oder `:8642`), `API_SERVER_KEY` in 0600-Env.
- Ein privater HTTPS-Zugangsweg zum Server (siehe §4): Tailscale Serve,
  WireGuard+Reverse-Proxy oder LAN mit gültigem Zertifikat.
- `uv` und ein Python ≥ 3.11 (Bridge-venv getrennt vom Hermes-venv).

## 2. Installation

```sh
./install.sh [--update] [--systemd] /pfad/zum/bridge/source
```

- Legt `$BRIDGE_HOME` (Default `~/.local/orsik-mobile-bridge`, 0700) und einen
  **dedizierten venv** an; installiert die Abhängigkeiten **hash-gepinnt** aus
  dem eingecheckten `bridge/uv.lock` (`uv export --frozen` mit
  `--require-hashes`, danach die Bridge selbst mit `--no-deps`), **kein**
  `curl | bash`. Fehlt `uv.lock`, bricht der Installer ab.
- Existiert bereits eine Installation, bricht `install.sh` ohne `--update` ab
  (bestehende Installationen/Ports werden nicht überschrieben).
- Schreibt `$BRIDGE_HOME/bridge.env` (0600) als Vorlage.

Damit ist die Installation reproduzierbar: derselbe Quellstand liefert an jedem
Tag und auf jedem Rechner exakt dieselben Abhängigkeitsversionen. Abhängigkeiten
ändern sich nur bewusst — `uv lock --upgrade-package <pkg>` plus Review des
Lock-Diffs (`uv lock --check` in CI schlägt bei veraltetem Lock fehl). Die CI
baut zusätzlich Wheel, `SHA256SUMS` und ein CycloneDX-SBOM und legt sie als
Artefakt `bridge-dist` ab.

`bridge.env`:

```
# nur Loopback; die Bridge erzwingt das
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
# offizieller api_server; nur Loopback-HTTP-IP mit Port, kein Hostname,
# kein Pfad — sonst startet die Bridge nicht
BRIDGE_UPSTREAM=http://127.0.0.1:8899
# Datei mit API_SERVER_KEY (0600)
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env
BRIDGE_STATE_DB=/home/USER/.local/orsik-mobile-bridge/state.db
# für Deep-Link/QR
BRIDGE_PUBLIC_BASE_URL=https://<host>
```

Kommentare nur in eigenen Zeilen: `bridge.env` wird als `KEY=VALUE` gelesen,
ein Inline-Kommentar würde Teil des Werts (und z. B. `BRIDGE_BIND` /
`BRIDGE_UPSTREAM` ungültig machen).

## 2a. Preflight: `./install.sh --check`

Prüft eine bestehende Installation, **ohne etwas anzulegen oder zu verändern**,
und liest dafür `$BRIDGE_HOME/bridge.env` zeilenweise als Daten (`KEY=VALUE`,
Kommentare/Leerzeilen ignoriert; kein `source`, kein `eval`). Ausgabe pro
Prüfung genau eine Zeile `OK <name>` / `WARN <name>: <grund>` /
`FAIL <name>: <grund>`; Key-/Token-Werte werden nie ausgegeben. Exit-Code 1,
sobald eine Prüfung FAIL ist, sonst 0; am Ende `PREFLIGHT: PASS` bzw.
`PREFLIGHT: FAIL (<n> failed)`.

```sh
./install.sh --check
```

| Prüfung | Erwartung |
|---|---|
| `home_mode` | `$BRIDGE_HOME` existiert, Modus 700 |
| `env_mode` | `bridge.env` existiert, Modus 600 |
| `venv` | `$BRIDGE_HOME/venv/bin/orsik-mobile-bridge` ausführbar |
| `bind` | `BRIDGE_BIND` leer, `127.0.0.1` oder `::1` |
| `upstream` | `BRIDGE_UPSTREAM` (Default `http://127.0.0.1:8899`) passt auf `^http://(127.x.x.x\|[::1]):port$` |
| `hermes_env` | `BRIDGE_HERMES_ENV` gesetzt, Datei 0600, mit `API_SERVER_KEY=…` |
| `public_base_url` | leer → WARN (kein Deep-Link/QR); sonst muss es mit `https://` beginnen |
| `port` | `BRIDGE_PORT` (Default 9842): `ss` fehlt → WARN; Listener auf Loopback/`0.0.0.0` → WARN („port in use") |
| `hermes_health` | `curl` auf `$UPSTREAM/health` == 200; nur wenn `upstream` OK war, sonst WARN |

Zusätzlich prüft der normale Installationsmodus **vor** jeder Änderung, ob `uv`
vorhanden ist und `uv python find ${BRIDGE_PYTHON:-3.14}` funktioniert; sonst
bricht er mit Hinweis ab. Nach einer Installation erinnert er an
`./install.sh --check`.

## 3. Operator-Kommandos (kein HTTP-Admin)

Pairing-Codes werden **ausschließlich lokal** erzeugt — die Dateisystemrechte auf
den Store sind die Autorisierungsgrenze; Geräte erhalten nie einen Admin-Weg.

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code \
  --base-url https://<host>
# -> "Pairing code: XXXX-XXXX-XXXX-XXXX-XXXX-XXXX  (valid 300s, single-use)"
# -> "Deep link:    orsik://pair?server=...&code=..."
# -> ASCII-QR (wenn 'qrencode' installiert ist)

... admin list-devices
... admin revoke-device <device_id>
```

Sicherheitseigenschaften: 24 Zeichen, single-use, TTL 300 s, servergebunden,
nur SHA-256-Hash at rest; **kein** Master-Key und **kein** Device-Key im Link.

## 4. Privates HTTPS (ein Weg genügt)

- **Tailscale Serve** (im Tailnet, kein Funnel):
  `tailscale serve --bg --https=8443 http://127.0.0.1:9842`
  → `https://<host>.<tailnet>:8443`
- **WireGuard + Reverse-Proxy**: Hermes/Bridge bleiben auf Loopback; der Proxy
  terminiert ein öffentlich vertrauenswürdiges Zertifikat und leitet nur die
  Quelle-Allowlist weiter.
- **LAN**: gültiges Zertifikat via Split-DNS auf die private Adresse.

Kein Klartext-HTTP, kein TLS-Bypass, kein öffentlicher Port. Die Android-App
prüft Zertifikat + Vertrag; bei ungültigem Zertifikat bricht sie ab.

## 5. Start / Stop / Status

Ohne systemd:

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env $BRIDGE_HOME/venv/bin/orsik-mobile-bridge
```

Mit `--systemd` (User-Unit `orsik-mobile-bridge.service`):

```sh
systemctl --user enable --now orsik-mobile-bridge.service
systemctl --user status  orsik-mobile-bridge.service
systemctl --user restart orsik-mobile-bridge.service
```

## 6. Update / Rollback / Backup / Restore / Deinstallation

- **Update:** `./install.sh --update [--systemd] /pfad/zum/source` (der Store
  bleibt erhalten; Pairing/Tokens überleben).
- **Deinstallation:** `./install.sh --uninstall` (Unit stoppen/entfernen) und
  `rm -rf $BRIDGE_HOME` (venv + Store). Hermes und seine Modelle bleiben unberührt.

### Backup / Restore

Operator-Backup des Stores (atomar über eine temp-Datei + Hardlink, Ziel
0600; ein bereits existierendes Ziel wird nie überschrieben):

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin backup $BRIDGE_HOME/state.db.bak
# -> "backup written: /home/USER/.local/orsik-mobile-bridge/state.db.bak"
# -> bei Fehler: "error: ..." auf stderr, Exit 1
```

- **Pre-Migration-Backup (automatisch):** Öffnet die Bridge einen Store mit
  älterem Schema (1 ≤ `user_version` < aktuelle Version), legt sie **vor** der
  Migration `state.db.pre-v<version>.bak` an (0600, ebenfalls atomar). Eine
  frische DB (`user_version` 0) bekommt kein Backup. Ein bereits vorhandenes
  Ziel wird nicht überschrieben, sondern als
  `state.db.pre-v<version>.<timestamp>.bak` abgelegt. Schlägt das Backup fehl,
  öffnet die Bridge den Store **nicht** und migriert nichts.
- **Restore:** Bridge stoppen, eventuell vorhandene `state.db-wal` und
  `state.db-shm` neben dem Ziel entfernen (sonst mischt SQLite den alten WAL in
  das zurückgespielte Backup), das Backup über `$BRIDGE_STATE_DB` kopieren (oder
  das Backup selbst als `BRIDGE_STATE_DB` setzen), Bridge starten, danach
  `admin list-devices` prüfen.
- **Rollback nach einer Schema-Migration:** nur durch Restore des
  Pre-Migration-Backups (Bridge stoppen → Backup über `BRIDGE_STATE_DB`
  kopieren → alte Bridge-Version starten). Eine ältere Bridge kann einen bereits
  migrierten Store **nicht** öffnen: sie verweigert ein neueres Schema
  fail-closed. Ohne Pre-Migration-Backup gibt es keinen Rollback-Weg.

⚠️ **WARNUNG:** Ein Restore stellt den **Autorisierungszustand zum
Backup-Zeitpunkt** wieder her. Revokes sind gelöschte Zeilen im Store, kein
Widerruf-Log; deshalb sind Geräte, die **nach** dem Backup widerrufen wurden,
nach dem Restore wieder gültig. Nach **jedem** Restore `admin list-devices`
prüfen und betroffene Geräte erneut mit `admin revoke-device <device_id>`
widerrufen.

## 7. Verifizierter Weg (build-host, 2026-10-10)

Frischer offizieller Core (`f86276d2`) → Bridge installiert (Quell-Checkout,
`uv`-venv) → Loopback-Proxy → Tailscale Serve `:8443` → Emulator API 36 →
Live-Akzeptanz **37/37 grün** (`bridge/tests/acceptance_live.py`). Details:
`docs/bridge-e2e.md`.
