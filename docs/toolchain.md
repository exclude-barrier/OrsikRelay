# Android Toolchain

## Required Tools

The only authoritative source for all builds is the versioned Gradle wrapper
(`./gradlew`, Gradle 9.8.1); a separate Gradle installation is not needed.

- JDK `17` (e.g. Temurin), in the `PATH` or via `JAVA_HOME`
- Android SDK (default location `~/Android/Sdk`, otherwise set `ANDROID_HOME`) with
  - Android platform `37` (`platforms;android-37.0`; target platform remains `targetSdk 36`)
  - Build Tools `36.1.0`
  - Platform Tools
- for instrumentation tests only: Android emulator, an API 36 system image
  (e.g. Google APIs) and an AVD, or a connected device; on Linux
  KVM should be available
- the project-specific SDK path is in `local.properties` (intentionally not
  versioned; Android Studio creates the file, alternatively `sdk.dir=…`)

## Check

```bash
scripts/verify-environment.sh
```

The script respects existing `JAVA_HOME`/`ANDROID_HOME` values, checks Java 17,
the SDK packages and the Gradle wrapper, and reports emulator components only as a
note. Output per check `OK`/`WARN`/`FAIL`, at the end `ENVIRONMENT-OK`
(exit 0) or `ENVIRONMENT-FAIL (<n>)` (exit 1).

## Coverage (Report, No Gate)

```bash
./gradlew :app:createDebugUnitTestCoverageReport
```

Generates the JaCoCo HTML/XML report of the JVM unit tests under
`app/build/reports/coverage/test/debug/` (`report.xml` for machine evaluation).
The `debug` build type enables `enableUnitTestCoverage = true` for this (AGP's own
JaCoCo). The step is a pure report and **not a threshold gate**; CI uploads
it via the existing `app/build/reports/**` upload. Instrumentation
coverage (`enableAndroidTestCoverage`) is deliberately **not** enabled.

Baseline (`:app:testDebugUnitTest` + coverage, 823 tests, 0 failures,
as of 2026-10-16, base `main@f1311d1`):

| Area | LINE | BRANCH |
| --- | --- | --- |
| Overall | 52.8 % | 55.3 % |
| `io.github.excludebarrier.orsikrelay.domain.run` | 94.6 % | 84.4 % |
| `io.github.excludebarrier.orsikrelay.network` | 88.4 % | 83.6 % |
| `io.github.excludebarrier.orsikrelay.data.message` | 95.4 % | 86.0 % |

Thresholds are introduced only after a risk-based assessment, not
automatically based on this baseline.

## Bridge Coverage (Report, No Gate)

The Python bridge (`bridge/`) measures its test coverage with `coverage`. The
version is pinned exactly (PEP-735 dependency group `dev` in
`bridge/pyproject.toml`, `uv.lock`): `coverage==7.16.2`. It is a pure
development dependency: `install.sh` exports with `--no-dev`
(`uv export --frozen --no-dev --no-emit-project`), so installing the bridge
remains unaffected.

Configuration in `bridge/pyproject.toml`:

- `[tool.coverage.run]`: `source = ["orsik_mobile_bridge"]`, `branch = true`
- `[tool.coverage.report]`: `show_missing = true`, `skip_covered = false`
- no `fail_under`: the report is deliberately not a gate.

Commands (run from `bridge/`):

```bash
uv lock --check
uv sync --locked          # installs the dev group by default
uv run --locked coverage run -m unittest discover -s tests -v
uv run --locked coverage report
uv run --locked coverage xml -o dist/coverage.xml
```

Measured baseline (date 2026-10-16, base `main@f1311d1`; 137 tests,
`branch = true`):

| Module | Line | Branch | Combined |
| --- | --- | --- | --- |
| `core.py` | 99% (186/187) | 96% (54/56) | 99% |
| `server.py` | 81% (334/411) | 69% (95/138) | 78% |
| `store.py` | 79% (154/194) | 90% (27/30) | 81% |
| `runhub.py` | 91% (169/185) | 74% (49/66) | 87% |
| `sse.py` | 99% (90/91) | 84% (27/32) | 95% |
| `pairing.py` | 85% (17/20) | 75% (3/4) | 83% |
| **Total** | **87% (951/1093)** | **78% (255/328)** | **85%** |

`Combined` is the mixed value reported by `coverage`; `Line` and `Branch`
are derived from the counters. The CI job (`Bridge CI`) replaces the
unit test step with `coverage run`, prints the report and uploads
`dist/coverage.xml` as an artifact.
