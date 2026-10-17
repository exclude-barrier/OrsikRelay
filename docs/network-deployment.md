# Hermes Android – Network Deployment

## Goal

The app should work with self-operated Hermes instances without being tied to a
specific VPN provider or a specific server domain. It remains a thin client:
Hermes and all privileged tools run on the server.

## Connection contract

The app requires, per server profile:

- an absolute HTTPS base URL;
- a Hermes bearer key;
- a route reachable via Android system routing and system DNS.

Before saving, the app checks `GET /health` and the authenticated
`GET /v1/capabilities`. The URL and uncritical profile metadata are stored
separately from the key. The key is encrypted with an AES-256-GCM key from the Android Keystore;
the private SharedPreferences contain exclusively versioned ciphertext and IV.

The app contains no WireGuard or Tailscale SDK and detects no VPN
provider. VPN setup, routing, and DNS remain the task of Android and the
respective VPN app. As a result, LAN, WireGuard, and later Tailscale work through the same
HTTPS client.

## Security boundaries

- No cleartext HTTP and no trust-all/TLS bypass.
- Currently only certificates that Android trusts via the normal system trust
  store. Private or self-signed CAs are not supported in the first Play
  Store slice.
- No direct port forwarding of the Hermes API to the internet.
- The Hermes bearer remains mandatory even behind a VPN or reverse proxy.
- Reverse proxies must not remove the `Authorization` header or redirect it to a different target.
- No automatic confirmation of Hermes approvals.
- No request/response body logs, keys, session contents, or private server data in telemetry or crash reports.
- No automatic retry of writing or risky requests.

## Supported deployment patterns

### 1. LAN with HTTPS

The server is reachable exclusively on the local network. A reverse proxy provides an HTTPS domain
with a publicly trusted certificate. The domain can point to the private address
via split DNS or a local resolver.

Suitable for: use exclusively in the home or company network.

### 2. WireGuard to a private network

Outside the site, the smartphone establishes a WireGuard tunnel to the private network. The app then uses the same HTTPS domain
as in the LAN.

Recommended server side:

1. Hermes API remains bound to loopback.
2. An internal bridge/socket proxy makes it reachable only for the reverse proxy.
3. The reverse proxy terminates publicly trusted TLS.
4. A source network allowlist permits exclusively the actually used LAN/WireGuard source networks and denies all other sources.
5. The Hermes bearer is passed unchanged to the API and additionally checked there.

Suitable for: current private reference deployment.

### 3. Tailscale Serve

Tailscale can later be used as an alternative routing model. Tailscale Serve terminates HTTPS within a tailnet
and forwards to the loopback-bound Hermes API. The Android app remains
unchanged; the user merely enters the HTTPS domain of the tailnet service.
Tailscale Funnel is not intended for the private Hermes API.

Suitable for: users who already operate a tailnet or do not want to configure their own reverse proxy.

### 4. Standalone mobile/OIDC gateway

Optionally, a purpose-built gateway can later offer OIDC/PKCE for mobile clients and communicate with Hermes server-side.
Authentik Forward Auth directly in front of the bearer API is not automatically compatible,
because browser login and the Hermes bearer are two different authentication contracts.

Suitable for: managed multi-user installations; not part of the first private slice.

## Unsupported patterns

- Hermes API directly on a public host port forward;
- public Tailscale Funnel exposure;
- cleartext HTTP in the LAN or VPN;
- disabled certificate verification;
- embedding a fixed domain, a VPN provider, or an API key in the app;
- automatic takeover of reverse proxy, VPN, or OIDC administration tasks by the app.

## Setup checklist for users

1. Start the Hermes API and configure a separate bearer key.
2. Choose the private network path: LAN, WireGuard, or Tailscale.
3. Set up an HTTPS reverse proxy or Tailscale Serve.
4. Verify a valid certificate trusted by Android.
5. Ensure that the Hermes API does not listen directly on the public internet.
6. For an IP allowlist, test the actual source networks, do not guess.
7. In the app, enter the profile name, HTTPS base URL, and bearer key.
8. Run the connection test; the profile is saved only after a successful capability check.

## Verification

Positive tests:

- Wi-Fi/LAN with a valid certificate;
- mobile network with active WireGuard;
- later Tailscale Serve in the tailnet;
- successful health and capability check;
- profile is saved only after success.

Negative tests:

- HTTP URL is rejected locally;
- unknown or invalid certificate produces a TLS error;
- wrong bearer key returns `401`/`403` and stores nothing;
- access without the required VPN tunnel fails;
- a source outside the reverse proxy allowlist is rejected before the backend;
- redirect is not followed;
- timeout and network errors are displayed without server or exception details.

## Rollback

- Deactivate or remove the reverse proxy host.
- Stop and disable the internal bridge.
- Hermes API remains unchanged on loopback and is afterwards reachable only locally again.
- Delete the app profile; this separately removes metadata and the Keystore credential.
- Revert the VPN configuration independently of the app.

## Play Store and privacy requirements

- No hard-coded personal infrastructure.
- No advertising, tracking, or analytics SDKs in the first release.
- No transmission of the bearer key to third parties.
- No sensitive content in logs or crash reports.
- Privacy policy and Play Data Safety declarations must match the actually built behavior.
- Release signing, backup rules, and data deletion are checked separately before publication.
