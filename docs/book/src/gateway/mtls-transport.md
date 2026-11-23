# Identity-based mTLS transport

`[gateway.mtls]` adds a second listener beside the normal gateway. It serves
the same HTTP and WebSocket API, but requires a client certificate on every
connection. The normal transport is unchanged and keeps its configured mode
(plain or `[gateway.tls]`).

This transport exists for remote access **without a public CA and without
binding a certificate to an address**. The server certificate identifies the
gateway with a generated URI SAN, and the client certificate identifies the
device. Public IPs, DDNS names, and LAN addresses are only dial targets; they
can change without invalidating anything.

## Configuration

```toml
[gateway.mtls]
enabled = true
bind = "127.0.0.1"
port = 42618
# Empty means <data_dir>/gateway-mtls.
# dir = "/var/lib/zeroclaw/gateway-mtls"
# Optional: additionally restrict accepted client leaves by SHA-256 pin.
# pinned_client_certs = []
# crl_path = "<data_dir>/gateway-mtls/revoked"
```

On first start with the listener enabled, ZeroClaw writes under the material
directory (mode `0700`, keys `0600`):

| File | Purpose |
|---|---|
| `ca.crt` / `ca.key` | Per-gateway CA (never silently rotated) |
| `server.crt` / `server.key` | Server leaf, SAN `spiffe://zeroclaw/gateway/<id>` only |
| `gateway_id` | Stable generated identity (`gw_<16 hex>`) |

The server leaf carries **no DNS or IP SANs**. Clients verify the pinned CA and
the URI identity, not the address they dialed.

## Issuing a client certificate

```sh
# 1. Find the gateway identity (printed at startup and stored on disk).
cat <data_dir>/gateway-mtls/gateway_id

# 2. Issue a clientAuth leaf signed by the gateway CA.
zeroclaw security issue-client-cert \
  --name phone \
  --ca-dir <data_dir>/gateway-mtls \
  --out-dir /tmp/phone-mtls \
  --force
```

The client keeps `client-phone.key` private and presents `client-phone.crt`
plus `ca.crt`.

## Validating with the Node client

`tests/manual/mtls-smoke.mjs` checks the handshake, the URI identity, the mandatory
client certificate, and (optionally) pairing plus an authenticated API call:

```sh
node tests/manual/mtls-smoke.mjs \
  --host home.example.net --port 42618 \
  --ca /tmp/phone-mtls/ca.crt \
  --cert /tmp/phone-mtls/client-phone.crt \
  --key /tmp/phone-mtls/client-phone.key \
  --gateway-id "$(cat <data_dir>/gateway-mtls/gateway_id)" \
  --code "$(zeroclaw gateway get-paircode --new --json | jq -r .pairing_code)"
```

The script uses the host only to dial. A different address (public IP, DDNS,
LAN) produces the same successful identity check.

## Security notes

- TLS 1.3 only; client certificates are mandatory (`clientAuth` EKU).
- The CA is the trust anchor. Keep `ca.key` offline or protected; it can mint
  any client identity.
- The bearer pairing token remains the authorization credential. The client
  certificate proves the device; it does not replace pairing.
- Revocation: list the leaf's SHA-256 fingerprint in the configured CRL file,
  or issue a fresh client certificate after removing the device.
- Do not put IPs or DNS names in the identity. Use them only in the client's
  dial address.
