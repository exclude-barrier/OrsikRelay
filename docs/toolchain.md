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

## Bridge (Rust)

The bridge is the Rust workspace `bridge-rs/` (toolchain pinned in
`bridge-rs/rust-toolchain.toml`, dependencies in `bridge-rs/Cargo.lock`).
Every build uses `--locked`.

```bash
cd bridge-rs
cargo fmt --all --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked -p orsik-mobile-bridge          # unit tests
cargo build --locked -p orsik-mobile-bridge
cargo test --locked -p orsik-bridge-contract        # black-box contract suite
bash tests/install-scripts.sh target/debug/orsik-mobile-bridge
```

`cargo audit` runs nightly (`.github/workflows/bridge-audit.yml`).
