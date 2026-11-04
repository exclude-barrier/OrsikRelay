# Public ingress (own domain, HTTPS + mTLS) – prepared, not active

The private path (Tailscale Serve, [`network-deployment.md`](network-deployment.md))
stays the default. This page describes a **second, public** path through a
domain of your own. Nothing here is switched on by the bridge installer.
Opening port 443 to the internet is a deliberate operator step, and the
go-live checklist at the end comes first.

```text
phone ──HTTPS + client certificate──> Caddy :443 (relay.example.org)
                                        │ allowlist (method + path), mTLS
                                        ▼
                         bridge public listener 127.0.0.1:9843
                                        │ public policy again, device key
                                        ▼
                                 Hermes 127.0.0.1:8642
```

Three independent locks guard every public request:

1. **mTLS at Caddy:** without a client certificate from your own CA the TLS
   handshake fails and no HTTP request is read.
2. **Route policy, twice:** Caddy forwards only the allowlisted
   methods/paths (`deploy/caddy/Caddyfile.example`). The bridge's public
   listener enforces the same list itself (`public_ingress_denied`), so a
   Caddy mistake does not open the terminal, files, pairing or approvals.
3. **Device key:** every route except `/health` and `/v1/server` still needs
   the paired device's key, which `admin revoke-device` disables at once.

## Public policy

| Function | Private (Tailscale) | Public (mTLS) |
|---|---|---|
| Pairing | yes | **no** – pair over the private path first |
| Chat, sessions, run status, history | yes | yes |
| Stop / steer / resolve a lost run | yes | yes |
| Approvals | yes | **no** (until a separate threat model allows it) |
| Terminal | with grant | **no** |
| Files / projects | when enabled | **no** |
| Host metrics | yes | **no** |
| Self-revoke | yes | yes |
| Administration | local CLI only | **no** |

## 1. Bridge: the public listener

In `$BRIDGE_HOME/bridge.env`, then restart the bridge:

```sh
BRIDGE_PUBLIC_PORT=9843
```

The listener binds the same loopback address as `BRIDGE_PORT`; it is never
reachable from outside the host by itself. `install.sh --check` validates the
value.

## 2. Client CA and one certificate per phone

On an offline or at least trusted machine (keep `orsik-client-ca.key`
off the server):

```sh
# Client CA (10 years). Only the certificate goes to Caddy.
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout orsik-client-ca.key -out orsik-client-ca.pem -days 3650 \
  -subj "/CN=Orsik Relay client CA" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"

# One phone (short lifetime, renew before it ends).
openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
  -keyout pixel.key -out pixel.csr -subj "/CN=pixel-9-pro"
openssl x509 -req -in pixel.csr -CA orsik-client-ca.pem -CAkey orsik-client-ca.key \
  -CAcreateserial -out pixel.pem -days 90 \
  -extfile <(printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=clientAuth\n')

# Bundle for Android (you are asked for an export password).
openssl pkcs12 -export -inkey pixel.key -in pixel.pem -certfile orsik-client-ca.pem \
  -name "Orsik Relay" -out pixel.p12
rm pixel.key pixel.csr
```

On the phone: *Settings → Security & privacy → More security settings →
Encryption & credentials → Install a certificate → VPN & app user
certificate*, then select `pixel.p12`. The private key stays in the Android
key store. The app later asks you to select this certificate for the public
endpoint of the server profile (Android `KeyChain`).

**Losing a phone:** run `admin revoke-device <device_id>` on the server.
The device key is then invalid on every path, even while the certificate is
still valid. The file-based trust pool in this example does not check
revocation lists. Keep certificate lifetimes short, and rotate the client CA
(a new `orsik-client-ca.pem`, new phone certificates) if a certificate
must be invalidated before it expires.

## 3. Caddy

Copy `deploy/caddy/Caddyfile.example` to `/etc/caddy/Caddyfile`, replace
`relay.example.org`, put `orsik-client-ca.pem` into `/etc/caddy/` and check:

```sh
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

CI validates the example with the official `caddy:2.11.8` image. Caddy gets
its server certificate through ACME and needs the domain's DNS record
pointing at the host. The example also:

- drops `Tailscale-User-*` headers, so a public client can never pose as a
  Tailscale identity;
- bounds request bodies to 10 MB, like the bridge;
- logs as JSON without credentials (keep the global option
  `log_credentials` off).

## 4. Go-live checklist (all must be true before opening port 443)

- [ ] The Rust bridge runs on the server, and the contract suite passed for that version.
- [ ] `BRIDGE_PUBLIC_PORT` set, `install.sh --check` passes, and the public listener answers `public_ingress_denied` for `/v1/terminal` on loopback.
- [ ] The client CA key is not on the server, and every phone has its own certificate with a short lifetime.
- [ ] The Android app supports the public endpoint with a client certificate (pairing over the private path).
- [ ] Rate limits at the edge: core Caddy has none. Use the `caddy-ratelimit` plugin or firewall connection limits. The bridge itself limits pairing (private only), active runs (16) and terminals.
- [ ] Firewall: only 443/tcp open publicly, and nothing else of the host.
- [ ] Monitoring: watch the Caddy access log (403/421 spikes, handshake failures) and the bridge audit log.
- [ ] Attack tests from outside: no certificate (handshake fails), a foreign certificate (fails), a valid certificate on terminal/files/pair/approval (403), a redirect or traversal attempt (403/404).

Opening the port and the DNS record are operator decisions. Neither the bridge
installer nor this repository does that.
