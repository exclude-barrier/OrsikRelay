# Hermes Android

Native Android-App als sichere Bedienoberfläche für den Hermes Agent auf dem Hermes-Server. Hermes und seine privilegierten Werkzeuge bleiben serverseitig; die App wird ein Kotlin-/Jetpack-Compose-Thin-Client.

## Projektstatus

Die Planung, das Qwen-Roleplay und die lokale Android-Entwicklungsumgebung sind vorbereitet. Produktiver Android-Code wird in kleinen, testbaren Tickets erstellt.

## Lokale Entwicklungsumgebung auf build-host

- Temurin JDK 17
- Android SDK Platform 36
- Android Build Tools 36.1.0
- Android Platform Tools
- Android Emulator mit AVD `Hermes_API_36`
- Gradle 9.7.1 für die Erzeugung des späteren Wrappers
- Hermes-Profil `dev-profile` ohne Cloud-Fallback

Prüfung:

```bash
scripts/verify-environment.sh
```

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
