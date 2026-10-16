# Release-Signing und AAB

## Zweck

Dieses Dokument beschreibt, wie ein signiertes Release-Artefakt (AAB und APK)
des `app`-Moduls gebaut und lokal verifiziert wird. Das Release-Signing ist
bewusst **nicht** im Repository hinterlegt: Die Signatur wird ausschließlich
aus vier Umgebungsvariablen gespeist, damit weder Keystore-Datei, Passwörter
noch Alias jemals in Git landen.

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

Regeln:

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

## Keystore außerhalb des Repositories

Die Keystore-Datei liegt außerhalb des Git-Worktrees, üblicherweise in einem
geschützten Verzeichnis des Build-Rechners oder in einem Passwortmanager/Backup.
Das Repository enthält keinerlei Key-Material; `.gitignore`-Regeln ersetzen die
Regel nicht, sondern die Datei verlässt das Repository gar nicht erst.

### Backup des produktiven Keys

Der produktive Signing-Key **muss** an mindestens zwei getrennten, gesicherten
Orten aufbewahrt werden (z. B. Passwortmanager plus Offline-Backup). Geht der
Key oder sein Passwort verloren, ist **kein Update mehr möglich**: Google Play
akzeptiert für ein bereits veröffentlichtes Paket nur Signaturen mit demselben
Key; ein neuer Key bedeutet eine neue App-ID und damit einen neuen Eintrag ohne
Update-Pfad für bestehende Installationen.

## Build-Befehle

Unsigned (keine Variablen gesetzt):

```bash
export ANDROID_HOME=/home/user/Android/Sdk
./gradlew --offline :app:bundleRelease
```

Signed (Beispiel; Passwörter nur als Umgebungsvariablen, nie im Klartext in
der History oder in Dateien):

```bash
export ANDROID_HOME=/home/user/Android/Sdk
export ORSIK_RELEASE_STORE_FILE=/pfad/ausserhalb/des/repos/release.jks
export ORSIK_RELEASE_STORE_PASSWORD="$(head -c 24 /dev/urandom | base64)"
export ORSIK_RELEASE_KEY_ALIAS=orsik-release
export ORSIK_RELEASE_KEY_PASSWORD="$ORSIK_RELEASE_STORE_PASSWORD"
./gradlew --offline :app:bundleRelease :app:assembleRelease
```

`isMinifyEnabled` bleibt `false`; R8/ProGuard-Verschlankung ist ein separates
Ticket und ist hier **nicht** Teil des Release-Builds.

## Verifikationsbefehle

```bash
# AAB-Pfad ermitteln
find app/build/outputs/bundle -name "*.aab"

# Unsigned: erwartet "jar is unsigned."
# Signed:   erwartet "jar verified."
jarsigner -verify app/build/outputs/bundle/release/app-release.aab

# APK-Zertifikat prüfen (DN und Digests; keine Secrets)
"$ANDROID_HOME"/build-tools/36.1.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

# Artefakt-Integrität
sha256sum app/build/outputs/bundle/release/app-release.aab \
  app/build/outputs/apk/release/app-release.apk
```

## Ausdrücklich NICHT umgesetzt

- **Play-Upload**: Es gibt keinen automatischen oder manuellen Upload zu Google
  Play; die Artefakte werden nur lokal gebaut und geprüft.
- **CI-Signing mit Secrets**: Die CI (`.github/workflows/android.yml`) nutzt
  weiterhin nur Debug-Artefakte. Es werden keine Signing-Secrets in GitHub
  hinterlegt und kein Release-Build in der CI ausgeführt.
- **SBOM/Provenance für die App**: Es werden keine SBOMs und keine
  Build-Provenance-Attestierungen für das App-Artefakt erzeugt.
- **Branch-Protection/Required Checks auf GitHub**: Branch-Regeln und
  verpflichtende Status-Checks sind eine Owner-Entscheidung und hier nicht
  gesetzt.
- **R8/Minify**: `isMinifyEnabled` bleibt `false`; Code-Shrinking und
  Ressourcen-Verkleinerung sind ein separates Ticket.
