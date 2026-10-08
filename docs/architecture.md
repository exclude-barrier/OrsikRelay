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

- **Keine Prozess-Tod-Recovery (P7 präzisiert).** Die Live-Run-Bindung
  (`AppDependenciesHolder`, `RunSessionController.ActiveRun`) überlebt
  Activity-/ViewModel-Recreation innerhalb desselben Prozesses, ist aber
  **nicht** persistiert. Ein Prozess-Kill beendet den Android-Prozess **und**
  den SSE-Socket, beweist aber **nicht** ein terminales Run-Ende auf dem
  Server: Ein Mobile-v1-Run mit dem `X-Hermes-Interactive-Approvals`-Opt-in
  läuft serverseitig unabhängig vom Socket weiter (siehe §5). Nach einem
  Prozess-Tod ist der unbekannte Ausgang lokal verloren; es gibt kein
  Room/DataStore-Wiederaufsetzen. Das ist eine bewusste Scope-Entscheidung
  (volle Process-Death-Persistenz war nicht Teil von P7).
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

## 5. Run-Lifecycle-Invarianten (P7)

Orsik hat genau **einen** app-weiten Run-Slot. Seine Freigabe beruht seit P7
ausschließlich auf **autoritativer** Server-Information; ein clientseitiger
Transport-, Timeout-, TLS- oder Protokollfehler darf niemals als
Server-Terminal gelten.

Klassifikation eines fehlgeschlagenen Turns (`RunTermination`, geschlossen):

- **`AuthoritativeTerminal`** – der Server hat das Run-Ende nachweislich
  bestätigt: `run.completed` (Erfolg) oder der servereigene `error`-Frame
  (`ChatStreamError.ServerError`, immer gefolgt von `done`; der Server setzt
  dabei einen terminalen Status). Nur hier wird der Slot freigegeben.
- **`TransportOutcomeUnknown`** – bestätigter Run + lokaler
  Transport-/Protokollfehler, **oder** unbestätigter Start nach dem initialen
  POST (`Network`/`Timeout`/`Tls`/`IncompatibleApi`/5xx `ServerUnavailable`):
  Der POST kann serverseitig bereits akzeptiert sein. Die Ownership bleibt
  erhalten (`ActiveRun.Status.UNCERTAIN`), es wird **kein** zweiter POST
  gesendet, kein impliziter Server-Stop ausgelöst und keine automatische
  Approval-Aktion ausgeführt. Der FGS wird gestoppt (kein aktiver Transport),
  die logische Ownership bleibt davon getrennt bestehen.
- **`LocalRequestRejected`** – der Start wurde nachweislich abgelehnt, bevor
  ein Run entstand (lokale Validierung oder eine 3xx/4xx-HTTP-Antwort; **kein
  5xx**, das ein Gateway nach einer bereits erfolgten Admission liefern kann).
  Der Slot wird freigegeben; ein neuer, bewusster Turn ist möglich.

Weitere Invarianten:

- **`RunStopResult.Accepted` ist nicht terminal.** Nur
  `AlreadyTerminal` löst die autoritative Freigabe (`cancelTurn`) aus; der
  Stream läuft bis zu seinem eigenen Terminal weiter.
- **Lokale Widersprüche (doppelte Approval-Nachricht) geben den Slot nicht
  frei.** `RunSessionController.markTurnUncertain` verwirft den toten lokalen
  Stream, hält aber die Ownership; es wird niemals automatisch `once`/`deny`
  gesendet.
- **Wiederherstellung ist explizit und lesend.** Ein bestätigter,
  unbestimmter Run kann manuell über `GET /v1/runs/{run_id}` abgeglichen
  werden (`RunStatusRequester`): ein terminaler Server-Status gibt den Slot
  autoritativ frei und lädt den Verlauf neu; `running`/`queued`/
  `waiting_for_approval`/`stopping` oder ein Lesefehler ändern nichts. Ohne
  bestätigte Run-ID gibt es **keinen** sicheren Abgleich; der Zustand wird
  ehrlich als unbestimmt gezeigt (siehe §6, Serververtrags-Lücke).
- **Keine automatische Freigabe durch `cancelTurn`-Semantik allein.** Der
  Collector-`finally` setzt den Slot nicht mehr zurück; nur die
  Terminal-Handler, `cancelTurn` (autoritativ), `markTurnUncertain`,
  `confirmTerminalForUncertainRun` und `close` ändern ihn.

## 6. Sicherheitsinvarianten (verifiziert im Code)

- Credentials nur im Android Keystore (`AndroidKeystoreCredentialStore`,
  AES-256-GCM, Schlüssel pro Profil, versioniertes Payload).
- `allowBackup=false`, kein Klartext, keine TLS-Ausnahmen, keine Redirects,
  keine Retry schreibender Requests.
- Approvals ausschließlich `once`/`deny`, nie automatisch.
- Fehlerflächen sind geschlossen und ohne Body-/URL-/Key-Echo.
- Tool-Aktivität wird redigiert (keine Kommandos/Pfade/URLs/Tool-Ausgaben).
