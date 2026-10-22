# Orsik Relay

**Your self-hosted Hermes Agent, safely in your pocket.**

[![Android CI](https://github.com/exclude-barrier/OrsikRelay/actions/workflows/android.yml/badge.svg)](https://github.com/exclude-barrier/OrsikRelay/actions/workflows/android.yml)
[![Bridge CI](https://github.com/exclude-barrier/OrsikRelay/actions/workflows/bridge.yml/badge.svg)](https://github.com/exclude-barrier/OrsikRelay/actions/workflows/bridge.yml)
[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-green.svg)](LICENSE)

Orsik Relay is a native Android client for [Hermes Agent](https://github.com/NousResearch/hermes-agent).
Chat with your agent, watch it work, stop or steer a run and answer tool
approvals from your phone – while Hermes, its API key and all privileged tools
stay on **your** server. The phone only ever holds a revocable, least-privilege
device credential and talks to a small, self-hosted **Orsik Mobile Bridge** over
your private network.

<p align="center">
  <img src="docs/images/chat.png" width="23%" alt="Chat with a Markdown answer and tool activity">
  <img src="docs/images/approval.png" width="23%" alt="Tool approval: deny or allow once">
  <img src="docs/images/sessions.png" width="23%" alt="Session list">
  <img src="docs/images/chat-dark.png" width="23%" alt="Chat in dark mode">
</p>

*Real app screens rendered with sample data (`scripts/readme-screenshots.sh`).
The user interface is available in English and German; choose the language
under **⋮ → Settings → Language**.*

> **Early alpha (`0.1.0-alpha.12`).** For technical testing on your own setup.
> No Play Store build yet – install the APK from the [releases](https://github.com/exclude-barrier/OrsikRelay/releases) or [build from source](#building-from-source).
> Not affiliated with or endorsed by Nous Research.

[How it works](#how-it-works) · [Quick start](#quick-start) · [Using the app](#using-the-app) · [Limits](#compatibility-and-limits) · [Troubleshooting](#troubleshooting) · [Documentation](#documentation)

## Highlights

- **Pairing without secrets on the phone:** a single-use code (valid 300 s) or an
  `orsik://pair` deep link / QR code creates a device credential limited to
  `chat`, `runs` and `approvals`, stored with the Android Keystore and revocable
  at any time – from the server or from the phone.
- **Runs that survive real life:** streaming answers continue across network
  drops, backgrounding, rotation and even process death. A run is never
  silently re-sent; if its outcome is unknown, the app asks the server.
- **You stay in control:** stop or steer a running turn; tool approvals offer
  only **Deny** or **Allow** (allow once).
- **Privacy by design:** tool activity is shown without commands, arguments,
  paths or output; write requests are never retried automatically; TLS
  validation and redirects are never relaxed.
- **Everyday chat:** sessions, history, Markdown with code blocks and a copy
  button, one image per turn via the system Photo Picker, persistent drafts,
  light and dark theme, large fonts and landscape keyboards.
- **Unmodified Hermes:** the bridge runs next to an official Hermes installation;
  no fork, no core patch.

## How it works

```
 Phone                         Your server
┌──────────────┐   HTTPS    ┌──────────────────────┐ loopback ┌─────────────────────────┐
│ Orsik Relay  │──────────▶ │ Orsik Mobile Bridge  │────────▶ │ official Hermes Agent   │
│ (app/)       │  private   │ (bridge/, 127.0.0.1) │          │ api_server (unmodified) │
└──────────────┘  network   └──────────────────────┘          └─────────────────────────┘
```

- **Private network only.** The bridge binds to `127.0.0.1`; HTTPS comes from
  something you already trust – Tailscale Serve, a reverse proxy in your LAN or
  WireGuard ([network options](docs/network-deployment.md)). Nothing needs to
  be exposed to the internet.
- **The API key never leaves the server.** The bridge holds it and gives each
  phone its own device token; it proxies only sessions and runs – no jobs,
  memory, cron, artifacts or administration.
- **What the bridge adds:** device pairing and revocation, protocol version
  negotiation and re-attaching to a running stream (`after_seq` replay) – the
  parts the official core does not (yet) provide for mobile clients.

## Quick start

You need:

- a server with **Hermes Agent** and its `api_server` enabled (it listens on
  `127.0.0.1`, port `8642` by default),
- a **private network** between server and phone, for example
  [Tailscale](https://tailscale.com/) on both,
- an Android **8.0+** phone.

### 1. Install the app

Download `orsik-relay-<version>.apk` from the
[latest release](https://github.com/exclude-barrier/OrsikRelay/releases) on
your phone and open it. Android asks once whether your browser may install
apps.

### 2. Set up the bridge on the server

The app talks to a small **Orsik Mobile Bridge** that runs next to Hermes on
the same server. Choose one way.

#### Quickest: one command

Run this as the user that runs Hermes (not as root):

```sh
curl -fsSL https://raw.githubusercontent.com/exclude-barrier/OrsikRelay/main/bridge/install-bridge.sh | bash
```

It checks Hermes (api_server on loopback, `approvals: mode: manual`), installs
the latest release with hash-pinned dependencies, writes the bridge
configuration, starts the bridge as a user service and prints the one command
that needs root (`tailscale serve`) plus the command for a pairing code. It
never changes Hermes itself, opens no port and prints no secrets. Once
Tailscale Serve is set up, `… | bash -s -- --pair` also prints a pairing code.

Prefer to read it first:

```sh
curl -fsSLO https://raw.githubusercontent.com/exclude-barrier/OrsikRelay/main/bridge/install-bridge.sh
less install-bridge.sh
bash install-bridge.sh
```

#### Option A: let Hermes do it

Send this message to your Hermes agent, for example via Telegram or the CLI.
Hermes installs everything that does not need root, tells you the one command
that does, and stops if anything fails.

```text
Please install the Orsik Mobile Bridge for the Orsik Relay app on this server.
Rules: work as the user that runs Hermes; do not change Hermes itself (no core
files, no config.yaml or .env edits, no restarts of the gateway); never print
API keys or other secrets; no "curl | bash"; never open a public port or use
Tailscale Funnel; do not run commands that need root, tell me instead. If a
step fails, stop and show me the error.

1. Check: the api_server answers on 127.0.0.1 (port = API_SERVER_PORT in
   ~/.hermes/.env, default 8642) with HTTP 200 on /health; ~/.hermes/config.yaml
   has "approvals: mode: manual" (if not, stop and tell me); git, uv and
   Python >= 3.11 are installed (if uv is missing, tell me how to install it,
   e.g. "pipx install uv"); "loginctl show-user $USER -p Linger" says yes (if
   not, tell me to run "sudo loginctl enable-linger <user>").
2. git clone https://github.com/exclude-barrier/OrsikRelay.git ~/OrsikRelay,
   then in ~/OrsikRelay: git checkout "$(git describe --tags --abbrev=0)".
3. Run ~/OrsikRelay/bridge/install.sh --systemd
   (if Python 3.14 is not available, prefix it with BRIDGE_PYTHON=<installed
   version, e.g. 3.12>).
4. In ~/.local/orsik-mobile-bridge/bridge.env set BRIDGE_UPSTREAM to
   http://127.0.0.1:<api port>, BRIDGE_HERMES_ENV to the absolute path of
   ~/.hermes/.env and BRIDGE_STATE_DB to the absolute path of
   ~/.local/orsik-mobile-bridge/state.db; keep BRIDGE_BIND=127.0.0.1 and
   BRIDGE_PORT=9842; comments only on their own lines.
5. Run ~/OrsikRelay/bridge/install.sh --check until it ends with
   "PREFLIGHT: PASS" (a warning about BRIDGE_PUBLIC_BASE_URL is fine for now),
   then: systemctl --user enable --now orsik-mobile-bridge.service
6. Tell me to run "sudo tailscale serve --bg --https=8443 http://127.0.0.1:9842"
   and to send you the https URL that "tailscale serve status" shows.
7. When I send the URL: set BRIDGE_PUBLIC_BASE_URL in bridge.env to it, run
   systemctl --user restart orsik-mobile-bridge.service, then create a pairing
   code with
   BRIDGE_ENV_FILE=~/.local/orsik-mobile-bridge/bridge.env ~/.local/orsik-mobile-bridge/venv/bin/orsik-mobile-bridge admin pairing-code --base-url <URL>
   and give me the code and the orsik://pair link. The code works once and
   for 5 minutes.
```

#### Option B: do it yourself

Run these as the user that runs Hermes, in this order.

```sh
# 1. Tools: git, Python >= 3.11 and uv (e.g. "sudo apt install pipx && pipx install uv")
git clone https://github.com/exclude-barrier/OrsikRelay.git ~/OrsikRelay
cd ~/OrsikRelay && git checkout "$(git describe --tags --abbrev=0)"

# 2. Install (add BRIDGE_PYTHON=3.12 in front if Python 3.14 is not available)
bridge/install.sh --systemd
```

```sh
# 3. Edit ~/.local/orsik-mobile-bridge/bridge.env (replace <user>; port = API_SERVER_PORT in ~/.hermes/.env)
BRIDGE_BIND=127.0.0.1
BRIDGE_PORT=9842
BRIDGE_UPSTREAM=http://127.0.0.1:8642
BRIDGE_HERMES_ENV=/home/<user>/.hermes/.env
BRIDGE_STATE_DB=/home/<user>/.local/orsik-mobile-bridge/state.db
BRIDGE_PUBLIC_BASE_URL=https://<server>.<tailnet>.ts.net:8443
```

```sh
# 4. Check and start ("PREFLIGHT: PASS" expected)
bridge/install.sh --check
systemctl --user enable --now orsik-mobile-bridge.service

# 5. Private HTTPS inside the tailnet (needs root; no Funnel, no public port)
sudo tailscale serve --bg --https=8443 http://127.0.0.1:9842

# 6. Pairing code (works once, for 5 minutes; prints a QR code if qrencode is installed)
BRIDGE_ENV_FILE=~/.local/orsik-mobile-bridge/bridge.env \
  ~/.local/orsik-mobile-bridge/venv/bin/orsik-mobile-bridge admin pairing-code \
  --base-url https://<server>.<tailnet>.ts.net:8443
```

Hermes must ask before dangerous commands: keep `approvals: mode: manual` in
`~/.hermes/config.yaml`; the core default `smart` can approve some commands
without asking. More options (systemd, other networks, backup):
[bridge installation](docs/bridge-installation.md).

### 3. Pair the app

In the app tap **Scan QR code** and point the camera at the QR in the
terminal, or type the HTTPS URL and the pairing code, then tap **Connect**.
The QR appears when `qrencode` is installed on the server (e.g.
`sudo apt install qrencode`). The phone must be in the same private network
(Tailscale on). The app checks the connection and the exact capability
contract before it stores anything.

<p align="center">
  <img src="docs/images/onboarding.png" width="28%" alt="Adding a server with a pairing code">
</p>

### Updating

- **Bridge, quickest:** run the install command from above again. It backs up
  the device store, switches to the latest release and restarts the bridge.
- **App:** install the new APK over the old one. It is signed with the same
  key, so your servers and pairing are kept. [Obtainium](https://github.com/ImranR98/Obtainium)
  can watch the GitHub releases and tell you about new versions.
- **Bridge with Hermes:** send Hermes:

  ```text
  Please update the Orsik Mobile Bridge on this server. Same rules as for the
  installation. Create a backup first:
  BRIDGE_ENV_FILE=~/.local/orsik-mobile-bridge/bridge.env ~/.local/orsik-mobile-bridge/venv/bin/orsik-mobile-bridge admin backup ~/.local/orsik-mobile-bridge/state.db.bak-<date>
  then in ~/OrsikRelay: git fetch --tags and
  git checkout "$(git describe --tags --abbrev=0 origin/main)", run
  bridge/install.sh --update --systemd, systemctl --user restart
  orsik-mobile-bridge.service and bridge/install.sh --check, and show me the
  result.
  ```

- **Bridge yourself:**

  ```sh
  BRIDGE_ENV_FILE=~/.local/orsik-mobile-bridge/bridge.env \
    ~/.local/orsik-mobile-bridge/venv/bin/orsik-mobile-bridge admin backup \
    ~/.local/orsik-mobile-bridge/state.db.bak-$(date +%F)
  cd ~/OrsikRelay && git fetch --tags
  git checkout "$(git describe --tags --abbrev=0 origin/main)"
  bridge/install.sh --update --systemd
  systemctl --user restart orsik-mobile-bridge.service
  bridge/install.sh --check
  ```

  `bridge.env` and the device store are kept, paired phones stay paired.
  Update app and bridge to the same release.

## Using the app

| Task | Where |
| --- | --- |
| Add a server | **⋮ → Manage servers → Add server** (or open an `orsik://pair` link) |
| Pair with a QR code | Onboarding → **Scan QR code** (the bridge prints it with `pairing-code` when `qrencode` is installed) |
| Switch or remove servers | tap the server name on **Home** (or **⋮ → Manage servers** on **Agents**), then tap a server or use its ⋮ |
| Disconnect this phone from a server | **⋮ → Manage servers → tap the server → Delete** (the device is disconnected on the server first) |
| Start a task | type it into the composer on **Home** and send (a new session opens), or **+** (New session) on **Agents** |
| See running tasks and approvals | **Home → Running now**; **Review** opens the chat with the approval |
| Stop a running answer | **Stop** |
| Correct a running answer | type in **Steer message**, then **Steer** |
| Answer a tool approval | **Deny** or **Allow** (allow once) |
| Attach an image | **Attach** in the composer (one image, ≤ 5 MiB) |
| Open the settings | the gear on **Home** (or **⋮ → Settings** on **Agents**) |
| Change the app language | **Settings → Language** (System / English / Deutsch) |
| Light / dark mode | **Settings → Appearance** (System / Light / Dark) |
| Chat text size | **Settings → Chat** |
| Block screenshots | **Settings → Privacy → Screen protection** |
| Lock the app | **Settings → Privacy → App lock** |
| App version and license | **⋮ → About the app** on **Agents** |

On the server, `admin list-devices` and `admin revoke-device <device_id>` list
and immediately revoke paired phones.

## Compatibility and limits

- **Server:** official Hermes Agent with the Orsik Mobile Bridge. Device pairing,
  protocol negotiation and stream re-attach are not part of the official Hermes
  release, so the bridge is required until they are upstream. Tested Hermes
  core revisions: [compatibility matrix](docs/hermes-fresh-compat.md).
- **Phone:** Android 8.0 (API 26) or newer; built against API 37, targeting
  API 36. Certificates must be trusted by Android's system store – private or
  self-signed CAs are not supported.
- **Single owner:** the official core has no per-device ownership; every paired
  phone acts on behalf of the one owner across all sessions and runs
  ([threat model](docs/bridge-security.md)).
- **Attachments:** images only (JPEG, PNG, WEBP), one per turn, up to 5 MiB;
  vision depends on your model.
- **Home:** shows only tasks started from this phone; runs started elsewhere
  (Telegram, cron) do not appear. Server values (CPU, RAM, GPU, services), the
  Server and Activity tabs, the dashed quick-access tiles and agents other than
  Hermes are previews without function.
- **Not yet:** approvals do not show the command being approved, no voice input,
  no bot management.

## Troubleshooting

- **“The server does not provide the required Hermes features.”** – the URL does
  not point at the bridge, or the bridge/core does not offer the Mobile v1
  contract. Use the bridge URL (port 8443 in the example above), not the Hermes
  `api_server` itself, and run `bridge/install.sh --check`.
- **“The secure server connection could not be verified.”** – the TLS
  certificate is not trusted by Android. Use a publicly trusted certificate
  (Tailscale Serve provides one) instead of a self-signed CA.
- **“The Hermes server is currently unreachable.”** – the phone cannot reach
  the server: check VPN/tailnet, the `tailscale serve` mapping and that the
  bridge is running.
- **“The pairing code is invalid, expired, or already used.”** – codes
  are single-use and expire after 300 seconds; create a new one.
- **No approval ever appears** – Hermes may be approving on its own; set
  `approvals: mode: manual`.

## Documentation

- [Bridge installation](docs/bridge-installation.md): install, configure, update, back up and roll back the bridge.
- [Network options](docs/network-deployment.md): LAN, WireGuard and Tailscale setups.
- [Threat model](docs/bridge-security.md): trust boundaries, security properties and residual risks.
- [Mobile wire contract](docs/mobile-wire-contract.md): the versioned Mobile v1 protocol between app and bridge.
- [Architecture](docs/architecture.md): layers and run-lifecycle invariants of the app.
- [Hermes compatibility](docs/hermes-fresh-compat.md) and [live end-to-end evidence](docs/bridge-e2e.md).
- [Release](docs/release.md): signing, SBOM, provenance and branch rules. [Toolchain](docs/toolchain.md): build requirements and coverage.

## Building from source

You need JDK 17 and the Android SDK (platform 37, build tools 36.1.0);
`scripts/verify-environment.sh` checks your setup. The Gradle wrapper pins
everything else.

```sh
./gradlew :app:assembleDebug          # debug APK in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # JVM unit tests
./gradlew :app:lintDebug              # Android lint
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.filter=io.github.excludebarrier.orsikrelay.ManualTestsFilter
(cd bridge && uv run --locked python -m unittest discover -s tests)
```

Every pull request runs these checks plus a release build with SBOM and
checksums. Release signing is fed only from environment variables and fails
closed ([release](docs/release.md)).

## Contributing

Bug reports and focused feature requests are welcome through
[GitHub Issues](https://github.com/exclude-barrier/OrsikRelay/issues). Please read
[CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request, and report
security issues privately as described in [SECURITY.md](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE).

The app bundles the Roboto Mono font (Copyright 2015 The Roboto Mono Project
Authors, [googlefonts/RobotoMono](https://github.com/googlefonts/RobotoMono))
under the SIL Open Font License 1.1; the license text ships in the app as
[`assets/licenses/roboto-mono-OFL.txt`](app/src/main/assets/licenses/roboto-mono-OFL.txt).
