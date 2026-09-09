# Orsik Relay

Inoffizieller, eigenständig entwickelter Android-Client für Hermes Agent. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; Orsik Relay ist ein Kotlin-/Jetpack-Compose-Thin-Client ohne offizielle Verbindung zu Nous Research.

## Projektstatus – v0.1.0-alpha.2

Der aktuelle Alpha-Stand ist lokal und auf dem Emulator verifiziert. Enthalten sind:

- sicheres Onboarding mit getrennten Server-Metadaten und Android-Keystore-Credentials,
- Serververwaltung: gespeicherte Server auflisten, aktiv markieren, auswählen und nach Bestätigung löschen,
- persistente Auswahl des aktiven Servers mit sicherer Validierung beim Neustart und Bereinigung nach erfolgreicher Serverlöschung,
- Sessionliste, Session-Erstellung und Nachrichtenhistorie,
- reine Textnachrichten mit live zusammengesetzten Hermes-SSE-Antworten,
- laufende Runs stoppen und per Steer-Nachricht steuern,
- persistente normale Chat-Entwürfe mit profil- und sessiongetrennter Speicherung,
- geschlossene Fehlerabbildung ohne automatische Wiederholung schreibender Requests,
- ein eigenständiges Material-3-Design mit persistentem Modus System, Hell oder Dunkel,
- sichere Edge-to-Edge-Inhalte für Statusleisten, Display-Cutouts und Gestennavigation.

Der Stand wurde ohne Live-Server, echte Credentials oder Netzwerkmutation getestet. Noch nicht enthalten sind insbesondere Toolcards mit Payload, Approvals, Markdown/Code-Rendering, Bilder, Sprache, Benachrichtigungen sowie Bot-Verwaltung. Diese Funktionen folgen in kleinen, testbaren Tickets gemäß `internal plan`.

## Verifikation des Alpha-2-Stands

- 379 Unit-Tests: 0 Fehler, 0 Errors, 0 Skips
- 145 Connected-/Compose-Tests auf `Hermes_API_36`: 0 Fehler, 0 Errors, 0 Skips
- `lintDebug`, `assembleDebug`, Android-Test-Kompilation und `git diff --check` erfolgreich

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

Nach größeren, vollständig geprüften Funktionsständen erscheint ein nummeriertes [GitHub-Alpha-Release](https://github.com/exclude-barrier/hermes-android/releases). Alpha-Releases sind Vorabversionen für technische Tests und nicht für den Produktiveinsatz bestimmt.

- Das APK ist aktuell ein lokal mit Android-Debug-Key signierter Testbuild und muss manuell installiert werden.
- Versionsschema: SemVer-Prereleases wie `v0.1.0-alpha.2`, später `v0.1.0-beta.1`.
- CI, Wrapper-Validation, produktives Release-Signing und AAB-Erstellung folgen in einem separaten Release-Ticket.
- Der Signing-Key kommt niemals ins Repository.
- Vor einem Play-Store-Release sind Datenschutztext, Support- und Updatekonzept sowie vollständige Store-Metadaten erforderlich.

## Verbindliche Dokumente

- `docs/first-slice-contract.md` – Vertrag für den ersten vertikalen Slice
- `docs/api-capability-matrix.md` – live bestätigte Hermes-Server-API-Fähigkeiten

## Sicherheitsgrenzen

- keine Secrets im Repository
- kein öffentlich erreichbarer Hermes-Port
- keine deaktivierte TLS-Prüfung
- keine automatische Approval-Bestätigung
- keine ungeprüften Qwen-Diffs oder erfundenen Testergebnisse
