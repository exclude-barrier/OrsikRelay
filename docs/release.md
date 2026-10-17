# Release-Signing, AAB, SBOM und CI-Release

## Zweck

Dieses Dokument beschreibt, wie das produktive Release-Signing verwaltet wird,
wie ein signiertes Release-Artefakt (AAB und APK) lokal gebaut und verifiziert
wird und welche Grenzen der CI-Release-Job, die SBOM und die Provenance haben.

Das Release-Signing ist bewusst **nicht** im Repository hinterlegt: Die Signatur
wird ausschließlich aus vier Umgebungsvariablen gespeist, damit weder
Keystore-Datei, Passwörter noch Alias jemals in Git landen.

Die Debug-Variante bleibt unverändert und nutzt weiterhin den Android-Debug-Key.

## Die vier Umgebungsvariablen

Alle vier werden über `providers.environmentVariable(...)` gelesen. Fehlt eine,
bleibt der Release-Build unsigniert (kein Fehler), solange **keine** gesetzt ist.

| Variable | Bedeutung |
| --- | --- |
| `ORSIK_RELEASE_STORE_FILE` | Pfad zur Keystore-Datei (`.jks`) **außerhalb** des Repositories |
| `ORSIK_RELEASE_STORE_PASSWORD` | Passwort des Keystores |
| `ORSIK_RELEASE_KEY_ALIAS` | Alias des Signing-Keys |
| `ORSIK_RELEASE_KEY_PASSWORD` | Passwort des Signing-Keys (bei PKCS12 gleich dem Store-Passwort) |

Regeln in `app/build.gradle.kts` (fail-closed, unverändert):

- **Keine** der vier Variablen gesetzt: Der Release-Build läuft durch und bleibt
  unsigniert.
- **Nur ein Teil** gesetzt: Der Build bricht mit einer Festfehlermeldung ab, die
  die erwarteten Variablennamen nennt, aber **keine Werte** ausgibt.
- **Keystore innerhalb des Repositories** (kanonisch geprüfter Pfad unterhalb von
  `rootDir`): Der Build bricht mit `Release keystore must live outside the
  repository` ab, ohne den Pfad auszugeben.
- **Keystore-Datei existiert nicht**: Der Build bricht ab, ebenfalls ohne
  Pfadwert in der Meldung.

Passwörter werden nur als Umgebungsvariablen in der Shell gesetzt und niemals
ausgegeben, geloggt oder in Dateien geschrieben.

## Produktiver Upload-/Release-Key (Owner-Ablauf)

Der produktive Key wird **nur vom Menschen** auf einem vertrauenswürdigen
Rechner erzeugt. Das Werkzeug ist `scripts/release-keystore.sh`; es ist nicht
für CI gedacht.

```bash
# Standardziel ~/.config/orsik-release/release.jks, Alias orsik-release
scripts/release-keystore.sh

# Alternativ mit explizitem Ziel und Alias
scripts/release-keystore.sh \
  --path "$HOME/.config/orsik-release/release.jks" \
  --alias orsik-release
```

Eigenschaften des Skripts:

- `keytool -genkeypair`, RSA 4096 Bit, PKCS12, Gültigkeit **10950 Tage**
  (30 Jahre).
- **Keine** Passwort-Argumente. `keytool` fragt das Keystore-Passwort und die
  Bestätigung interaktiv ab; am Ende fragt es das Passwort erneut, um den
  SHA-256-Zertifikats-Fingerprint zu lesen.
- Bricht ab, wenn das Ziel im Repository liegt oder bereits existiert.
- Erzeugt das Elternverzeichnis mit Modus `0700` und die Keystore-Datei mit
  Modus `0600`.
- Gibt am Ende nur Pfad, Alias und SHA-256-Fingerprint aus. Das Passwort wird
  weder ausgegeben noch gespeichert.

### Gültigkeit und Quelle

Die Android-Dokumentation empfiehlt mindestens 25 Jahre und Google Play
verlangt ein Zertifikat, das nach dem 22. Oktober 2033 gültig ist:

> "A validity period of 25 years or more is recommended."
>
> "If you plan to publish your apps on Google Play, the key you use to sign
> your app must have a validity period ending after 22 October 2033. Google
> Play enforces this requirement to ensure that users can seamlessly upgrade
> apps when new versions are available."

Quelle: <https://developer.android.com/studio/publish/app-signing>, Abschnitt
"Signing considerations" (geprüft am 2026-10-17). 10950 Tage liegen mit rund
30 Jahren oberhalb der Empfehlung und oberhalb der Play-Grenze.

### Sichere Aufbewahrung

- Die Keystore-Datei und das Passwort getrennt aufbewahren, zum Beispiel
  Keystore in einem Passwortmanager und das Passwort in einem zweiten,
  getrennten Tresor.
- **Mindestens zwei** getrennte, gesicherte Orte, davon ein **Offline-Backup**
  an einem zweiten Ort (z. B. verschlüsselter Datenträger im Safe).
- Niemals in Git, in CI-Variablen oder in einen Cloud-Sync ohne
  Verschlüsselung legen. Das Repository enthält keinerlei Key-Material; die
  `.gitignore`-Regeln ersetzen diese Regel nicht.
- Das Passwort nie in Shell-History, Logs, Tickets oder Screenshots schreiben.

### Verlust des Keys

Geht der Key oder sein Passwort verloren, ist mit demselben Key **kein Update
mehr möglich**: Google Play akzeptiert für ein bereits veröffentlichtes Paket
nur Signaturen mit derselben Identität. Ein neuer Key bedeutet eine neue
App-ID und damit einen neuen Eintrag ohne Update-Pfad für bestehende
Installationen.

**Google Play App Signing (Option, derzeit nicht eingerichtet):** Google
verwaltet dabei den App-Signing-Key; der lokale Key ist nur noch der
Upload-Key. Bei Verlust des Upload-Keys kann der Owner bei Google einen
Upload-Key-Reset beantragen, ohne die App-Identität zu verlieren. Quelle:
<https://support.google.com/googleplay/android-developer/answer/7384423>
("Use Play App Signing"). Die Entscheidung für oder gegen Play App Signing
trifft der Owner; dieses Repository setzt sie nicht voraus.

## Lokaler signierter Release-Build

Unsigned (keine Variablen gesetzt):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --offline :app:bundleRelease :app:assembleRelease
```

Signed (Passwörter nur interaktiv in Shell-Variablen, nie in Dateien oder in
der History):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ORSIK_RELEASE_STORE_FILE="$HOME/.config/orsik-release/release.jks"
read -rsp "Keystore password: " ORSIK_RELEASE_STORE_PASSWORD; echo
export ORSIK_RELEASE_STORE_PASSWORD
export ORSIK_RELEASE_KEY_PASSWORD="$ORSIK_RELEASE_STORE_PASSWORD"
export ORSIK_RELEASE_KEY_ALIAS=orsik-release
./gradlew --offline :app:bundleRelease :app:assembleRelease
```

`isMinifyEnabled` bleibt `false`; R8/ProGuard-Verschlankung ist ein separates
Ticket und ist hier **nicht** Teil des Release-Builds.

## Verifikation

`scripts/verify-release-signing.sh` prüft die vier Pfade real und offline
(Throwaway-Key und Passwort nur in `mktemp` und Shell-Variablen, mit
`trap`-Cleanup):

| Fall | Erwartung |
| --- | --- |
| (a) keine Variablen | Release baut, AAB ist unsigniert, APK verifiziert nicht |
| (b) nur teilweise Variablen | Build bricht ab, Meldung nennt Variablennamen, keine Werte |
| (c) Keystore im Repository | Build bricht ab, Meldung enthält keinen Pfad |
| (d) Throwaway-Key außerhalb | `:app:bundleRelease` und `:app:assembleRelease` bauen, `apksigner verify --print-certs` und `jarsigner -verify` sind erfolgreich |

```bash
./scripts/verify-release-signing.sh
```

Einzelne Artefakte prüfen:

```bash
# Unsigned: erwartet "jar is unsigned." (jarsigner liefert dabei Exit 0)
# Signed:   erwartet "jar verified."
jarsigner -verify app/build/outputs/bundle/release/app-release.aab

# APK-Zertifikat prüfen (DN und Digests; keine Secrets)
"$ANDROID_HOME"/build-tools/36.1.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

# Artefakt-Integrität
sha256sum app/build/outputs/bundle/release/app-release.aab \
  app/build/outputs/apk/release/app-release.apk
```

## CI-Release-Job

Der Job `release` in `.github/workflows/android.yml` heißt
**"Release build, SBOM and provenance"** und läuft bei Push und Pull Request
gegen `main`. Er hat `permissions: contents: read` und verwendet **keine**
GitHub-Secrets.

Ablauf:

1. Erzeugt in `$RUNNER_TEMP` einen Wegwerf-Keystore (RSA 4096, 30 Jahre) mit
   zufälligem Passwort aus `openssl rand -hex 24`. Das Passwort wird vor der
   Weitergabe mit `::add-mask::` maskiert und nur über `$GITHUB_ENV` an die
   folgenden Schritte gegeben. `keytool` liest es aus stdin, es gibt kein
   Passwort-Argument.
2. Baut `:app:bundleRelease` und `:app:assembleRelease` und prüft APK
   (`apksigner verify --print-certs`) und AAB (`jarsigner -verify`).
3. Erzeugt die CycloneDX-SBOM über `:app:cyclonedxDirectBom` und validiert das
   JSON (`bomFormat`, nichtleere Komponentenliste).
4. Legt `app-release.aab`, `app-release.apk`, `app.cdx.json` und `SHA256SUMS`
   in `$RUNNER_TEMP/release-dist` ab.
5. Schreibt `provenance.intoto.json` (in-toto Statement v1,
   `predicateType` `https://slsa.dev/provenance/v1`) mit den Subjekten
   Dateiname plus SHA-256 sowie Repo, Commit-SHA, Workflow-Ref, Run-ID und
   Runner aus den GitHub-Umgebungsvariablen.
6. Lädt alles als Artefakt **`release-dist`** hoch.
7. Führt zuletzt `scripts/verify-release-signing.sh` aus und prüft damit die
   Fail-closed-Fälle (a)–(c) und einen eigenen Wegwerf-Key-Build (d) auch in
   der CI.

Grenzen:

- Der Wegwerf-Key ist **nicht verteilbar**. Er belegt nur, dass die Pipeline
  signieren und verifizieren kann. Produktive Artefakte entstehen ausschließlich
  lokal mit dem Owner-Key.
- Die Provenance ist **unsigniert**. GitHub Artifact Attestations stehen für
  private Repositories nur mit GitHub Enterprise Cloud zur Verfügung; Free,
  Pro und Team nur für öffentliche Repositories. Quelle:
  <https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations>.
- Der Job veröffentlicht nichts außerhalb des Workflow-Runs (kein Play-Upload,
  kein Release, kein Push).

## App-SBOM

Die SBOM wird mit dem CycloneDX-Gradle-Plugin erzeugt. Version und Plugin-ID
sind im Version Catalog gepinnt (`gradle/libs.versions.toml`,
`org.cyclonedx.bom` 3.5.1); das Plugin ist im `app`-Modul angewendet. Erfasst
wird die **Release-Runtime-Classpath** (`releaseRuntimeClasspath`), nicht der
Test- oder Compile-Classpath.

```bash
./gradlew --offline :app:cyclonedxDirectBom
```

Ausgabe: `app/build/reports/sbom/app.cdx.json` (CycloneDX 1.6, nur JSON).
Die Datei enthält Komponenten wie OkHttp, Compose und kotlinx-coroutines.
Lokal real mit Gradle 9.7.0 und AGP 9.3.1 erzeugt; das Plugin verlangt Gradle
8.4 oder neuer.

Grenzen: Die SBOM beschreibt aufgelöste Abhängigkeiten. Sie ist kein
Quellcode-Inventar, kein Vulnerability-Scan und nicht signiert. Eine
SBOM-Attestierung ist aus demselben Grund wie die Provenance nicht aktiv.

## Branch-Governance für `main`

Gewünschte Regeln (Owner-Entscheidung):

- Pull Request vor jedem Merge verpflichtend.
- Genehmigende Reviews: **0** (Payload). Bei nur einem Maintainer kann der
  Autor seinen eigenen PR nicht approven; mit `1` und ohne Bypass wäre `main`
  unmergebar. Sobald ein zweiter Maintainer existiert, auf `1` setzen.
- Required Status Checks mit aktuellem Branch (strict).
- Keine Force-Pushes, keine Branch-Löschung.
- Lineare History.

Pflicht-Checks (exakte Check-Namen, also die Job-Namen aus den Workflows):

| Workflow | Check-Name |
| --- | --- |
| Android CI | `Unit tests, lint and debug build` |
| Android CI | `Instrumented tests (API 36)` |
| Bridge CI | `Bridge tests and reproducible build` |
| Android CI | `Release build, SBOM and provenance` |

Bridge CI läuft ohne Pfadfilter auf jedem Push/PR gegen `main` (ca. 30 s),
damit der Pflicht-Check immer berichtet; ein pfadgefilterter Required Check
bliebe bei PRs außerhalb von `bridge/` dauerhaft "pending".

**Status: derzeit NICHT aktiv.** Das Repository ist privat und liegt im
Free-Plan. GitHub antwortet auf den Branch-Protection-Aufruf mit HTTP 403
("Upgrade to GitHub Pro or make this repository public to enable this
feature."). Rulesets mit Required Checks sind für private Repositories
ebenfalls erst ab Pro/Team verfügbar.

Der Payload liegt vorbereitet unter `.github/branch-ruleset-main.json`. Nach
einem Plan-Upgrade oder dem Öffentlich-Machen führt der Owner genau diesen
Befehl einmal aus:

```bash
gh api --method POST repos/exclude-barrier/OrsikRelay/rulesets \
  --input .github/branch-ruleset-main.json
```

Kontrolle:

```bash
gh api repos/exclude-barrier/OrsikRelay/rulesets
```

## Ausdrücklich NICHT umgesetzt

- **Play-Upload**: Es gibt keinen automatischen oder manuellen Upload zu Google
  Play; die Artefakte werden lokal gebaut und geprüft.
- **Produktives Signing in der CI**: Der CI-Release-Job nutzt ausschließlich
  einen Wegwerf-Key. Es werden keine Signing-Secrets in GitHub hinterlegt und
  keine produktiven Artefakte in der CI erzeugt.
- **Signierte Provenance/Attestations**: `provenance.intoto.json` ist
  unsigniert. GitHub Artifact Attestations für private Repositories setzen
  Enterprise Cloud voraus.
- **Aktive Branch-Protection/Rulesets**: Der Payload ist vorbereitet, aber wegen
  des Free-Plan-403 nicht angewendet.
- **R8/Minify**: `isMinifyEnabled` bleibt `false`; Code-Shrinking und
  Ressourcen-Verkleinerung sind ein separates Ticket.
- **SBOM-Signatur und Vulnerability-Scan**: Die SBOM ist ein reines
  Abhängigkeitsinventar; es gibt keine Signatur und kein Scan-Gate.
