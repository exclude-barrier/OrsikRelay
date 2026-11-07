# Public ingress (own domain, HTTPS + mTLS) – prepared, not active

The private path (Tailscale Serve, [`network-deployment.md`](network-deployment.md))
stays the default. This page describes a **second, public** path through a
domain of your own. Nothing here is switched on by the bridge installer.
Opening port 443 to the internet is a deliberate operator step, and the
go-live checklist at the end comes first.

```text
phone ──HTTPS + client certificate──> Nginx Proxy Manager :443 (relay.example.org)
                                        │ mTLS, path allowlist (deny by default)
                                        ▼
            bridge public listener 172.18.0.1:9843 (Docker bridge) or 127.0.0.1:9843
                                        │ public policy again, device key
                                        ▼
                                 Hermes 127.0.0.1:8642
```

Three independent locks guard every public request:

1. **mTLS at the reverse proxy:** without a client certificate from your own
   CA the TLS handshake fails and no HTTP request is read.
2. **Route policy, twice:** the proxy forwards only the allowlisted paths
   (`deploy/npm/advanced.conf.example`, or `deploy/caddy/Caddyfile.example`
   for a host without NPM). The bridge's public listener enforces the same
   list and the methods itself (`public_ingress_denied`), so a proxy mistake
   does not open the terminal, files, pairing or approvals.
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
# Only when the proxy runs in a Docker network (Nginx Proxy Manager): the
# gateway address of that network, e.g. from
#   docker network inspect npm_dyndns_proxy -f '{{(index .IPAM.Config 0).Gateway}}'
BRIDGE_PUBLIC_BIND=172.18.0.1
```

Without `BRIDGE_PUBLIC_BIND` the listener binds the same loopback address as
`BRIDGE_PORT`. With it, the bridge accepts only the private IPv4 address of a
local Docker bridge interface (`docker*`, `br-*`), never a LAN, public or
wildcard address. A Docker gateway address is reachable from the host and
from containers in that network, not from the LAN; containers there would
still need a device key and get only the public routes. A dedicated Docker
network for the proxy keeps that circle small. The private listener
(`BRIDGE_PORT`) always stays on loopback. `install.sh --check` validates both
values.

If the host runs a firewall such as UFW, it blocks the proxy container from
reaching the listener by default (the request times out at the proxy). Allow
only the proxy network to that one port, for example:

```sh
sudo ufw allow in on br-<network id> from 172.18.0.0/16 to 172.18.0.1 port 9843 proto tcp
```

The interface is `br-` plus the first 12 characters of
`docker network inspect <network> -f '{{.Id}}'`.

## 2. Client CA and one certificate per phone

On an offline or at least trusted machine (keep `orsik-client-ca.key`
off the server):

```sh
# Client CA (10 years). Only the certificate goes to the proxy.
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

The app uses the public address only after it has learned the server ID over
the private path once. Open the app with Tailscale on after saving the public
address; from then on it works without the VPN.

**Losing a phone:** run `admin revoke-device <device_id>` on the server.
The device key is then invalid on every path, even while the certificate is
still valid. The file-based trust pool in this example does not check
revocation lists. Keep certificate lifetimes short, and rotate the client CA
(a new `orsik-client-ca.pem`, new phone certificates) if a certificate
must be invalidated before it expires.

## 3. Nginx Proxy Manager (the hp-server setup)

1. Copy `orsik-client-ca.pem` into the NPM data volume as
   `custom_ssl/orsik-client-ca.pem` (inside the container
   `/data/custom_ssl/orsik-client-ca.pem`).
2. *Hosts → Proxy Hosts → Add*: domain `relay.example.org`, scheme `http`,
   forward host/port = `BRIDGE_PUBLIC_BIND` / `BRIDGE_PUBLIC_PORT`
   (e.g. `172.18.0.1` / `9843`), *Block common exploits* on, no WebSocket
   support needed.
3. *SSL*: request a Let's Encrypt certificate, *Force SSL* and *HTTP/2* on.
4. *Advanced*: paste `deploy/npm/advanced.conf.example` with
   `BRIDGE_PUBLIC_ADDRESS` replaced by `172.18.0.1:9843`. It turns on mTLS
   and, because it defines `location /`, replaces NPM's default location: only
   the listed paths reach the bridge, everything else is 403.
5. Rate limits: copy `deploy/npm/http_top.conf.example` (domain replaced) to
   `/data/nginx/custom/http_top.conf`, then `nginx -t` and `nginx -s reload`
   in the container. Other hosts are not affected (empty limit key).
6. Test from outside the tailnet (mobile data): without the certificate NPM
   answers 400 (`No required SSL certificate was sent`) and forwards nothing;
   with it `GET /v1/server` answers and `/v1/terminal` answers 403.

CI checks the example with `nginx -t` (official `nginx:1.31` image) inside
an NPM-like server block.

## 3b. Caddy (hosts without Nginx Proxy Manager)

Only one of them can own port 443. Copy `deploy/caddy/Caddyfile.example` to `/etc/caddy/Caddyfile`, replace
`relay.example.org`, put `orsik-client-ca.pem` into `/etc/caddy/` and check:

```sh
caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

CI validates the example with the official `caddy:2.11.7` image. Caddy gets
its server certificate through ACME and needs the domain's DNS record
pointing at the host. The example also:

- drops `Tailscale-User-*` headers, so a public client can never pose as a
  Tailscale identity;
- bounds request bodies to 10 MB, like the bridge;
- logs as JSON without credentials (keep the global option
  `log_credentials` off).

## 4. Go-live checklist (all must be true before the public host goes live)

- [ ] The Rust bridge runs on the server, and the contract suite passed for that version.
- [ ] `BRIDGE_PUBLIC_PORT` set, `install.sh --check` passes, and the public listener answers `public_ingress_denied` for `/v1/terminal` on loopback.
- [ ] The client CA key is not on the server, and every phone has its own certificate with a short lifetime.
- [ ] The Android app supports the public endpoint with a client certificate (pairing over the private path).
- [ ] Rate limits at the edge: `deploy/npm/http_top.conf.example` as NPM's custom http config (`/data/nginx/custom/http_top.conf`), the `caddy-ratelimit` plugin, or firewall connection limits. Zones alone do nothing: the `limit_req`/`limit_conn` lines must be there too. Check with a burst of 40 parallel requests (with the certificate): some must answer 429. The bridge itself limits pairing (private only), active runs (16) and terminals.
- [ ] The NPM admin interface (port 81) is not reachable from the internet.
- [ ] Firewall: only 443/tcp open publicly, and nothing else of the host.
- [ ] Monitoring: watch the proxy access log (403/421 spikes, handshake failures) and the bridge audit log.
- [ ] Attack tests from outside: no certificate (handshake fails), a foreign certificate (fails), a valid certificate on terminal/files/pair/approval (403), a redirect or traversal attempt (403/404).

Opening the port and the DNS record are operator decisions. Neither the bridge
installer nor this repository does that.
