# Orsik Mobile Bridge – End-to-End-Nachweis (P13)

Stand: 2026-10-10. Alle Zahlen sind real auf build-host ausgeführt.

## 1. Aufbau

- **Offizieller Core:** `NousResearch/hermes-agent` `f86276d2`, isolierte
  Installation (`~/hermes-official-src`, venv Python 3.14), `HERMES_HOME`
  `~/hermes-official-home`, `api_server` auf `127.0.0.1:8899`. Gestartet als
  transiente User-Unit `p13-gw.service`; unveränderter Code.
- **Bridge:** aus dem P13-Quellstand (`uv`/venv), Loopback `127.0.0.1:9842`,
  `BRIDGE_UPSTREAM=http://127.0.0.1:8899`, persistenter Store
  `state.db`.
- **Privates HTTPS:** `tailscale serve --bg --https=8443 http://127.0.0.1:9842`
  (tailnet-only, gültiges Zertifikat, kein Funnel, kein öffentlicher Port). Die
  bestehende `:443`-Zuordnung blieb unangetastet.
- **Android:** Emulator `Hermes_API_36` (API 36), App-Debug-Build.

## 2. Live-Akzeptanz (`bridge/tests/acceptance_live.py`) — 37/37 grün

Ausgeführt auf build-host mit dem Bridge-venv gegen die laufende Bridge:

```
PASS health_200_ok / operator_issues_code / pair_201 / pair_permissions
PASS pair_key_shape / pair_device_id_uuid / pair_code_single_use
PASS capabilities_200 / caps_object / caps_runtime / caps_device_credentials
PASS caps_interactive_approvals_exact
PASS approval_always_rejected_400 / approval_once_reaches_core / query_evil_rejected_403
PASS proxied_sessions_200 / create_session_201 / session_messages_200
PASS out_of_scope_403 (jobs/cron/memory/skills/toolsets) / missing_token_401
PASS chat_stream_200 / chat_event_run.started / message.started / assistant.completed / done
PASS chat_terminal_event (run.completed) / chat_stream_run_id / run_status_200
PASS reattach_same_run_200 / reattach_replays_events / reattach_unknown_run_404
PASS self_revoke_200 / revoked_token_401
SUMMARY: 37/37
```

Bedeutung: Pairing/Single-Use, Capability-Negotiation (device_credentials +
interaktive Approvals exakt v1), Approval-Eskalationsschutz lokal, vollständiger
Chat-SSE-Lebenszyklus inkl. terminalem `run.completed`, **Re-Attach auf dieselbe
`run_id` mit Replay**, unbekannter Run `404`, durabler Self-Revoke.

## 3. Realer Android-Live-Test (`LiveBridgeE2ETest`) — 1/1 grün

Ausgeführt auf `emulator-5554` mit Instrumentation-Argumenten
(`liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443`, frischer `livePairingCode`):

```
Starting 1 tests on Hermes_API_36(AVD) - 16
Finished 1 tests on Hermes_API_36(AVD) - 16
BUILD SUCCESSFUL
```

Der Test paart und negotiiert **mit den echten App-Clients** (`HermesDevicePairClient`,
`HermesConnectionClient`) über HTTPS zur Bridge vor dem offiziellen Core und
prüft `device_credentials` + `session_chat_interactive_approvals`.

Der Test trägt `@LiveBridge` und ist aus der Regulärsuite und CI ausgeschlossen
(`notAnnotation=…CaptureOnly,…LiveBridge`); ohne Argumente skippt die JUnit-
Annahme. Er läuft nur explizit gegen den Live-Stack.

## 4. Android-Regression (build-host)

- `:app:testDebugUnitTest --rerun-tasks`: **823 Tests, 0 Fehler/Errors/Skips**.
- `:app:lintDebug`, `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin`:
  BUILD SUCCESSFUL.
- `:app:connectedDebugAndroidTest` (API 36, `notAnnotation=…CaptureOnly,…LiveBridge`):
  **247 Tests, 1 dokumentierter Skip, 0 Fehler — Voll-Suite grün.** Zwei
  vorangegangene volle Läufe trafen je den bekannten IME-/Landscape-Flake
  (`ChatLandscapeComposerUiTest.composerStaysAboveTheKeyboardWithAttachmentAndMultilineDraft`);
  die Klasse isoliert: **12/12 grün**. Der Flake ist umgebungsbedingt
  (IME-Layout) und dokumentiert in `docs/connected-test-stability.md`; kein
  Produktcode geändert.
- Bridge-unit: **100 Tests, 0 Fehler**.

## 5. Nicht verifiziert

- Echter Assistant-Inhalt (kein Provider konfiguriert; keine Modell-/Provider-
  Änderung).
- Positiver interaktiver Approval `once`/`deny` mit echtem Tool-Aufruf (ohne
  Provider keine Pending-Approval); Negativpfade live grün.
- UI-Automation der Onboarding-/Lifecycle-/Bild-Flows.

## 6. Live-Onboarding-UI-E2E (OR-07)

`LiveBridgeOnboardingE2ETest` fährt den vollständigen Onboarding-Weg über die
**echten Compose-Screens und die Produktions-`AppDependencies`** (kein direkter
Client-Aufruf): Deep Link `orsik://pair?server=…&code=…` an `MainActivity` →
Bestätigung im Onboarding → HTTPS-Pairing → Keystore-/Metadaten-Persistenz des
Device-Credentials → Sessions-Ansicht des neuen Profils → positive
Capability-Negotiation. Der Test trägt `@LiveBridge`, ist aus der Regulärsuite
und CI ausgeschlossen und skippt ohne Argumente über die JUnit-Annahme.

Aufruf gegen den Live-Stack (frischer `livePairingCode`):

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeOnboardingE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443 \
  -Pandroid.testInstrumentationRunnerArguments.livePairingCode=<frischer-code>
```

**Live-Lauf 2026-10-16 grün:** Emulator `ci_default` (API 36) gegen
`build-host` (Tailscale-HTTPS :8443 → Bridge `or-night-integration` → offizieller
Core `f86276d2`), je ein frischer Code: `LiveBridgeOnboardingE2ETest`
1/1 (0 Skip, 4,5 s) und `LiveBridgeE2ETest` 1/1 (0 Skip). Ohne Argumente:
1 Skip, 0 Fehler.

## 7. Live-Chat-UI-E2E (OR-08a)

Eigener instrumentierter Test: `LiveBridgeChatE2ETest` (`@LiveBridge`, aus der
Regulärsuite und CI ausgeschlossen wie `LiveBridgeE2ETest`). Er fährt die
ECHTE `MainActivity`, die echten Compose-Screens und den Produktions-Graph
(`AppDependenciesHolder`) gegen den Live-Stack und deckt den providerlosen
Teil des Live-Chats ab:

1. Vorbedingung ohne UI-Onboarding: alle gespeicherten Profile über
   `profileAdmin` löschen, dann das Profil über den Produktionspfad pairen
   (`DevicePairingCoordinator` aus `AppDependencies`), die aktive Auswahl und
   die verifizierte Approval-Capability persistieren (wie ein erfolgreiches
   Onboarding). Nach dem Lauf wird das erzeugte Profil wieder gelöscht.
2. Neue Session über die echte Sessions-UI (FAB + „Neue Session"-Dialog).
3. Eine Nachricht über das echte Composer-Feld senden.
4. Bounded warten (kein `Thread.sleep`) auf den TERMINALEN Run-Zustand: eine
   Assistant-Nachricht ist vorhanden und der Composer ist wieder sendebereit.
   Der Assistant-INHALT wird NICHT geprüft.
5. Genau EINE User-Nachricht mit dem gesendeten Text im Transcript.
6. `scenario.recreate()`: dasselbe Transcript ohne erneuten Send.
7. Zusätzlich liest der Test den Transcript über den Produktionspfad
   (`messageHistorySource`) und prüft genau eine User-Nachricht mit dem Text.

Der gesendete Text ist pro Lauf eindeutig (UUID-Suffix), damit Schritt 5/7
nicht mit Nachrichten früherer Läufe kollidieren.

Aufruf (explizit gegen den Live-Stack; `<tailnet>` und `<frischer-code>`
einsetzen):

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeChatE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443 \
  -Pandroid.testInstrumentationRunnerArguments.livePairingCode=<frischer-code>
```

Ohne `liveBaseUrl`/`livePairingCode` skippt die JUnit-Annahme.

Abgrenzung: Stop/Steer und das Trennen mit identischer `run_id` setzen einen
LANGE laufenden Turn voraus und damit einen Modellprovider (OR-05). Sie sind
NICHT Teil von OR-08a und werden hier nicht geprüft.

**Live-Lauf 2026-10-16** (Emulator `ci_default` API 36 → `build-host`, Core
`f86276d2` ohne Provider): Schritte 1–5 **grün** (Bridge-Log: genau ein
`POST …/chat/stream` 200). Schritt 6 **scheitert erwartbar**: nach der
Recreation lädt die App `GET …/messages` neu, und der providerlose Core
persistiert keine Nachrichten (leere History vor und nach dem Turn; 0 Messages
in allen Live-Sessions). Schritte 6–7 sind damit erst mit OR-05 (Provider)
nachweisbar; offen bleibt, ob die App den lokalen Transcript über die
Recreation halten sollte, wenn der Server leer antwortet.

**Live-Lauf mit Provider (DeepSeek, 2026-10-16): 1/1 grün, alle 7 Schritte**
(nach Scroll-Fix: eine echte Antwort schiebt die User-Zeile aus der Lazy-Liste;
der Test scrollt jetzt vor dem Zählen).

## 8. Bildturn-Transport (OR-10a)

Live-Sonde 2026-10-16 (Bridge → Core `f86276d2`, ein `image_url`-Content-Part,
PNG-Header + Padding): vor dem Fix 256 KiB → 200, aber **1 MiB / 3 MiB / 5 MiB →
502 `upstream_frame_too_large`**, weil der Core in `run.started` die komplette
`user_message` samt Base64-Bild echot (1 MiB roh → 1 398 522-Byte-Frame) und
die Relay-Grenze 1 MiB beträgt. Nach dem Fix (Echo-Redaktion, siehe
`bridge-security.md`) 1 KiB / 1 MiB / 5 MiB → **200 mit vollständigem
Lebenszyklus**, `acceptance_live.py` weiter 37/37. Ohne Provider ist nur der
Transport belegt (Core nimmt den Content-Part an, siehe Echo); Bildverständnis
und Photo-Picker-UI-Pfad sind offen (OR-05).

## 9. Live-Run-Control-E2E (OR-08b)

**Zweck:** der zweite reale Android-Live-Test. `LiveBridgeChatE2ETest` (OR-08a)
deckt nur den provider-losen Sende-Pfad ab; OR-08b fährt über denselben
Produktionspfad (`MainActivity`, echte Compose-Screens, `AppDependenciesHolder`)
die **Laufsteuerung** gegen den Live-Stack mit echtem Modellprovider (DeepSeek).
Ein langer Lauf entsteht deterministisch über die Anweisung, im Terminal-Tool
`sleep 40 && echo fertig` auszuführen (`sleep` braucht kein Approval).
Assistant-Inhalt wird nie geprüft.

Pro Test ist genau **ein** frischer Pairing-Code verfügbar; deshalb fährt
`LiveBridgeRunControlE2ETest` in genau **einem** `@Test` drei Phasen nacheinander
in derselben Session:

- **Phase A – Stop:** lange Nachricht senden, auf den Stop-Button
  (`composer_run_stop`) warten, die `run_id` aus dem Produktions-Controller
  (`RunSessionController.activeRun`) lesen, stoppen und den terminalen Zustand
  abwarten. Über den Produktionspfad `GET /v1/runs/{run_id}`
  (`RunStatusRequester`) wird zusätzlich geprüft, dass der gestoppte Lauf
  terminal und **nicht** `completed` ist (der Core meldet einen gestoppten
  Lauf als `cancelled`). Genau eine User-Nachricht für den Text.
- **Phase B – Steer:** lange Nachricht senden, während des Laufs über
  `chatSteerInput`/`chatSteerSend` steuern. Erwartet wird ein
  Queued-Ack (`chatSteerQueuedStatus`) oder ein recoverbarer Eintrag (409);
  kein `chatSteerFailedStatus`, der Lauf endet terminal.
- **Phase C – Disconnect/Re-Attach (P6/P7):** lange Nachricht senden, `run_id`
  merken. Emulator-Netz über
  `InstrumentationRegistry…uiAutomation.executeShellCommand("svc wifi disable")`
  / `("svc data disable")` trennen (Descriptor vollständig lesen und schließen),
  begrenzt auf eine sichtbare Reaktion warten, dann wieder aktivieren (immer
  in `finally`/`@After`). Zulässig ist der P4-Re-Attach auf **dieselbe**
  `run_id` (kein zweiter POST) **oder** der ehrliche P7-Zustand
  (`chat_run_uncertain`) mit `chat_run_reconcile`. Danach muss die ursprüngliche
  `run_id` terminal sein und es darf genau **eine** User-Nachricht vorliegen;
  zuletzt belegt ein Produktions-Transkript-Read, dass jeder Phasentext genau
  einmal gespeichert ist. Alle Wartezeiten sind `bounded waitUntil` (max. 120 s
  pro Run-Phase), ohne `Thread.sleep`-Ketten.

Beide Tests tragen `@LiveBridge` und sind aus der Regulärsuite/CI ausgeschlossen
(`notAnnotation=…CaptureOnly,…LiveBridge`); ohne Argumente skippt die
JUnit-Annahme. Nur explizit gegen den Live-Stack ausführen:

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeRunControlE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443 \
  -Pandroid.testInstrumentationRunnerArguments.livePairingCode=<frischer-code>
```

`<tailnet>` ist der Tailnet-Hostname des Bridge-Rechners, `<frischer-code>` ein
neu erzeugter, einmaliger 24-stelliger Pairing-Code. Der Live-Lauf wird vom
Orchestrator ausgeführt; OR-08b selbst prüft nur Übersetzung und Doku
(`:app:compileDebugAndroidTestKotlin`, `git diff --check`).

## 10. Echter Provider und reale Approvals (OR-05 / OR-06)

**OR-05:** Nur im isolierten Test-Home `~/hermes-official-home` auf
`build-host`: `config.yaml` mit `model.provider: deepseek`,
`model.default: deepseek-flash` und `approvals.mode: manual`; der Key liegt als
`DEEPSEEK_API_KEY` in `~/hermes-official-home/.env` (0600, nie ausgegeben).
Das produktive Gateway (`~/.hermes`) ist unverändert. Nachweis: Chat-SSE mit
echten `assistant.delta`-Frames, `acceptance_live.py` 37/37; Bildturn mit echtem
64×64-PNG (rot) → Antwort „Rot“.

**OR-06:** Bridge-Probe (`once` → genau einmal ausgeführt, `deny` → nicht,
`always`/`session` → lokal 400; Details `bridge-security.md` §4.1) und UI-Test
`LiveBridgeApprovalE2ETest` (`@LiveBridge`): echter `rm -rf` auf einen nicht
existierenden `/tmp`-Pfad → Produktions-Approval-Dialog → erst „Ablehnen“, dann
„Erlauben“; beide Turns enden terminal, nie UNCERTAIN, je genau eine
User-Nachricht. Live 2026-10-16: **1/1 grün**; Bridge-Audit zeigt
`choice=deny status=200` und `choice=once status=200` für dasselbe Gerät.

Zwei dabei gefundene Defekte (behoben in `or-night-integration`):
1. Die Bridge reichte den Core-`approval.request` roh durch (16 Felder,
   `choices=[once,session,always,deny]`), die App lehnt ihn strikt ab → jetzt
   Normalisierung auf die Mobile-v1-Form in der Bridge.
2. Nach Stop sendet der Core `run.cancelled` (statt `run.completed`); die App
   kannte nur `run.completed` → jeder Stop endete UNCERTAIN (OR-08c).

## 11. Echter Process Death während eines Runs (OR-09)

`LiveBridgeProcessDeathE2ETest` (`@LiveBridge`) läuft in **zwei getrennten
Instrumentation-Aufrufen**, die App bleibt dazwischen installiert (kein
`connectedDebugAndroidTest`: das deinstalliert nach dem Lauf):

```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
R=io.github.excludebarrier.orsikrelay.test/androidx.test.runner.AndroidJUnitRunner
C=io.github.excludebarrier.orsikrelay.LiveBridgeProcessDeathE2ETest
adb shell am instrument -w -e class "$C#startLongRunThenDie" -e or09Phase start \
  -e or09Title "'OR-09 <id>'" -e liveBaseUrl https://<bridge-host>.<tailnet>.ts.net:8443 \
  -e livePairingCode <frischer-code> $R
adb shell pidof io.github.excludebarrier.orsikrelay      # leer: Prozess ist tot
adb shell am instrument -w -e class "$C#recoverAfterProcessDeath" -e or09Phase recover \
  -e or09Title "'OR-09 <id>'" $R
```

Phase 1 sendet über die echte UI einen langen Turn (`sleep 45`), wartet auf die
server-bestätigte `run_id` und das persistierte Lease und endet ohne Cleanup;
das Ende der Instrumentation beendet den App-Prozess. Phase 2 startet kalt,
prüft das überlebende Lease, öffnet die Session über die echte Liste, erwartet
UNCERTAIN, reconciled explizit bis zum terminalen Server-Status, prüft das
gelöschte Lease und genau eine User-Nachricht.

**Live 2026-10-16: beide Phasen OK**, `pidof` nach Phase 1 leer. Bridge-Log: genau
ein `chat`-POST, danach nur `GET /v1/runs/{id}` (Reconcile-Polls, ~50 s
`running`, dann terminal) – kein zweiter POST.

## 12. Bildturn über den echten Photo Picker (OR-10)

`LiveBridgeImageTurnE2ETest` (`@LiveBridge`) tippt die Produktions-Anhang-Aktion
(`PickVisualMedia.ImageOnly`) und wartet begrenzt auf das Thumbnail. Den
System-Picker (eigene App `com.google.android.photopicker`) bedient der Host:

```
adb push red.png /sdcard/Pictures/or10-red.png        # 256×256, einfarbig rot
adb shell content call --method scan_file --uri content://media --arg /sdcard/Pictures/or10-red.png
adb shell am instrument -w -e class io.github.excludebarrier.orsikrelay.LiveBridgeImageTurnE2ETest \
  -e liveBaseUrl https://<bridge-host>.<tailnet>.ts.net:8443 -e livePairingCode <frischer-code> \
  io.github.excludebarrier.orsikrelay.test/androidx.test.runner.AndroidJUnitRunner &
# sobald der Picker den Fokus hat: Bounds des Eintrags "Photo taken …" aus
# `adb shell uiautomator dump` lesen und dessen Mitte antippen
adb shell input tap <x> <y>
```

Danach sendet der Test die Frage nach der Farbe, wartet auf den terminalen Turn
(nie UNCERTAIN, Anhang verbraucht) und prüft als einzige Inhaltsaussage, dass
die Antwort „rot“ enthält. **Live 2026-10-16 (DeepSeek `deepseek-flash`): 1/1
grün**, genau ein `chat/stream`-POST mit 200.
