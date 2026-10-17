# Contributing

Thanks for your interest in Orsik Relay! The project is in early alpha, so
please open an issue to discuss larger changes before you start working on
them.

## Ground rules

- **Never commit secrets** – no API keys, device tokens, pairing codes,
  keystores or passwords, not even in tests. Use obvious placeholders such as
  `sk-test-0123456789abcdef`.
- Security issues go through [SECURITY.md](SECURITY.md), not public issues.
- Keep changes small and focused; one topic per pull request.
- Do not weaken security properties (TLS validation, loopback-only bridge,
  least-privilege device scope, "deny" / "allow once" approvals, no automatic
  retries of write requests) without discussing it first.
- Server features must be detected from `/v1/capabilities`, not from version
  assumptions.

## Development setup

You need JDK 17 and the Android SDK (platform 36, build tools 36.1.0) for the
app, and Python ≥ 3.11 with [`uv`](https://docs.astral.sh/uv/) for the bridge.
See the README for build and test commands.

The app follows the layering Compose UI → ViewModel → use case → repository →
REST/SSE client; UI code does not see network DTOs (`docs/architecture.md`).

## Pull requests

Before opening a pull request, please run locally:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
(cd bridge && uv run --locked python -m unittest discover -s tests)
```

and, if you changed UI or Android behavior, the instrumented tests on an
emulator (see README). Add or update tests for the behavior you change.

All CI checks must pass before a pull request is merged into `main`:

- `Unit tests, lint and debug build`
- `Instrumented tests (API 36)`
- `Release build, SBOM and provenance`
- `Bridge tests and reproducible build`

Bridge dependencies are locked in `bridge/uv.lock`; change them only with
`uv lock --upgrade-package <name>` and explain why in the pull request.

## License

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE), the license of this project.
