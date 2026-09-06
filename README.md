# Orsik Relay

Inoffizieller, eigenständig entwickelter Android-Client für Hermes Agent. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; Orsik Relay ist ein sicherer Kotlin-/Jetpack-Compose-Thin-Client ohne offizielle Verbindung zu Nous Research.

## Projektstatus

MISSION 006A ist implementiert und lokal verifiziert. Die App unterstützt:

- sicheres Onboarding mit getrennt gespeicherten Profilmetadaten und Keystore-Credential,
- Sessionliste, Session-Erstellung und Nachrichtenhistorie,
- reine Textnachrichten mit live zusammengesetzten Hermes-SSE-Antworten,
- geschlossene Fehlerabbildung ohne automatische Wiederholung schreibender Requests,
- sichere Edge-to-Edge-Inhalte für Statusleisten, Display-Cutouts und Gestennavigation,
- ein Nous-inspiriertes, eigenständiges Material-3-Design,
- den persistenten Darstellungsmodus System, Hell oder Dunkel.

Noch nicht enthalten sind insbesondere Toolcards mit Payload, Approvals, Stop/Steer, Markdown, Bilder, Sprache, Benachrichtigungen und persistente Chatentwürfe. Die nächsten Funktionen folgen in kleinen, testbaren Tickets gemäß `internal plan`.

## Lokale Entwicklungsumgebung auf build-host

- Temurin JDK 17
- Android SDK Platform 36
- Android Build Tools 36.1.0
- Android Platform Tools
- Android Emulator mit AVD `Hermes_API_36`
- Gradle 9.7.1 (nur für den initialen Bootstrap des Wrappers)
- Hermes-Profil `dev-profile` ohne Cloud-Fallback

Prüfung:

```bash
scripts/verify-environment.sh
```

## Entwicklung

Maßgeblich für alle Builds und Tests ist der im Repository versionierte
**Gradle Wrapper 8.14.5** (`./gradlew`). Der Version Catalog liegt in
`gradle/libs.versions.toml` (aktuell AGP 8.13.2, Kotlin 2.2.10, Compose BOM 2025.08.00,
API 36, minSdk 26).

```bash
./gradlew test                       # Unit-Tests (JVM)
./gradlew lint                       # Lint
./gradlew assembleDebug              # Debug-APK
./gradlew connectedDebugAndroidTest  # Instrumentationstests (AVD nötig)
```

## Veröffentlichungsrichtung (später, noch nicht umgesetzt)

Die App soll möglicherweise auf GitHub und im Google Play Store erscheinen. Davon
bestehen **aktuell keine** Release-Komponenten; es werden keine ungeprüften
Release-Versprechen, Signing-Details oder Lizenzannahmen getroffen.

- CI, Wrapper-Validation, Release-Build und AAB-Erstellung werden erst zu einem
  späteren Release-Ticket aufgebaut.
- Der Signing-Key (keystore) kommt **niemals** ins Repository; er bleibt außerhalb
  (z. B. CI-Secrets bzw. lokale, nicht versionierte Ablage).
- Vor einem Play-Store-Release sind erforderlich: Datenschutztext, Support- und
  Updatekonzept sowie vollständige Store-Metadaten.
- Die Lizenz ist noch nicht entschieden; es wird bewusst keine Lizenz angenommen
  oder ausgeschrieben.

Qwen wird bei Bedarf lokal gestartet. Wenn ein anderes Modell Port 8081 belegt, bricht der Starter ab, statt es ungefragt zu beenden.

## Verbindliche Dokumente

- `docs/first-slice-contract.md` – Vertrag für den ersten vertikalen Slice
- `docs/api-capability-matrix.md` – live bestätigte Hermes-Server-API-Fähigkeiten

## Sicherheitsgrenzen

- keine Secrets im Repository
- kein öffentlich erreichbarer Hermes-Port
- keine deaktivierte TLS-Prüfung
- keine automatische Approval-Bestätigung
- keine ungeprüften Qwen-Diffs oder erfundenen Testergebnisse
