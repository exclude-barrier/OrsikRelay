# Release device check

Before a release is published, the signed release APK is checked on a real
phone against the real bridge. Emulators miss the real WebView, keyboard,
Keystore and biometrics, notifications and the network path over Tailscale.

## Rules

- Install the signed release APK as an update (`adb install -r`), so the
  pairing and settings stay. Never run `connectedAndroidTest` on the phone:
  the debug app has the same application ID and a different signature, so the
  run would uninstall the real app and its pairing.
- Use one dedicated test chat and harmless prompts only
  ("Reply with OK only."). Every turn is a real agent run on the server.
- Never confirm an approval during the check. If one appears, the owner
  decides.
- Fingerprint/PIN prompts are done by the owner.
- Screenshots and logcat stay local and are deleted after the check; they
  show real content.

## Setup

0. The bridge on the server runs the release tag (`/branch` and `/model`
   need its routes since alpha.18).
1. On the phone: developer options → wireless debugging (or USB).
2. `adb pair <ip>:<port>` with the pairing code, then `adb connect <ip>:<port>`.
3. `adb install -r orsik-relay-<version>.apk`, then
   `adb shell dumpsys package io.github.excludebarrier.orsikrelay | grep versionName`.
4. `adb logcat -c`, so the crash check covers only this run.

## Checklist

| # | Step | Expected |
|---|---|---|
| 1 | Open the app | Home shows the paired server as connected, no crash |
| 2 | Open the test chat, send "Reply with OK only." | The answer streams in and the run ends |
| 3 | Type `/` | The command menu opens above the composer; `/stop` and `/steer` are greyed out with "No active run" |
| 4 | `/title Release check <version>` | The chat title changes |
| 5 | `/model `, choose an entry | The menu lists the server's models; the lock is confirmed |
| 6 | `/branch` | A copy of the chat opens |
| 7 | Send "Count slowly from 1 to 30.", then `/stop` | The run stops |
| 8 | Type `/var/log/syslog` without sending, then clear the field | No command menu opens; the text stays ordinary text |
| 9 | Home composer: type `/new` and send | The hint says commands work inside a chat |
| 10 | Files tile (if `BRIDGE_FILES=1`): open a folder and a text file | The list and preview load |
| 11 | Terminal tile (if `BRIDGE_TERMINAL=1`; owner unlocks), run `echo ok` | `ok` is shown |
| 12 | Start a turn, send the app to the background | A notification arrives when the run ends; tapping it opens the chat |
| 13 | Settings → switch language and back | The texts switch without a restart problem |
| 14 | `adb logcat -d -b crash` and `adb logcat -d \| grep -E "FATAL\|ANR"` | Empty |

The release is published only if every step passes. A failed step is
recorded with its screenshot and logcat excerpt in the release PR.
