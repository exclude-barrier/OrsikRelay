# Orsik Relay

Inoffizieller, eigenständig entwickelter Android-Client für Hermes Agent. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; Orsik Relay ist ein Kotlin-/Jetpack-Compose-Thin-Client ohne offizielle Verbindung zu Nous Research.

## Projektstatus – v0.1.0-alpha.3

Dieser Stand ist ein **privater technischer Alpha-Release für Tests**, nicht für den Produktiveinsatz. Enthalten sind:

- sicheres Onboarding per eingeschränktem, widerrufbarem Geräte-Credential; der Pairing-Code ist der empfohlene Weg,
- bestehendes API-Key-Onboarding als expliziter Kompatibilitätsweg,
- Android-Keystore-geschützte Credentials und getrennte, nichtgeheime Server-/Gerätemetadaten,
- Serververwaltung mit Auswahl, persistentem aktiven Server, Löschen sowie bestätigtem „Gerät trennen“,
- Sessionliste, Session-Erstellung und Nachrichtenhistorie,
- reine Textnachrichten mit live zusammengesetzten Hermes-SSE-Antworten,
- sichere Werkzeugaktivitätsanzeige ohne Befehle, Argumente, Pfade, URLs oder Toolausgaben,
- native Session-Chat-Approvals mit ausschließlich **Ablehnen** oder **einmal erlauben**,
- laufende Runs stoppen und per Steer-Nachricht steuern,
- persistente normale Chat-Entwürfe mit server- und sessiongetrennter Speicherung,
- geschlossene Fehlerabbildung ohne automatische Wiederholung schreibender Requests,
- ein eigenständiges Material-3-Design mit persistentem Modus System, Hell oder Dunkel,
- sichere Edge-to-Edge-Inhalte für Statusleisten, Display-Cutouts und Gestennavigation.

Geräte-Credentials sind auf `chat`, `runs` und `approvals` beschränkt. Sie gewähren keinen Zugriff auf Jobs, Artefakte, Memory, Cron oder Administration und können serverseitig sofort widerrufen werden.

## Hermes-Kompatibilität

Alpha 3 benötigt einen Hermes-Server mit dem versionierten Mobile-Vertrag für:

- `device_credentials` Protokoll v1 (`POST /v1/devices/pair`, `POST /v1/devices/self/revoke`),
- interaktive Session-Chat-Approvals mit exakt korrelierter `request_id` und den Entscheidungen `once`/`deny`.

Der dafür entwickelte Hermes-Serverstand wurde separat auf einer isolierten frischen Installation per E2E geprüft, ist aber **noch nicht in der öffentlichen Hermes-Version verfügbar**. Eine unveränderte offizielle Hermes-Installation ist deshalb derzeit nicht kompatibel. Dieser private App-Release ist für den passenden Entwicklungsstand bestimmt; die Veröffentlichung des Serververtrags ist Voraussetzung für einen allgemein nutzbaren Release.

## Pairing mit einem kompatiblen Server

Auf dem Hermes-Server erzeugt der Operator einen einmaligen, 120 Sekunden gültigen Code:

```bash
hermes devices pair --label "Mein Android-Gerät"
```

In Orsik Relay:

1. **Server hinzufügen** öffnen.
2. Name und öffentlich bzw. privat erreichbare HTTPS-Basis-URL des kompatiblen Gateways eingeben.
3. **Pairing-Code** auswählen und den angezeigten Code eingeben.
4. Die App prüft Verbindung, Authentifizierung und den exakten Capability-Vertrag, bevor lokal etwas gespeichert wird.

Geräte verwalten oder sofort widerrufen:

```bash
hermes devices list
hermes devices revoke <device-id> --yes
hermes devices revoke-all --yes
```

Alternativ kann das gekoppelte Gerät in der App über **Gerät trennen** sein eigenes Credential widerrufen.

## Verifikation des Alpha-3-Stands

- 508 JVM-/Unit-Tests: 0 Fehler, 0 Errors, 0 Skips
- 185 Connected-/Compose-Tests auf `Hermes_API_36`: 0 Fehler, 0 Errors, 0 Skips
- `lintDebug`: 0 Error/Fatal und keine Findings in geänderten Dateien
- `assembleDebug`, APK-Installation, Kaltstart und `git diff --check` erfolgreich
- finales unabhängiges Sicherheitsreview: `APPROVE`, keine Findings
- keine echten Credentials, keine Produktionsmutation und kein Gateway-Restart für die Tests

## Installation des privaten Alpha-Releases

1. Im privaten [GitHub-Releasebereich](https://github.com/exclude-barrier/Orsik-Relay/releases) `v0.1.0-alpha.3` öffnen.
2. Die APK `orsik-relay-v0.1.0-alpha.3-debug.apk` herunterladen.
3. Installation aus der verwendeten Datei-/Browser-App einmalig erlauben und die APK installieren.

Die APK ist mit dem Android-Debug-Key signiert. Sie ist ausschließlich zum manuellen Testen bestimmt und bildet noch keinen stabilen Update- oder Store-Kanal.

## Bekannte Grenzen

- Der erforderliche Hermes-Serververtrag ist noch nicht öffentlich/upstream verfügbar.
- Kein reales End-to-End-Pairing mit einem physischen Android-Gerät über den späteren TLS-/Proxy-Pfad.
- Kein produktives Release-Signing, keine AAB und keine CI-basierte Release-Pipeline.
- Markdown-/Code-Rendering, Inline-Bilder, Sprache, Benachrichtigungen und Bot-Verwaltung sind noch nicht enthalten.
- Die Benutzeroberfläche wird nach realer Nutzung schrittweise weiter angepasst.

## Lokale Entwicklungsumgebung auf build-host

- Temurin JDK 17
- Android SDK Platform 36
- Android Build Tools 36.1.0
- Android Platform Tools
- Android Emulator mit AVD `Hermes_API_36`
- Gradle 9.7.1 nur für den initialen Bootstrap des Wrappers
- Hermes-Profil `dev-profile` ohne Cloud-Fallback

Prüfung:

```bash
scripts/verify-environment.sh
```

## Entwicklung

Maßgeblich für alle Builds und Tests ist der versionierte **Gradle Wrapper 8.14.5** (`./gradlew`). Der Version Catalog liegt in `gradle/libs.versions.toml`.

```bash
./gradlew test                       # Unit-Tests (JVM)
./gradlew lint                       # Lint
./gradlew assembleDebug              # Debug-APK
./gradlew connectedDebugAndroidTest  # Instrumentationstests (AVD nötig)
```

## Alpha-Releases

Nach größeren, vollständig geprüften Funktionsständen erscheint ein nummeriertes [GitHub-Alpha-Release](https://github.com/exclude-barrier/Orsik-Relay/releases). Alpha-Releases sind Vorabversionen für technische Tests und nicht für den Produktiveinsatz bestimmt.

- Versionsschema: SemVer-Prereleases wie `v0.1.0-alpha.3`, später `v0.1.0-beta.1`.
- CI, Wrapper-Validation, produktives Release-Signing und AAB-Erstellung folgen in einem separaten Release-Ticket.
- Der Signing-Key kommt niemals ins Repository.
- Vor einem öffentlichen bzw. Play-Store-Release sind Lizenz, Datenschutztext, Support- und Updatekonzept sowie vollständige Store-Metadaten erforderlich.

## Verbindliche Dokumente

- `docs/first-slice-contract.md` – Vertrag für den ersten vertikalen Slice
- `docs/api-capability-matrix.md` – bestätigte Server-API-Fähigkeiten

## Sicherheitsgrenzen

- keine Secrets im Repository
- kein öffentlich erreichbarer Hermes-Port
- keine deaktivierte TLS-Prüfung
- keine automatische Approval-Bestätigung
- keine ungeprüften Agent-Diffs oder erfundenen Testergebnisse
