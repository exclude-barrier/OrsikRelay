# Hermes Android

Native Android-App als sichere Bedienoberfläche für den Hermes Agent auf dem Hermes-Server. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; die App wird ein Kotlin-/Jetpack-Compose-Thin-Client.

## Projektstatus

MISSION 001 (Compose-Fundament) ist abgeschlossen und real verifiziert: Die App ist der
erste lauffähige, native Compose-Slice mit Onboarding → Sessions-Navigation, Material-3-Theme,
Unit-Tests und einem Compose-Instrumentationstest. Details:
`docs/completed/mission-001-compose-foundation.md`.

Bewusst noch nicht enthalten: kein Netzwerk, kein Serverprofil, kein Keystore, keine Persistenz.
Produktiver Android-Code folgt in kleinen, testbaren Tickets gemäß
`internal plan`; das nächste Ticket wird erst nach Review/Commit vergeben.

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
