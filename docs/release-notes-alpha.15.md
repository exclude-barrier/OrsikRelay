# v0.1.0-alpha.15

Update app and bridge together for the new terminal and file features. They
remain unavailable unless the bridge advertises the corresponding capabilities.

## What's new

- Native server terminal with device authentication, reconnect, font sizing
  and terminal output as context for a new Hermes request.
- Read-only files and projects: browse and preview server files, then prepare
  a Hermes request with the selected context. No file mutations.
- Long-running chats survive repeated network interruptions when each
  reconnection makes progress. Reconnects without progress remain bounded.

## Bridge and installer fixes

- Large history transformations and response redaction run off the event loop.
  Oversized or unprocessable upstream responses produce closed errors;
  oversized request bodies return a structured 413 response.
- CPU metrics no longer count guest time twice.
- Installer environment parsing matches the bridge, including quoted and
  exported values and repeated keys. Custom ports and database paths survive
  updates; loopback checks and systemd write permissions follow the configured
  paths.
- Update backups resolve relative database paths against the service user's
  home directory and stop the update if backup fails.

## Maintenance

- Android Fragment 1.9.1, Gradle 9.8.2 and bridge build dependency updates.
- Terminal UI tests synchronize with current resize reports and xterm output
  processing, and invoke the enabled send action independently of keyboard
  layout changes.

## Install and verify

Install `orsik-relay-0.1.0-alpha.15.apk` over Alpha 14; the existing release
signing identity is preserved. `SHA256SUMS` lists the checksum of the signed APK.

Signing certificate SHA-256:
`b252e2d89520b9112ae61fd5ddfb0987e59d6840b3e531db6cb47dc2c865ba51`.
