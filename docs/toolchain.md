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

## Coverage (Report, kein Gate)

```bash
./gradlew :app:createDebugUnitTestCoverageReport
```

Erzeugt den JaCoCo-HTML/XML-Report der JVM-Unit-Tests unter
`app/build/reports/coverage/test/debug/` (`report.xml` für Maschinenauswertung).
Der `debug`-Build-Typ aktiviert dafür `enableUnitTestCoverage = true` (AGP-eigenes
JaCoCo). Der Schritt ist reiner Report und **kein Schwellwert-Gate**; die CI lädt
ihn über den bestehenden `app/build/reports/**`-Upload mit hoch. Instrumentation-
Coverage (`enableAndroidTestCoverage`) ist bewusst **nicht** aktiviert.

Baseline (`:app:testDebugUnitTest` + Coverage, 823 Tests, 0 Fehler,
Stand 2026-10-16, Basis `main@f1311d1`):

| Bereich | LINE | BRANCH |
| --- | --- | --- |
| Gesamt | 52,8 % | 55,3 % |
| `io.github.excludebarrier.orsikrelay.domain.run` | 94,6 % | 84,4 % |
| `io.github.excludebarrier.orsikrelay.network` | 88,4 % | 83,6 % |
| `io.github.excludebarrier.orsikrelay.data.message` | 95,4 % | 86,0 % |

Schwellwerte werden erst nach einer risikobasierten Bewertung eingeführt, nicht
auf Basis dieser Baseline automatisch.
