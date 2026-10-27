# Security Policy

Orsik Relay is a thin client for a self-hosted AI agent that can run powerful
tools on your server. Security reports are very welcome.

## Supported versions

The project is in early alpha. Only the latest state of the `main` branch and
the most recent pre-release (once published) receive security fixes.

| Version | Supported |
| --- | --- |
| `main` / latest `0.1.x` pre-release | ✅ |
| older pre-releases | ❌ |

## Reporting a vulnerability

**Please do not open a public issue, discussion or pull request for a
vulnerability.**

Report it privately through GitHub's
[private vulnerability reporting](https://github.com/exclude-barrier/OrsikRelay/security/advisories/new)
("Security" tab → "Report a vulnerability"). Please include:

- the affected component (Android app, bridge, install script, CI workflow),
- the version or commit,
- steps to reproduce or a proof of concept,
- the impact you expect.

Do not include real API keys, device tokens, pairing codes or personal data in
the report; redact them.

This is a spare-time project. You can expect an acknowledgement within
**7 days** and an assessment within **30 days**. Fixes are released as soon as
practical; you will be credited in the advisory unless you prefer otherwise.

## Scope

In scope:

- the Android app (`app/`), including credential storage, pairing, TLS
  handling, approvals and the handling of untrusted server data,
- the Orsik Mobile Bridge (`bridge/`), including authentication, the device
  scope allowlist, proxying to Hermes and the local admin CLI,
- `bridge/install.sh`, `bridge/install-bridge.sh`, the release scripts in `scripts/` and the GitHub Actions
  workflows.

Out of scope:

- vulnerabilities in Hermes Agent itself – please report those to the
  [Hermes Agent project](https://github.com/NousResearch/hermes-agent),
- insecure deployments that contradict the documentation, for example exposing
  the bridge or the Hermes `api_server` directly to the internet, disabling
  TLS, or running Hermes with automatic approvals,
- denial of service that requires an already paired device (paired devices act
  on behalf of the owner by design, see `docs/bridge-security.md`).

## Security model

The threat model, trust boundaries and known residual risks are documented in
[`docs/bridge-security.md`](docs/bridge-security.md). Key properties:

- the Hermes API key never leaves the server; phones only hold revocable
  device credentials limited to `chat`, `runs` and `approvals`,
- the bridge binds to loopback only and has no HTTP admin interface,
- approvals are limited to "deny" and "allow once",
- the app does not follow redirects, does not disable TLS validation and never
  retries write requests automatically.

## Local settings and updates

`omb setup` is a local operator interface, with no HTTP administration endpoint.
It edits only the allowlisted terminal/file settings and the CLI language in an
existing private `bridge.env`. Writes are atomic and refuse an observed
concurrent edit. Secrets and unrelated configuration lines are preserved.
English is the default; a saved English/German preference applies to setup and
update. Language changes do not change permissions or require a service restart.

Saving settings does not restart the service. **Apply and restart bridge** has
its own confirmation and interrupts active connections. Device terminal grants
are separately confirmed and take effect immediately. A granted terminal is a
full shell as the bridge user, bypasses Hermes approvals and is not a project
sandbox. Revocation disconnects access but does not terminate existing shell jobs.

`omb update` checks the official repository before confirmation and pins the
installer to the resolved release commit. A matching private `installed-commit`
marker avoids installation and restart. Missing or invalid markers are reported
as unknown; network failures do not start an installation. This marker is a
local operational record, not a cryptographic attestation. Only successful
installation/checks record it. Updates back up the device store, preserve
settings, and have no automatic write retries. `--yes` explicitly authorizes a
noninteractive update; `--no-service` skips service changes.
