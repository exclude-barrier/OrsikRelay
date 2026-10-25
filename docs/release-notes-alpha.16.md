# v0.1.0-alpha.16

Reliability and privacy fixes following Alpha 15. The Android protocol is
unchanged; bridge-side pairing changes require a separate bridge update.
Installing the APK does not install or restart the bridge.

## Android fixes

- An explicitly submitted approval decision survives dialog recreation and
  navigation. The current dialog receives the result; ambiguous failures
  never permit a second decision POST.
- Local recovery settles a stale uncertain-run indicator when the run slot
  was already released. A different session's slot stays intact, and a
  healthy replacement run is adopted without releasing it or sending again.
- Management tiles show the existing generic error after task startup fails
  and become usable again. Coroutine cancellation still propagates.
- Host metrics recheck their capability after a failed read, so withdrawn
  support is detected without restarting the app. Healthy polls keep one GET.
- Screen protection shows as enabled and read-only while app lock enforces
  it, with an English/German explanation. The manual preference is preserved.

## Bridge pairing

- Pairing attempts have separate per-peer budgets, with a bounded identity
  table that does not evict another client's active budget.
- Trusted Tailscale Serve deployments can explicitly enable per-user budgets
  with `BRIDGE_TRUST_TAILSCALE_IDENTITY=1`. The option defaults off and never
  replaces one-time pairing codes or device-token authentication.
- Devices of one user share a budget. Missing or tagged identities fall back
  to their transport peer. Arbitrary forwarding headers are ignored.
- Enable the option only after checking the trusted-local-host requirements
  in [the bridge documentation](https://github.com/exclude-barrier/OrsikRelay/blob/main/bridge/README.md#pairing-behind-tailscale-serve).
  The enabled mode has not been verified on the productive deployment.

## Maintenance

- The terminal boundary test waits for the child to enter raw mode before
  sending its 64 KiB input.
- Composer image references render at the actual control's bounds. Exact
  color comparison and the existing threshold remain unchanged; a wrong
  paper-plane symbol is still rejected.

## Install and verify

Version code: `18`. Install the signed `orsik-relay-0.1.0-alpha.16.apk`
over Alpha 15. Unsigned artifacts and CI builds signed with a throwaway key
are not suitable for updating the existing installation.

The signing certificate must match Alpha 15, SHA-256:
`b252e2d89520b9112ae61fd5ddfb0987e59d6840b3e531db6cb47dc2c865ba51`.
