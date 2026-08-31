# Android-Toolchain auf build-host

## Installierter Stand

Die Werkzeuge sind benutzerlokal installiert; es wurden keine Systempakete und keine sudo-Rechte benötigt.

- Temurin JDK `17.0.20.1+1`: `~/.local/opt/temurin-17`
- Gradle `9.7.1`: `~/.local/opt/gradle-9.7.1`
- Android SDK: `~/Android/Sdk`
- Command-line Tools `23.0`
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
