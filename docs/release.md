# Release Signing, AAB, SBOM and CI Release

## Purpose

This document describes how productive release signing is managed,
how a signed release artifact (AAB and APK) is built and verified locally,
and what limits the CI release job, the SBOM and the provenance have.

Release signing is deliberately **not** stored in the repository: The signature
is fed exclusively from four environment variables, so that neither
keystore file, passwords nor alias ever land in Git.

The debug variant remains unchanged and continues to use the Android debug key.

## The Four Environment Variables

All four are read via `providers.environmentVariable(...)`. If one is missing,
the release build remains unsigned (no error), as long as **none** is set.

| Variable | Meaning |
| --- | --- |
| `ORSIK_RELEASE_STORE_FILE` | Path to the keystore file (`.jks`) **outside** the repository |
| `ORSIK_RELEASE_STORE_PASSWORD` | Password of the keystore |
| `ORSIK_RELEASE_KEY_ALIAS` | Alias of the signing key |
| `ORSIK_RELEASE_KEY_PASSWORD` | Password of the signing key (for PKCS12 the same as the store password) |

Rules in `app/build.gradle.kts` (fail-closed, unchanged):

- **None** of the four variables set: The release build succeeds and remains
  unsigned.
- **Only some** set: The build aborts with a fixed error message that
  names the expected variable names but outputs **no values**.
- **Keystore inside the repository** (canonically checked path below
  `rootDir`): The build aborts with `Release keystore must live outside the
  repository`, without outputting the path.
- **Keystore file does not exist**: The build aborts, likewise without a
  path value in the message.

Passwords are set only as environment variables in the shell and never
output, logged or written to files.

## Productive Upload/Release Key (Owner Procedure)

The productive key is generated **only by the human** on a trusted
machine. The tool is `scripts/release-keystore.sh`; it is not
intended for CI.

```bash
# Default target ~/.config/orsik-release/release.jks, alias orsik-release
scripts/release-keystore.sh

# Alternatively with explicit target and alias
scripts/release-keystore.sh \
  --path "$HOME/.config/orsik-release/release.jks" \
  --alias orsik-release
```

Properties of the script:

- `keytool -genkeypair`, RSA 4096 bit, PKCS12, validity **10950 days**
  (30 years).
- **No** password arguments. `keytool` prompts for the keystore password and the
  confirmation interactively; at the end it asks for the password again to read the
  SHA-256 certificate fingerprint.
- Aborts if the target is inside the repository or already exists.
- Creates the parent directory with mode `0700` and the keystore file with
  mode `0600`.
- At the end outputs only path, alias and SHA-256 fingerprint. The password is
  neither output nor stored.

### Validity and Source

The Android documentation recommends at least 25 years and Google Play
requires a certificate that is valid after 22 October 2033:

> "A validity period of 25 years or more is recommended."
>
> "If you plan to publish your apps on Google Play, the key you use to sign
> your app must have a validity period ending after 22 October 2033. Google
> Play enforces this requirement to ensure that users can seamlessly upgrade
> apps when new versions are available."

Source: <https://developer.android.com/studio/publish/app-signing>, section
"Signing considerations" (checked on 2026-10-17). 10950 days are, at around
30 years, above the recommendation and above the Play limit.

### Secure Storage

- Store the keystore file and the password separately, for example
  the keystore in a password manager and the password in a second,
  separate vault.
- **At least two** separate, secured locations, one of them an **offline backup**
  at a second location (e.g. an encrypted medium in a safe).
- Never place in Git, in CI variables or in a cloud sync without
  encryption. The repository contains no key material whatsoever; the
  `.gitignore` rules do not replace this rule.
- Never write the password to shell history, logs, tickets or screenshots.

### Loss of the Key

If the key or its password is lost, **no further update is possible** with the
same key: Google Play accepts for an already published package
only signatures with the same identity. A new key means a new
app ID and thus a new entry without an update path for existing
installations.

**Google Play App Signing (option, currently not set up):** Google
manages the app signing key; the local key is then only the
upload key. If the upload key is lost, the owner can request an
upload key reset at Google without losing the app identity. Source:
<https://support.google.com/googleplay/android-developer/answer/7384423>
("Use Play App Signing"). The decision for or against Play App Signing
is made by the owner; this repository does not assume it.

## Local Signed Release Build

Unsigned (no variables set):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --offline :app:bundleRelease :app:assembleRelease
```

Signed (passwords only interactively in shell variables, never in files or in
the history):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ORSIK_RELEASE_STORE_FILE="$HOME/.config/orsik-release/release.jks"
read -rsp "Keystore password: " ORSIK_RELEASE_STORE_PASSWORD; echo
export ORSIK_RELEASE_STORE_PASSWORD
export ORSIK_RELEASE_KEY_PASSWORD="$ORSIK_RELEASE_STORE_PASSWORD"
export ORSIK_RELEASE_KEY_ALIAS=orsik-release
./gradlew --no-configuration-cache --offline :app:bundleRelease :app:assembleRelease
```

The configuration cache stores configuration inputs on disk, and the signing
environment variables must never end up there.

`isMinifyEnabled` remains `false`; R8/ProGuard shrinking is a separate
initiative and is **not** part of the release build here.

## Verification

`scripts/verify-release-signing.sh` checks the four paths for real and offline
(throwaway key and password only in `mktemp` and shell variables, with
`trap` cleanup):

| Case | Expectation |
| --- | --- |
| (a) no variables | release builds, AAB is unsigned, APK does not verify |
| (b) only partial variables | build aborts, message names variable names, no values |
| (c) keystore in the repository | build aborts, message contains no path |
| (d) throwaway key outside | `:app:bundleRelease` and `:app:assembleRelease` build, `apksigner verify --print-certs` and `jarsigner -verify` succeed |

```bash
./scripts/verify-release-signing.sh
```

Verify individual artifacts:

```bash
# Unsigned: expects "jar is unsigned." (jarsigner exits 0 in this case)
# Signed:   expects "jar verified."
jarsigner -verify app/build/outputs/bundle/release/app-release.aab

# Verify APK certificate (DN and digests; no secrets)
"$ANDROID_HOME"/build-tools/36.1.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

# Artifact integrity
sha256sum app/build/outputs/bundle/release/app-release.aab \
  app/build/outputs/apk/release/app-release.apk
```

## CI Release Job

The `release` job in `.github/workflows/android.yml` is named
**"Release build, SBOM and provenance"** and runs on push and pull request
against `main`. It has `permissions: contents: read` and uses **no**
GitHub secrets.

Procedure:

1. Creates a throwaway keystore in `$RUNNER_TEMP` (RSA 4096, 30 years) with a
   random password from `openssl rand -hex 24`. The password is masked with
   `::add-mask::` before being passed on and is passed to the
   following steps only via `$GITHUB_ENV`. `keytool` reads it from stdin; there is no
   password argument.
2. Builds `:app:bundleRelease` and `:app:assembleRelease` and verifies the APK
   (`apksigner verify --print-certs`) and AAB (`jarsigner -verify`).
3. Generates the CycloneDX SBOM via `:app:cyclonedxDirectBom` and validates the
   JSON (`bomFormat`, non-empty component list).
4. Places `app-release.aab`, `app-release.apk`, `app.cdx.json` and `SHA256SUMS`
   in `$RUNNER_TEMP/release-dist`.
5. Writes `provenance.intoto.json` (in-toto statement v1,
   `predicateType` `https://slsa.dev/provenance/v1`) with the subjects
   file name plus SHA-256 as well as repo, commit SHA, workflow ref, run ID and
   runner from the GitHub environment variables.
6. Uploads everything as the artifact **`release-dist`**.
7. Finally runs `scripts/verify-release-signing.sh`, thereby checking the
   fail-closed cases (a)–(c) and its own throwaway-key build (d) also in
   CI.

Limits:

- The throwaway key is **not distributable**. It only proves that the pipeline
  can sign and verify. Productive artifacts are produced exclusively
  locally with the owner key.
- The provenance is **unsigned**. GitHub Artifact Attestations are available for
  private repositories only with GitHub Enterprise Cloud; Free,
  Pro and Team only for public repositories. Source:
  <https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations>.
- The job publishes nothing outside the workflow run (no Play upload,
  no release, no push).

## App SBOM

The SBOM is generated with the CycloneDX Gradle plugin. Version and plugin ID
are pinned in the version catalog (`gradle/libs.versions.toml`,
`org.cyclonedx.bom` 3.5.1); the plugin is applied in the `app` module. Captured
is the **release runtime classpath** (`releaseRuntimeClasspath`), not the
test or compile classpath.

```bash
./gradlew --offline :app:cyclonedxDirectBom
```

Output: `app/build/reports/sbom/app.cdx.json` (CycloneDX 1.6, JSON only).
The file contains components such as OkHttp, Compose and kotlinx-coroutines.
Generated for real locally with Gradle 9.7.0 and AGP 9.3.1; the plugin requires Gradle
8.4 or newer.

Limits: The SBOM describes resolved dependencies. It is not a
source code inventory, not a vulnerability scan and not signed. An
SBOM attestation is not active for the same reason as the provenance.

## Branch Governance for `main`

Desired rules (owner decision):

- Pull request mandatory before every merge.
- Approving reviews: **0** (payload). With only one maintainer, the
  author cannot approve their own PR; with `1` and without a bypass, `main`
  would be unmergeable. As soon as a second maintainer exists, set to `1`.
- Required status checks with up-to-date branch (strict).
- No force pushes, no branch deletion.
- Linear history.

Required checks (exact check names, i.e. the job names from the workflows):

| Workflow | Check name |
| --- | --- |
| Android CI | `Unit tests, lint and debug build` |
| Android CI | `Instrumented tests (API 36)` |
| Bridge CI | `Bridge tests and reproducible build` |
| Android CI | `Release build, SBOM and provenance` |

Bridge CI runs without a path filter on every push/PR against `main` (approx. 30 s),
so that the required check always reports; a path-filtered required check
would remain permanently "pending" for PRs outside `bridge/`.

**Status: currently NOT active.** The repository is private and on the
Free plan. GitHub responds to the branch protection call with HTTP 403
("Upgrade to GitHub Pro or make this repository public to enable this
feature."). Rulesets with required checks are for private repositories
likewise available only from Pro/Team.

The payload is prepared at `.github/branch-ruleset-main.json`. After
a plan upgrade or making it public, the owner runs exactly this
command once:

```bash
gh api --method POST repos/exclude-barrier/OrsikRelay/rulesets \
  --input .github/branch-ruleset-main.json
```

Check:

```bash
gh api repos/exclude-barrier/OrsikRelay/rulesets
```

## Explicitly NOT Implemented

- **Play upload**: There is no automatic or manual upload to Google
  Play; the artifacts are built and checked locally.
- **Productive signing in CI**: The CI release job uses exclusively
  a throwaway key. No signing secrets are stored in GitHub and
  no productive artifacts are produced in CI.
- **Signed provenance/attestations**: `provenance.intoto.json` is
  unsigned. GitHub Artifact Attestations for private repositories require
  Enterprise Cloud.
- **Active branch protection/rulesets**: The payload is prepared, but due to
  the Free-plan 403 not applied.
- **R8/minify**: `isMinifyEnabled` remains `false`; code shrinking and
  resource shrinking are a separate initiative.
- **SBOM signature and vulnerability scan**: The SBOM is a pure
  dependency inventory; there is no signature and no scan gate.
