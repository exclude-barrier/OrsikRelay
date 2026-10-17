# Orsik Mobile Bridge – End-to-End Proof

Status: 2026-10-10. All numbers were actually executed on build-host.

## 1. Setup

- **Official core:** `NousResearch/hermes-agent` `f86276d2`, isolated
  installation (`~/hermes-official-src`, Python 3.14 venv), `HERMES_HOME`
  `~/hermes-official-home`, `api_server` on `127.0.0.1:8899`. Started as a
  transient user unit `p13-gw.service`; unmodified code.
- **Bridge:** from the source state (`uv`/venv), loopback `127.0.0.1:9842`,
  `BRIDGE_UPSTREAM=http://127.0.0.1:8899`, persistent store
  `state.db`.
- **Private HTTPS:** `tailscale serve --bg --https=8443 http://127.0.0.1:9842`
  (tailnet-only, valid certificate, no Funnel, no public port). The
  existing `:443` mapping remained untouched.
- **Android:** emulator `Hermes_API_36` (API 36), app debug build.

## 2. Live acceptance (`bridge/tests/acceptance_live.py`) — 37/37 green

Executed on build-host with the bridge venv against the running bridge:

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

Meaning: pairing/single-use, capability negotiation (device_credentials +
interactive approvals exactly v1), approval escalation protection local, complete
chat SSE lifecycle incl. terminal `run.completed`, **re-attach to the same
`run_id` with replay**, unknown run `404`, durable self-revoke.

## 3. Real Android live test (`LiveBridgeE2ETest`) — 1/1 green

Executed on `emulator-5554` with instrumentation arguments
(`liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443`, fresh `livePairingCode`):

```
Starting 1 tests on Hermes_API_36(AVD) - 16
Finished 1 tests on Hermes_API_36(AVD) - 16
BUILD SUCCESSFUL
```

The test pairs and negotiates **with the real app clients** (`HermesDevicePairClient`,
`HermesConnectionClient`) over HTTPS to the bridge in front of the official core and
checks `device_credentials` + `session_chat_interactive_approvals`.

The test carries `@LiveBridge` and is excluded from the regular suite and CI
(`notAnnotation=…CaptureOnly,…LiveBridge`); without arguments the JUnit
assumption skips. It runs only explicitly against the live stack.

## 4. Android regression (build-host)

- `:app:testDebugUnitTest --rerun-tasks`: **823 tests, 0 failures/errors/skips**.
- `:app:lintDebug`, `:app:assembleDebug`, `:app:compileDebugAndroidTestKotlin`:
  BUILD SUCCESSFUL.
- `:app:connectedDebugAndroidTest` (API 36, `notAnnotation=…CaptureOnly,…LiveBridge`):
  **247 tests, 1 documented skip, 0 failures — full suite green.** Two
  preceding full runs each hit the known IME/landscape flake
  (`ChatLandscapeComposerUiTest.composerStaysAboveTheKeyboardWithAttachmentAndMultilineDraft`);
  the class in isolation: **12/12 green**. The flake is environmental
  (IME layout); no
  product code changed.
- Bridge unit: **100 tests, 0 failures**.

## 5. Not verified

- Real assistant content (no provider configured; no model/provider
  change).
- Positive interactive approval `once`/`deny` with a real tool call (without a
  provider, no pending approval); negative paths green live.
- UI automation of the onboarding/lifecycle/image flows.

## 6. Live onboarding UI E2E

`LiveBridgeOnboardingE2ETest` drives the complete onboarding path through the
**real Compose screens and the production `AppDependencies`** (no direct
client call): deep link `orsik://pair?server=…&code=…` to `MainActivity` →
confirmation in onboarding → HTTPS pairing → Keystore/metadata persistence of the
device credential → sessions view of the new profile → positive
capability negotiation. The test carries `@LiveBridge`, is excluded from the regular suite
and CI, and skips without arguments via the JUnit assumption.

Invocation against the live stack (fresh `livePairingCode`):

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeOnboardingE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443 \
  -Pandroid.testInstrumentationRunnerArguments.livePairingCode=<frischer-code>
```

**Live run 2026-10-16 green:** emulator `ci_default` (API 36) against
`build-host` (Tailscale HTTPS :8443 → bridge `or-night-integration` → official
core `f86276d2`), one fresh code each: `LiveBridgeOnboardingE2ETest`
1/1 (0 skips, 4.5 s) and `LiveBridgeE2ETest` 1/1 (0 skips). Without arguments:
1 skip, 0 failures.

## 7. Live chat UI E2E

Its own instrumented test: `LiveBridgeChatE2ETest` (`@LiveBridge`, excluded from the
regular suite and CI like `LiveBridgeE2ETest`). It drives the
REAL `MainActivity`, the real Compose screens, and the production graph
(`AppDependenciesHolder`) against the live stack and covers the providerless
part of the live chat:

1. Precondition without UI onboarding: delete all stored profiles via
   `profileAdmin`, then pair the profile via the production path
   (`DevicePairingCoordinator` from `AppDependencies`), persist the active selection and
   the verified approval capability (like a successful
   onboarding). After the run, the created profile is deleted again.
2. New session via the real sessions UI (FAB + **Neue Session** (New session) dialog).
3. Send a message via the real composer field.
4. Bounded wait (no `Thread.sleep`) for the TERMINAL run state: an
   assistant message is present and the composer is ready to send again.
   The assistant CONTENT is NOT checked.
5. Exactly ONE user message with the sent text in the transcript.
6. `scenario.recreate()`: the same transcript without sending again.
7. Additionally, the test reads the transcript via the production path
   (`messageHistorySource`) and checks exactly one user message with the text.

The sent text is unique per run (UUID suffix) so that step 5/7
does not collide with messages from earlier runs.

Invocation (explicitly against the live stack; substitute `<tailnet>` and `<frischer-code>`):

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeChatE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443 \
  -Pandroid.testInstrumentationRunnerArguments.livePairingCode=<frischer-code>
```

Without `liveBaseUrl`/`livePairingCode` the JUnit assumption skips.

Scope: stop/steer and disconnecting with an identical `run_id` require a
LONG-running turn and thus a model provider. They are
NOT part of this test and are not checked here.

**Live run 2026-10-16** (emulator `ci_default` API 36 → `build-host`, core
`f86276d2` without a provider): steps 1–5 **green** (bridge log: exactly one
`POST …/chat/stream` 200). Step 6 **fails as expected**: after the
recreation the app reloads `GET …/messages`, and the providerless core
does not persist messages (empty history before and after the turn; 0 messages
in all live sessions). Steps 6–7 are therefore only provable with a provider;
it remains open whether the app should retain the local transcript across the
recreation when the server responds empty.

**Live run with provider (DeepSeek, 2026-10-16): 1/1 green, all 7 steps**
(after the scroll fix: a real answer pushes the user line out of the lazy list;
the test now scrolls before counting).

## 8. Image turn transport

Live probe 2026-10-16 (bridge → core `f86276d2`, one `image_url` content part,
PNG header + padding): before the fix 256 KiB → 200, but **1 MiB / 3 MiB / 5 MiB →
502 `upstream_frame_too_large`**, because the core echoes the complete
`user_message` including the Base64 image in `run.started` (1 MiB raw → 1 398 522-byte frame) and
the relay limit is 1 MiB. After the fix (echo redaction, see
`bridge-security.md`) 1 KiB / 1 MiB / 5 MiB → **200 with complete
lifecycle**, `acceptance_live.py` still 37/37. Without a provider, only the
transport is proven (the core accepts the content part, see echo); image understanding
and the photo picker UI path remain open.

## 9. Live run control E2E

**Purpose:** the second real Android live test. `LiveBridgeChatE2ETest`
covers only the providerless send path; `LiveBridgeRunControlE2ETest` drives through the same
production path (`MainActivity`, real Compose screens, `AppDependenciesHolder`)
the **run control** against the live stack with a real model provider (DeepSeek).
A long run arises deterministically via the instruction to execute
`sleep 40 && echo fertig` in the terminal tool (`sleep` needs no approval).
Assistant content is never checked.

Per test, exactly **one** fresh pairing code is available; therefore
`LiveBridgeRunControlE2ETest` drives three phases in sequence
in exactly **one** `@Test` in the same session:

- **Phase A – Stop:** send a long message, wait for the stop button
  (`composer_run_stop`), read the `run_id` from the production controller
  (`RunSessionController.activeRun`), stop, and await the terminal state.
  Via the production path `GET /v1/runs/{run_id}`
  (`RunStatusRequester`) it additionally checks that the stopped run is
  terminal and **not** `completed` (the core reports a stopped
  run as `cancelled`). Exactly one user message for the text.
- **Phase B – Steer:** send a long message, steer during the run via
  `chatSteerInput`/`chatSteerSend`. Expected is a
  queued ack (`chatSteerQueuedStatus`) or a recoverable entry (409);
  no `chatSteerFailedStatus`, the run ends terminally.
- **Phase C – Disconnect/re-attach:** send a long message, note the `run_id`.
  Disconnect the emulator network via
  `InstrumentationRegistry…uiAutomation.executeShellCommand("svc wifi disable")`
  / `("svc data disable")` (read the descriptor completely and close it),
  wait bounded for a visible reaction, then re-enable (always
  in `finally`/`@After`). Permitted is the re-attach to the **same**
  `run_id` (no second POST) **or** the honest state
  (`chat_run_uncertain`) with `chat_run_reconcile`. After that, the original
  `run_id` must be terminal and there must be exactly **one** user message;
  finally, a production transcript read proves that each phase text is stored exactly
  once. All waits are `bounded waitUntil` (max. 120 s
  per run phase), without `Thread.sleep` chains.

Both tests carry `@LiveBridge` and are excluded from the regular suite/CI
(`notAnnotation=…CaptureOnly,…LiveBridge`); without arguments the
JUnit assumption skips. Run only explicitly against the live stack:

```
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeRunControlE2ETest \
  -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=https://<bridge-host>.<tailnet>.ts.net:8443 \
  -Pandroid.testInstrumentationRunnerArguments.livePairingCode=<frischer-code>
```

`<tailnet>` is the tailnet hostname of the bridge machine, `<frischer-code>` a
newly generated, one-time 24-character pairing code. The live run is executed by the
orchestrator; the test itself only checks translation and docs
(`:app:compileDebugAndroidTestKotlin`, `git diff --check`).

## 10. Real provider and real approvals

**Provider:** Only in the isolated test home `~/hermes-official-home` on
`build-host`: `config.yaml` with `model.provider: deepseek`,
`model.default: deepseek-flash`, and `approvals.mode: manual`; the key resides as
`DEEPSEEK_API_KEY` in `~/hermes-official-home/.env` (0600, never output).
The productive gateway (`~/.hermes`) is unchanged. Proof: chat SSE with
real `assistant.delta` frames, `acceptance_live.py` 37/37; image turn with a real
64×64 PNG (red) → answer „Rot“.

**Approvals:** Bridge probe (`once` → executed exactly once, `deny` → not,
`always`/`session` → local 400; details `bridge-security.md` §4.1) and UI test
`LiveBridgeApprovalE2ETest` (`@LiveBridge`): real `rm -rf` on a non-
existent `/tmp` path → production approval dialog → first **Ablehnen** (Deny), then
**Erlauben** (Allow); both turns end terminally, never UNCERTAIN, exactly one
user message each. Live 2026-10-16: **1/1 green**; bridge audit shows
`choice=deny status=200` and `choice=once status=200` for the same device.

Two defects found in the process (fixed in `or-night-integration`):
1. The bridge passed through the core `approval.request` raw (16 fields,
   `choices=[once,session,always,deny]`), the app rejects it strictly → now
   normalization to the mobile-v1 shape in the bridge.
2. After stop, the core sends `run.cancelled` (instead of `run.completed`); the app
   only knew `run.completed` → every stop ended UNCERTAIN.

## 11. Real process death during a run

`LiveBridgeProcessDeathE2ETest` (`@LiveBridge`) runs in **two separate
instrumentation invocations**, the app remains installed in between (no
`connectedDebugAndroidTest`: that uninstalls after the run):

```
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
R=io.github.excludebarrier.orsikrelay.test/androidx.test.runner.AndroidJUnitRunner
C=io.github.excludebarrier.orsikrelay.LiveBridgeProcessDeathE2ETest
adb shell am instrument -w -e class "$C#startLongRunThenDie" -e or09Phase start \
  -e or09Title "'Process-Death <id>'" -e liveBaseUrl https://<bridge-host>.<tailnet>.ts.net:8443 \
  -e livePairingCode <frischer-code> $R
adb shell pidof io.github.excludebarrier.orsikrelay      # empty: process is dead
adb shell am instrument -w -e class "$C#recoverAfterProcessDeath" -e or09Phase recover \
  -e or09Title "'Process-Death <id>'" $R
```

Phase 1 sends a long turn via the real UI (`sleep 45`), waits for the
server-confirmed `run_id` and the persisted lease, and ends without cleanup;
the end of the instrumentation terminates the app process. Phase 2 starts cold,
checks the surviving lease, opens the session via the real list, expects
UNCERTAIN, reconciles explicitly until the terminal server status, checks the
deleted lease and exactly one user message.

**Live 2026-10-16: both phases OK**, `pidof` after phase 1 empty. Bridge log: exactly
one `chat` POST, afterwards only `GET /v1/runs/{id}` (reconcile polls, ~50 s
`running`, then terminal) – no second POST.

## 12. Image turn via the real photo picker

`LiveBridgeImageTurnE2ETest` (`@LiveBridge`) taps the production attachment action
(`PickVisualMedia.ImageOnly`) and waits bounded for the thumbnail. The
system picker (separate app `com.google.android.photopicker`) is operated by the host:

```
adb push red.png /sdcard/Pictures/or10-red.png        # 256×256, solid red
adb shell content call --method scan_file --uri content://media --arg /sdcard/Pictures/or10-red.png
adb shell am instrument -w -e class io.github.excludebarrier.orsikrelay.LiveBridgeImageTurnE2ETest \
  -e liveBaseUrl https://<bridge-host>.<tailnet>.ts.net:8443 -e livePairingCode <frischer-code> \
  io.github.excludebarrier.orsikrelay.test/androidx.test.runner.AndroidJUnitRunner &
# once the picker has focus: read the bounds of the "Photo taken …" entry from
# `adb shell uiautomator dump` and tap its center
adb shell input tap <x> <y>
```

Afterwards the test sends the question about the color, waits for the terminal turn
(never UNCERTAIN, attachment consumed), and as the only content assertion checks that
the answer contains „rot“. **Live 2026-10-16 (DeepSeek `deepseek-flash`): 1/1
green**, exactly one `chat/stream` POST with 200.
