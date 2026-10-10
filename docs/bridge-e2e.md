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
