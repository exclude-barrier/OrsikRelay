# Android-Toolchain auf build-host

## Installierter Stand

Die Werkzeuge sind benutzerlokal installiert; es wurden keine Systempakete und keine sudo-Rechte benötigt.

> Hinweis: Die folgenden Pfade und Versionen sind ein **beispielhafter**
> Maschinenstand und nicht generell gültig. Maßgeblich für Builds ist
> ausschließlich der versionierte Gradle Wrapper (`./gradlew`, Gradle 9.7.0).
> `scripts/verify-environment.sh` leitet `$HOME` und das Repo-Verzeichnis selbst
> ab und prüft die unten genannten SDK-Pfade.

- Temurin JDK `17` (Pfadbeispiel: `~/.local/opt/temurin-17`; es genügt ein JDK 17 im `PATH`)
- Gradle (nur für den initialen Wrapper-Bootstrap; maßgeblich ist `./gradlew`)
- Android SDK: `~/Android/Sdk`
- Command-line Tools `12.0` (`source.properties` der installierten `cmdline-tools/latest`)
- Platform Tools `37.0.1`
- Android Platform `36` Revision 2
- Build Tools `36.1.0`
- Emulator `37.1.11`
- System Image: Android 36, Google APIs, x86_64
- AVD: `Hermes_API_36` auf Basis eines Pixel 8
- KVM: verfügbar und nutzbar

## Persistente Umgebung

- Fish: `~/.config/fish/conf.d/android-dev.fish`
- systemd/User-Umgebung: `~/.config/environment.d/60-android-dev.conf`
- Gradle: `~/.gradle/gradle.properties`
- Werkzeug-Symlinks: `~/.local/bin`
- projektspezifischer SDK-Pfad: `local.properties` (absichtlich nicht versioniert)

## Prüfung

```bash
scripts/verify-environment.sh
```

Für echte Android-Builds ist später der im Repository erzeugte Gradle Wrapper (`./gradlew`) maßgeblich. Die benutzerlokale Gradle-Installation dient nur dem initialen Bootstrap.
