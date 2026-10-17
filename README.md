# Orsik Relay

An unofficial, independently developed native Android client for
[Hermes Agent](https://github.com/NousResearch/hermes-agent). Hermes and all of
its privileged tools stay on your own server; Orsik Relay is a Kotlin /
Jetpack Compose thin client that talks to it through a small, self-hosted
**Orsik Mobile Bridge**.

> Orsik Relay is not affiliated with or endorsed by Nous Research.

**Status: early alpha (`0.1.0-alpha.7`).** Suitable for technical testing on
your own setup, not for production use. There is no Play Store build and no
public binary release yet; build from source (see below). The app's user
interface is currently German.

## How it fits together

```
Android app ──HTTPS (private network)──▶ Orsik Mobile Bridge ──loopback──▶ official Hermes api_server
 (this repo, app/)                         (this repo, bridge/)                 (unmodified)
```

- The **bridge** runs next to an unmodified official Hermes installation and
  binds to `127.0.0.1` only. TLS is provided by something you already trust,
  for example Tailscale Serve, a reverse proxy in your LAN or WireGuard
  (`docs/network-deployment.md`).
- The bridge keeps the Hermes API key on the server. Phones never see it; they
  receive a **revocable device credential** limited to `chat`, `runs` and
  `approvals` – no jobs, memory, cron, artifacts or administration.
- The bridge adds what the official core does not (yet) offer for mobile
  clients: device pairing and revocation, protocol version negotiation, and
  re-attaching to a running stream after a network drop (`after_seq` replay).

## Features

- Pairing with a short-lived, single-use code or an `orsik://pair?…` deep link /
  QR code; credentials are protected by the Android Keystore (an API-key mode
  exists as an explicit compatibility path)
- Multiple servers with an active-server selection and "disconnect this device"
- Session list, new sessions, message history with Markdown rendering
- Streaming answers that survive network loss, app backgrounding (short-lived
  foreground service), rotation and process death – a run is never silently
  re-sent; an uncertain run is reconciled with the server instead
- Stop and steer a running turn
- Interactive tool approvals limited to **deny** or **allow once**
- Tool activity shown without commands, arguments, paths or output
- One image per turn via the system Photo Picker (JPEG/PNG/WEBP, ≤ 5 MiB, no
  storage permission)
- Persistent drafts, light/dark theme, large-font and landscape keyboard support
- No automatic retries of write requests; closed, user-readable error states

## Requirements

- **Server:** an official Hermes Agent installation with its `api_server`,
  Python ≥ 3.11 and [`uv`](https://docs.astral.sh/uv/) for the bridge, and a
  private HTTPS path from your phone to the server. Set Hermes approvals to
  `mode: manual` – the core default (`smart`) can approve some commands
  without asking (`docs/bridge-security.md`).
- **Phone:** Android 8.0 (API 26) or newer.
- **Building the app:** JDK 17 and the Android SDK (platform 37, build tools
  36.1.0). The Gradle wrapper (`./gradlew`) pins everything else.

## Quick start

1. **Install the bridge** on the Hermes host. Dependencies are installed
   hash-pinned from `bridge/uv.lock`:

   ```sh
   bridge/install.sh
   # edit ~/.local/orsik-mobile-bridge/bridge.env, then check it:
   bridge/install.sh --check
   ```

   Configuration, systemd unit, HTTPS options, update, backup and rollback are
   described in `docs/bridge-installation.md`.

2. **Expose it privately over HTTPS**, e.g. inside a tailnet:

   ```sh
   tailscale serve --bg --https=8443 http://127.0.0.1:9842
   ```

3. **Create a pairing code** on the server. This is a local CLI; the bridge has
   no HTTP admin interface:

   ```sh
   BRIDGE_ENV_FILE=~/.local/orsik-mobile-bridge/bridge.env \
     ~/.local/orsik-mobile-bridge/venv/bin/orsik-mobile-bridge admin pairing-code \
     --base-url https://<your-host>:8443
   ```

   The code is valid for 300 seconds and can be used once. With `qrencode`
   installed, a QR code for the deep link is printed as well.

4. **Pair the app:** open *Server hinzufügen* (add server), enter a name and the
   HTTPS base URL, choose *Pairing-Code* and enter the code – or simply open the
   deep link. The app verifies the connection and the exact capability contract
   before it stores anything.

List or revoke devices on the server with `admin list-devices` and
`admin revoke-device <device_id>`; a paired phone can also revoke its own
credential via *Gerät trennen* (disconnect device).

## Building from source

```sh
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=io.github.excludebarrier.orsikrelay.CaptureOnly,io.github.excludebarrier.orsikrelay.LiveBridge
                                      # instrumented tests (emulator or device)
```

Bridge tests:

```sh
cd bridge
uv run --locked python -m unittest discover -s tests
```

Release signing is fed only from environment variables and fails closed; see
`docs/release.md`. `scripts/verify-environment.sh` checks a local toolchain.

## Verification

Every pull request runs JVM unit tests, lint, a debug build, the instrumented
suite on an API 36 emulator, a release build with SBOM and checksums, and the
bridge tests (`.github/workflows/`). Live end-to-end runs against an unmodified
official Hermes core with a real model provider – pairing, chat, stop/steer,
reconnect, approvals, process death and an image turn – are documented in
`docs/bridge-e2e.md`; compatibility with several Hermes core revisions in
`docs/hermes-fresh-compat.md`.

## Known limitations

- Device pairing, protocol negotiation and stream re-attach are not part of the
  official Hermes release; the bridge is required until they are upstream.
- No per-device isolation: the official core has no per-device ownership, so
  every paired device acts on behalf of the single owner across all sessions
  and runs.
- Only images are supported as attachments (one per turn); vision depends on
  the configured model.
- Approvals do not show the command that is being approved yet.
- Voice input and bot management are not implemented.

## Documentation

Most design documents are currently written in German.

| Document | Content |
|---|---|
| `docs/architecture.md` | implemented architecture of the app |
| `docs/mobile-wire-contract.md` | versioned Mobile v1 protocol between app and bridge |
| `docs/bridge-installation.md` | bridge installation and operation |
| `docs/bridge-security.md` | threat model and security properties of the bridge |
| `docs/network-deployment.md` | private network options (LAN, WireGuard, Tailscale) |
| `docs/bridge-e2e.md` | live end-to-end evidence |
| `docs/hermes-fresh-compat.md` | compatibility with the official Hermes core |
| `docs/release.md` | release signing, SBOM, provenance, branch rules |
| `docs/toolchain.md` | required toolchain, environment check, coverage reports |

## Security

Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md),
not in public issues. Principles this project follows: no secrets in the
repository, no publicly reachable Hermes port, no disabled TLS checks and no
automatic approval of tool calls.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE).
