# Architektur (Ist-Stand)

Dieses Dokument beschreibt die **tatsächlich implementierte** Architektur von
Orsik Relay und markiert ausdrücklich die Stellen, an denen der
`internal plan` (Soll) vom Code (Ist) abweicht. Maßgeblich ist der
Code; Abweichungen sind teils bewusst, teils offen.

Stand: `v0.1.0-alpha.6` (`versionCode 6`), inklusive CI-Workflow,
Apache-2.0-Lizenz und Audit-Härtungen.

## 1. Modul- und Build-Struktur

- **Ein einzelnes Gradle-Modul** `app` (Namespace `io.github.excludebarrier.orsikrelay`).
  Der Plan sah Multi-Modul (`core/`, `data/`, `feature/`) vor; der Plan
  erlaubt in §5 ausdrücklich den Start als einzelnes `app`-Modul. Paketgrenzen
  bleiben die Schichtgrenzen.
- Kein DI-Framework. Der produktive Graph wird von Hand in
  `di/AppDependencies.kt` gebaut und prozessweit in `AppDependenciesHolder`
  gecacht. `HermesNavHost` löst bei Bedarf `AppDependenciesHolder.get(...)` auf;
  Test-Injektionen umgehen den Cache und bauen einen isolierten Graphen.
- Wrapper `8.14.5`, Kotlin `2.2.10`, AGP `8.13.2`, Compose BOM `2025.12.00`,
  `minSdk 26`, `compileSdk`/`targetSdk 36`, JDK 17.

## 2. Schichten

```
Compose UI  →  ViewModel  →  Domain-Seams (Use Cases)  →  Data-Repositories  →  REST/SSE + Platform/Security
```

- `ui/` kennt keine Netzwerk-DTOs; `ChatViewModel` hält einen unveränderlichen
  `ChatUiState`.
- `domain/` enthält geschlossene Ergebnis-/Fehlertypen und Seams
  (`RunStopRequester`, `RunSteerRequester`, `RunApprovalRequester`, …).
- `data/` implementiert die Seams gegen `network/` und die Stores.
- `network/` enthält je Endpunkt einen Client plus `SseFrameParser` und
  `ChatStreamEventPipeline`.
- `security/credential/` (Keystore) und `platform/` (Attachment, Notification,
  Foreground-Service) sind die Android-Adapter.

## 3. Abweichungen Soll → Ist

| Plan (Soll) | Ist | Bewertung |
|---|---|---|
| Multi-Modul `core/`/`data/`/`feature/` | Ein `app`-Modul | bewusst (§5 erlaubt es) |
| Room für Profile/Cache/Drafts | SharedPreferences mit versionierten Keys | bewusst: Room ist keine Dependency; das Repo-Muster ist SharedPreferences + versionierter Key (TASK 009A) |
| DataStore für Drafts | SharedPreferences (`chat.draft.v1.*`) | bewusst, s. o. |
| `docs/security-threat-model.md`, `docs/testing.md`, `docs/release-checklist.md` | nicht vorhanden | offen |
| Network Security Config (Datei) | `android:usesCleartextTraffic="false"` + HTTPS-only im Client | bewusst/minimal: Klartext ist ohnehin blockiert |
| Zertifikats-Pinning | nicht implementiert | offen (Plan: „optional nach stabiler Strategie“) |
| Release-Signing / AAB / CI | kein Signing, keine AAB; CI vorhanden (`​.github/workflows/android.yml`: Unit-Tests, Lint, `assembleDebug`, Instrumented API 36) | CI erledigt; Signing/AAB bleiben ein separates Ticket |
| `HermesApplication.kt` | nicht vorhanden (Application-Klasse unnötig) | bewusst |

## 4. Bekannte, bewusst getragene Grenzen

- **Keine Prozess-Tod-Recovery.** Die Live-Run-Bindung (`AppDependenciesHolder`,
  `RunSessionController.ActiveRun`) überlebt Activity-/ViewModel-Recreation
  innerhalb desselben Prozesses, ist aber **nicht** persistiert. Ein
  Prozess-Kill beendet den laufenden Run und die SSE-Verbindung; es gibt kein
  Room/DataStore-Wiederaufsetzen. Das ist eine bewusste Scope-Entscheidung.
- **Test-Seam im Produktionscode.** `MainActivity.sessionPageSourceForTests`
  (`@VisibleForTesting`, Default `null`) überspringt in `androidTest` die
  Sessionliste. In Produktion ist sie immer `null`; der Seam ist bewusst und als
  vertretbar dokumentiert, weil ein realer Activity-Recreation-Test sonst einen
  echten Netzwerkpfad bräuchte.
- **Serververtrag nicht öffentlich.** Der Mobile-v1-Vertrag
  (`device_credentials`, `session_chat_interactive_approvals`,
  `X-Hermes-Interactive-Approvals`) ist **nicht** in der öffentlichen
  Hermes-Version enthalten. Ohne einen passenden Serverstand ist die App nicht
  nutzbar. Die Veröffentlichung des Serververtrags ist Voraussetzung für einen
  allgemein nutzbaren Release (siehe `README.md`).

## 5. Sicherheitsinvarianten (verifiziert im Code)

- Credentials nur im Android Keystore (`AndroidKeystoreCredentialStore`,
  AES-256-GCM, Schlüssel pro Profil, versioniertes Payload).
- `allowBackup=false`, kein Klartext, keine TLS-Ausnahmen, keine Redirects,
  keine Retry schreibender Requests.
- Approvals ausschließlich `once`/`deny`, nie automatisch.
- Fehlerflächen sind geschlossen und ohne Body-/URL-/Key-Echo.
- Tool-Aktivität wird redigiert (keine Kommandos/Pfade/URLs/Tool-Ausgaben).
