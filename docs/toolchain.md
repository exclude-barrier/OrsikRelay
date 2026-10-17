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

## Bridge-Coverage (Report, kein Gate)

Die Python-Bridge (`bridge/`) misst ihre Testabdeckung mit `coverage`. Die
Version ist exakt gepinnt (PEP-735-Dependency-Group `dev` in
`bridge/pyproject.toml`, `uv.lock`): `coverage==7.16.2`. Es ist eine reine
Entwicklungsabhängigkeit: `install.sh` exportiert mit `--no-dev`
(`uv export --frozen --no-dev --no-emit-project`), die Installation der Bridge
bleibt daher unberührt.

Konfiguration in `bridge/pyproject.toml`:

- `[tool.coverage.run]`: `source = ["orsik_mobile_bridge"]`, `branch = true`
- `[tool.coverage.report]`: `show_missing = true`, `skip_covered = false`
- kein `fail_under`: der Report ist bewusst kein Gate.

Befehle (aus `bridge/` ausführen):

```bash
uv lock --check
uv sync --locked          # installiert die dev-Gruppe standardmäßig mit
uv run --locked coverage run -m unittest discover -s tests -v
uv run --locked coverage report
uv run --locked coverage xml -o dist/coverage.xml
```

Gemessene Baseline (Datum 2026-10-16, Basis `main@f1311d1`; 137 Tests,
`branch = true`):

| Modul | Line | Branch | Combined |
| --- | --- | --- | --- |
| `core.py` | 99% (186/187) | 96% (54/56) | 99% |
| `server.py` | 81% (334/411) | 69% (95/138) | 78% |
| `store.py` | 79% (154/194) | 90% (27/30) | 81% |
| `runhub.py` | 91% (169/185) | 74% (49/66) | 87% |
| `sse.py` | 99% (90/91) | 84% (27/32) | 95% |
| `pairing.py` | 85% (17/20) | 75% (3/4) | 83% |
| **Gesamt** | **87% (951/1093)** | **78% (255/328)** | **85%** |

`Combined` ist der von `coverage` gemeldete Mischwert; `Line` und `Branch`
sind aus den Zählern abgeleitet. Der CI-Job (`Bridge CI`) ersetzt den
Unit-Test-Schritt durch `coverage run`, gibt den Report aus und lädt
`dist/coverage.xml` als Artefakt hoch.
