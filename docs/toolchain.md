# Android-Toolchain

## Benötigte Werkzeuge

Maßgeblich für alle Builds ist ausschließlich der versionierte Gradle Wrapper
(`./gradlew`, Gradle 9.7.0); eine separate Gradle-Installation ist nicht nötig.

- JDK `17` (z. B. Temurin), im `PATH` oder über `JAVA_HOME`
- Android SDK (Standardort `~/Android/Sdk`, sonst `ANDROID_HOME` setzen) mit
  - Android Platform `36`
  - Build Tools `36.1.0`
  - Platform Tools
- nur für Instrumentationstests: Android Emulator, ein API-36-System-Image
  (z. B. Google APIs) und ein AVD, oder ein angeschlossenes Gerät; unter Linux
  sollte KVM verfügbar sein
- der projektspezifische SDK-Pfad steht in `local.properties` (absichtlich nicht
  versioniert; Android Studio legt die Datei an, alternativ `sdk.dir=…`)

## Prüfung

```bash
scripts/verify-environment.sh
```

Das Skript respektiert vorhandene `JAVA_HOME`/`ANDROID_HOME`-Werte, prüft Java 17,
die SDK-Pakete und den Gradle Wrapper und meldet Emulator-Bestandteile nur als
Hinweis. Ausgabe je Prüfung `OK`/`WARN`/`FAIL`, am Ende `ENVIRONMENT-OK`
(Exit 0) oder `ENVIRONMENT-FAIL (<n>)` (Exit 1).

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
