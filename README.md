# Orsik Relay

Inoffizieller, eigenständig entwickelter Android-Client für Hermes Agent. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; Orsik Relay ist ein Kotlin-/Jetpack-Compose-Thin-Client ohne offizielle Verbindung zu Nous Research.

## Projektstatus – v0.1.0-alpha.4

Dieser Stand ist ein **privater technischer Alpha-Release für Tests**, nicht für den Produktiveinsatz. Enthalten sind:

- sicheres Onboarding per eingeschränktem, widerrufbarem Geräte-Credential; der Pairing-Code ist der empfohlene Weg,
- bestehendes API-Key-Onboarding als expliziter Kompatibilitätsweg,
- Android-Keystore-geschützte Credentials und getrennte, nichtgeheime Server-/Gerätemetadaten,
- Serververwaltung mit Auswahl, persistentem aktiven Server, Löschen sowie bestätigtem „Gerät trennen“,
- Sessionliste, Session-Erstellung und Nachrichtenhistorie,
- Markdown-Rendering für Assistant-Antworten mit Headings, Listen, Quotes, Inline Code und fenced Code Blocks mit Copy-Button,
- sichere Werkzeugaktivitätsanzeige ohne Befehle, Argumente, Pfade, URLs oder Toolausgaben (auch in der geladenen Historie),
- native Session-Chat-Approvals mit ausschließlich **Ablehnen** oder **einmal erlauben**,
- laufende Runs stoppen und per Steer-Nachricht steuern,
- persistente normale Chat-Entwürfe mit server- und sessiongetrennter Speicherung,
- geschlossene Fehlerabbildung ohne automatische Wiederholung schreibender Requests,
- kurzlebiger Foreground Service während aktiver Runs, damit das SSE im Android-Background aktiv bleibt, mit Approval- und Run-Completed-Benachrichtigungen,
- ein eigenständiges Material-3-Design mit persistentem Modus System, Hell oder Dunkel,

Geräte-Credentials sind auf `chat`, `runs` und `approvals` beschränkt. Sie gewähren keinen Zugriff auf Jobs, Artefakte, Memory, Cron oder Administration und können serverseitig sofort widerrufen werden.

## Hermes-Kompatibilität

Alpha 4 benötigt einen Hermes-Server mit dem versionierten Mobile-Vertrag für:

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

## Verifikation des Alpha-4-Stands

- 626 JVM-/Unit-Tests: 0 Fehler, 0 Errors, 0 Skips
- 206 Connected-/Compose-Tests auf `emulator-5554` (API 36): 0 Fehler, 0 Errors, 1 dokumentierter Skip (System-Benachrichtigungstest, benötigt deaktiviertes System-Notification-Setting)
- `lint`: 0 Errors
- `assembleDebug`, APK-Installation und Versionsprüfung (`versionName=0.1.0-alpha.4`, `versionCode=4`) erfolgreich
- E2E auf headed Emulator: Pairing, Chat, Markdown, Streaming, Tool-Activity (Live und Historie) und Approvals gegen einen lokalen Hermes-Serverstand
- keine echten Credentials im Repository

## Installation des privaten Alpha-Releases

1. Im privaten [GitHub-Releasebereich](https://github.com/exclude-barrier/Orsik-Relay/releases) `v0.1.0-alpha.4` öffnen.
2. Die APK `orsik-relay-v0.1.0-alpha.4-debug.apk` herunterladen.
3. Installation aus der verwendeten Datei-/Browser-App einmalig erlauben und die APK installieren.

Die APK ist mit dem Android-Debug-Key signiert. Sie ist ausschließlich zum manuellen Testen bestimmt und bildet noch keinen stabilen Update- oder Store-Kanal.

## Bekannte Grenzen

- Kein produktives Release-Signing, keine AAB und keine CI-basierte Release-Pipeline.
- Der erforderliche Hermes-Serververtrag (Mobile-v1) ist noch nicht in der öffentlichen Hermes-Version enthalten.
- Inline-Bilder, Sprache und Bot-Verwaltung sind noch nicht enthalten.
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

- Versionsschema: SemVer-Prereleases wie `v0.1.0-alpha.4`, später `v0.1.0-beta.1`.
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
