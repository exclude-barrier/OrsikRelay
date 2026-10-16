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
BRIDGE_BIND=127.0.0.1                 # nur Loopback; die Bridge erzwingt das
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8899 # offizieller api_server
BRIDGE_HERMES_ENV=/home/USER/.hermes/.env   # Datei mit API_SERVER_KEY (0600)
BRIDGE_STATE_DB=/home/USER/.local/orsik-mobile-bridge/state.db
BRIDGE_PUBLIC_BASE_URL=https://<host> # für Deep-Link/QR
```

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

## 6. Update / Rollback / Deinstallation

- **Update:** `./install.sh --update [--systemd] /pfad/zum/source` (der Store
  bleibt erhalten; Pairing/Tokens überleben).
- **Rollback:** alte Quelle erneut mit `--update` installieren; der Store bleibt
  kompatibel (Schema ist abwärts gepflegt und verweigert nur echte Zukunftsversionen).
- **Deinstallation:** `./install.sh --uninstall` (Unit stoppen/entfernen) und
  `rm -rf $BRIDGE_HOME` (venv + Store). Hermes und seine Modelle bleiben unberührt.

## 7. Verifizierter Weg (build-host, 2026-10-10)

Frischer offizieller Core (`f86276d2`) → Bridge installiert (Quell-Checkout,
`uv`-venv) → Loopback-Proxy → Tailscale Serve `:8443` → Emulator API 36 →
Live-Akzeptanz **37/37 grün** (`bridge/tests/acceptance_live.py`). Details:
`docs/bridge-e2e.md`.
