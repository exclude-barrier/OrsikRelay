# Orsik Relay

Inoffizieller, eigenständig entwickelter Android-Client für Hermes Agent. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; Orsik Relay ist ein Kotlin-/Jetpack-Compose-Thin-Client ohne offizielle Verbindung zu Nous Research.

## Projektstatus – v0.1.0-alpha.6

Dieser Stand ist ein **privater technischer Alpha-Release für Tests**, nicht für den Produktiveinsatz. Enthalten sind:

- sicheres Onboarding per eingeschränktem, widerrufbarem Geräte-Credential; der Pairing-Code ist der empfohlene Weg,
- bestehendes API-Key-Onboarding als expliziter Kompatibilitätsweg,
- Android-Keystore-geschützte Credentials und getrennte, nichtgeheime Server-/Gerätemetadaten,
- Serververwaltung mit Auswahl, persistentem aktiven Server, Löschen sowie bestätigtem „Gerät trennen“,
- Sessionliste, Session-Erstellung und Nachrichtenhistorie,
- Markdown-Rendering für Assistant-Antworten mit Headings, Listen, Quotes, Inline Code und fenced Code Blocks mit Copy-Button,
- sichere Werkzeugaktivitätsanzeige ohne Befehle, Argumente, Pfade, URLs oder Toolausgaben (auch in der geladenen Historie),
- native Session-Chat-Approvals mit ausschließlich **Ablehnen** oder **einmal erlauben**,
- laufende Hermes-Runs überleben Netzwerk-/SSE-Unterbrechungen: automatischer Reconnect auf dieselbe `run_id` mit `after_seq`-Replay, ohne doppelten Prompt und ohne zweiten Run,
- laufende Runs und offene Approvals überleben eine Activity-Recreation (Rotation); veraltete Approval-Dialoge werden nicht wiederbelebt,
- laufende Runs stoppen und per Steer-Nachricht steuern,
- Bilder senden über den Android System Photo Picker (JPEG, PNG, WEBP, max. 1 Bild pro Turn, bis 5 MiB, ohne Storage-Berechtigung): Text + Bild oder Bild-only als multimodale Hermes-Content-Parts, inline und ohne Upload-Endpoint,
- persistente normale Chat-Entwürfe mit server- und sessiongetrennter Speicherung,
- geschlossene Fehlerabbildung ohne automatische Wiederholung schreibender Requests,
- kurzlebiger Foreground Service während aktiver Runs, damit das SSE im Android-Background aktiv bleibt, mit Approval- und Run-Completed-Benachrichtigungen,
- ein eigenständiges Material-3-Design mit persistentem Modus System, Hell oder Dunkel,
- stabilerer IME-/Keyboard-Support inklusive adaptivem Compact Composer in Landscape (Composer und Stop-/Steer-Controls bleiben erreichbar, Anhang/Entfernen/Senden behalten 48-dp-Touch-Targets),

Geräte-Credentials sind auf `chat`, `runs` und `approvals` beschränkt. Sie gewähren keinen Zugriff auf Jobs, Artefakte, Memory, Cron oder Administration und können serverseitig sofort widerrufen werden.

## Hermes-Kompatibilität

Orsik Relay spricht den versionierten **Mobile-v1-Vertrag** und verbindet sich mit einer **frischen, unveränderten offiziellen Hermes-Installation** über die **Orsik Mobile Bridge** (Sidecar, P13). Der Hermes-Core bleibt unpatched; die Bridge läuft als eigener Loopback-Prozess vor dem offiziellen `api_server`.

Die Bridge **ergänzt** die Funktionen, die der offizielle Core (noch) nicht bereitstellt:

- `device_credentials` Protokoll v1 (`POST /v1/devices/pair`, `POST /v1/devices/self/revoke`) samt Device-Scope-Allowlist,
- `protocol_version`-Negotiation und die Mobile-Capability `session_chat_interactive_approvals`,
- Session-Run-Re-Attach mit `after_seq`-Replay,
- persistente Geräteverwaltung.

Im offiziellen Core **vorhanden** und von der Bridge durchgereicht bzw. auf den Mobile-v1-Vertrag verengt:

- Session-CRUD, Nachrichten und Session-Chat/Stream (die Bridge reicht den Chat-Body unverändert durch; Body-Limit 10 MB wie der offizielle `api_server`),
- interaktive Session-Approvals (`approval.request`); die Bridge bewirbt den v1-Vertrag und verengt die Entscheidungen auf `once`/`deny`,
- `POST /v1/runs/{run_id}/approval` und `GET /v1/runs/{run_id}`.

Der Client benötigt damit keinen privaten Hermes-Fork. Der Fresh-Install-Pfad (unveränderter offizieller Core + Bridge) ist auf build-host live verifiziert; Installation, HTTPS und Betrieb stehen in `docs/bridge-installation.md`, die Live-Ergebnisse in `docs/bridge-e2e.md` und `docs/hermes-fresh-compat.md`.

**Verbleibende reale Grenze:** Die device- und reconnect-spezifischen Mobile-v1-Erweiterungen (Device-Pairing/-Credentials/-Scope, `protocol_version`-Negotiation, `after_seq`-Re-Attach) sind **noch nicht Teil der öffentlichen Hermes-Version**; sie liefert derzeit die separate Bridge. Die Veröffentlichung des Serververtrags im offiziellen Core (Upstream) bleibt Voraussetzung für einen allgemein nutzbaren Release ohne Bridge.

## Pairing mit der Orsik Mobile Bridge

Voraussetzung ist eine installierte Bridge vor einer unveränderten offiziellen Hermes-Installation (Installation, privates HTTPS, Update und Rollback: `docs/bridge-installation.md`).

Auf dem Server erzeugt der Operator den einmaligen Pairing-Code **lokal** über die Bridge-CLI:

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin pairing-code \
  --base-url https://<host>
```

Der Befehl gibt einen Code (24 Zeichen, single-use, 300 Sekunden gültig) und einen Deep-Link `orsik://pair?…` aus; ein ASCII-QR-Code, sofern `qrencode` installiert ist.

In Orsik Relay:

1. **Server hinzufügen** öffnen.
2. Name und privat erreichbare HTTPS-Basis-URL der Bridge eingeben.
3. **Pairing-Code** auswählen und den angezeigten Code eingeben.
4. Die App prüft Verbindung, Authentifizierung und den exakten Capability-Vertrag, bevor lokal etwas gespeichert wird.

Geräte auflisten oder sofort widerrufen (ebenfalls lokal, kein HTTP-Admin):

```sh
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin list-devices
BRIDGE_ENV_FILE=$BRIDGE_HOME/bridge.env \
  $BRIDGE_HOME/venv/bin/orsik-mobile-bridge admin revoke-device <device_id>
```

Alternativ kann das gekoppelte Gerät in der App über **Gerät trennen** sein eigenes Credential widerrufen.

## Verifikation des Alpha-6-Stands

Belege: `docs/bridge-e2e.md` (P13, Fresh-Install-Pfad) und die App-Regressionsläufe.

App-Suite, Builds und Version (real auf build-host):

- 823 JVM-/Unit-Tests: 0 Fehler, 0 Errors, 0 Skips
- 247 Connected-/Compose-Tests auf `emulator-5554` (API 36): 0 Fehler, 0 Errors, 1 dokumentierter Skip (`NotificationManagerInstrumentedTest.systemNotificationsDisabled_viaAppOps_postsNothingAndDoesNotCrash`); zwei vorangegangene volle Läufe trafen den bekannten IME-/Landscape-Flake (`ChatLandscapeComposerUiTest`), die Klasse isoliert 12/12 grün (siehe `docs/connected-test-stability.md`)
- `lint`, `assembleDebug` und `compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL
- `versionName=0.1.0-alpha.6`, `versionCode=6`
- keine echten Credentials im Repository

Fresh-Install-Pfad (P13, `docs/bridge-e2e.md`):

- Bridge-Live-Akzeptanz gegen einen frischen offiziellen Hermes-Core (`NousResearch/hermes-agent`): 37/37 grün
- realer Android-Live-Test `LiveBridgeE2ETest` (Pairing und Capability-Negotiation über HTTPS): 1/1 grün

Früherer App-E2E gegen den privaten lokalen Mobile-v1-Serverstand (Alpha-5-Stand, **nicht** der Fresh-Install-Pfad): Text-Chat, Bild-Turn (genau ein Image-Part), Reconnect auf dieselbe `run_id` mit `after_seq` ohne zweiten POST, Activity-Recreation mit gebundenem Run, Approval `once` und `deny`, Compact-Landscape mit erreichbaren Stop-/Steer-Controls.

Auf dem Fresh-Install-Pfad **nicht verifiziert** (`docs/bridge-e2e.md` §5): echter Assistant-Inhalt (kein Provider konfiguriert), positiver interaktiver Approval `once`/`deny` mit echtem Tool-Aufruf, UI-Automation der Onboarding-/Lifecycle-/Bild-Flows sowie ein Bild-Turn Ende-zu-Ende gegen den offiziellen Core (ob der Core multimodale Content-Parts verarbeitet, ist im Repo nicht belegt).

## Installation des privaten Alpha-Releases

1. Im [GitHub-Releasebereich](https://github.com/exclude-barrier/OrsikRelay/releases) `v0.1.0-alpha.6` öffnen.
2. Die APK `orsik-relay-v0.1.0-alpha.6-debug.apk` herunterladen.
3. Installation aus der verwendeten Datei-/Browser-App einmalig erlauben und die APK installieren.

Die APK ist mit dem Android-Debug-Key signiert. Sie ist ausschließlich zum manuellen Testen bestimmt und bildet noch keinen stabilen Update- oder Store-Kanal.

## Bekannte Grenzen

- Signing-Konfiguration über Umgebungsvariablen vorhanden (docs/release.md); produktiver Key und Pipeline offen.
- Die device- und reconnect-spezifischen Mobile-v1-Erweiterungen (Device-Pairing/-Credentials/-Scope, `protocol_version`-Negotiation, `after_seq`-Re-Attach) sind noch nicht in der öffentlichen Hermes-Version enthalten; sie liefert die Orsik Mobile Bridge (`docs/bridge-installation.md`). Bis zu einem Upstream-Beitrag ist die Bridge der erforderliche Serverbaustein.
- Keine garantierte Per-Device-Isolation: Der offizielle Core kennt keine Ressourcen-Ownership pro Gerät; gepaarte Geräte handeln als Delegierte des einen Owners über alle Sessions/Runs (`docs/hermes-fresh-compat.md` §7).
- PDF-, Text- und allgemeine Dateianhänge werden nicht unterstützt, und es wird maximal ein Bild pro Turn übertragen.
- Vision-Support hängt vom verwendeten Hermes-Modell ab.
- Sprache und Bot-Verwaltung sind noch nicht enthalten.
- Nach einer Activity-Recreation kann das Laden der Historie einmalig fehlschlagen; „Erneut versuchen“ lädt sie nach, der laufende Run bleibt dabei gebunden.
- Im Compact-Modus (sehr flaches Fenster, z. B. Landscape mit offener Tastatur) bleibt die Nachrichtenliste ausgeblendet, bis wieder genug Höhe verfügbar ist; Header, Eingabe und die Stop-/Steer-Controls bleiben sichtbar.
- Die Benutzeroberfläche wird nach realer Nutzung schrittweise weiter angepasst.

## Lokale Entwicklungsumgebung auf build-host

- Temurin JDK 17
- Android SDK Platform 36
- Android Build Tools 36.1.0
- Android Platform Tools
- Android Emulator mit AVD `Hermes_API_36`
- Gradle 9.7.1 (nur für den initialen Wrapper-Bootstrap; maßgeblich ist `./gradlew`)

Prüfung:

```bash
scripts/verify-environment.sh
```

## Entwicklung

Maßgeblich für alle Builds und Tests ist der versionierte **Gradle Wrapper 9.7.0** (`./gradlew`). Der Version Catalog liegt in `gradle/libs.versions.toml` (AGP 9.3.1, Kotlin 2.4.20).

```bash
./gradlew test                       # Unit-Tests (JVM)
./gradlew lint                       # Lint
./gradlew assembleDebug              # Debug-APK
./gradlew connectedDebugAndroidTest  # Instrumentationstests (AVD nötig)
```

## Alpha-Releases

Nach größeren, vollständig geprüften Funktionsständen erscheint ein nummeriertes [GitHub-Alpha-Release](https://github.com/exclude-barrier/OrsikRelay/releases). Alpha-Releases sind Vorabversionen für technische Tests und nicht für den Produktiveinsatz bestimmt.

- Versionsschema: SemVer-Prereleases wie `v0.1.0-alpha.6`, später `v0.1.0-beta.1`.
- CI (Unit-Tests, Lint, `assembleDebug` und Instrumentationstests auf API 36) liegt in `.github/workflows/android.yml`; die Gradle-Wrapper-Validation ist Teil des Workflows.
- Signing-Konfiguration über Umgebungsvariablen vorhanden (docs/release.md); produktiver Key und Pipeline offen.
- Der Signing-Key kommt niemals ins Repository.
- Lizenz: **Apache-2.0** (siehe `LICENSE`). Vor einem öffentlichen bzw. Play-Store-Release sind zusätzlich Datenschutztext, Support- und Updatekonzept sowie vollständige Store-Metadaten erforderlich.

## Verbindliche Dokumente

- `docs/architecture.md` – implementierte Architektur (Ist) und Abweichungen
- `docs/first-slice-contract.md` – Vertrag für den ersten vertikalen Slice
- `docs/api-capability-matrix.md` – bestätigte Server-API-Fähigkeiten
- `docs/mobile-wire-contract.md` – versionierter Mobile-v1-Vertrag
- `docs/bridge-installation.md` – Installation und Betrieb der Orsik Mobile Bridge (Fresh-Install-Pfad)
- `docs/bridge-e2e.md` – Live-Nachweis gegen einen frischen offiziellen Hermes
- `docs/toolchain.md` – lokale Toolchain (beispielhafter Stand)

## Sicherheitsgrenzen

- keine Secrets im Repository
- kein öffentlich erreichbarer Hermes-Port
- keine deaktivierte TLS-Prüfung
- keine automatische Approval-Bestätigung
- keine ungeprüften Agent-Diffs oder erfundenen Testergebnisse
