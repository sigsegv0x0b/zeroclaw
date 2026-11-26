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
# Setting this disables in-band enrollment (issued leaves would not be pinned).
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
| `issued.json` | Ledger: device id, fingerprint, expiry, bound token hash |
| `revoked` | CRL: one revoked SHA-256 fingerprint per line |

Set `ZEROCLAW_CA_PASSPHRASE` (or `ZEROCLAW_CA_PASSPHRASE_FILE`) before the
first start to encrypt `ca.key` at rest. An existing plaintext key keeps
working; the gateway needs the passphrase at every start to sign certificates.

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

## One-screen enrollment in the Android app

The app bootstraps itself from one pairing code. Keep `enroll = false` in the
configuration and let the pairing command open the enrollment listener only for
the pairing window:

1. On the gateway: `zeroclaw pair --host <public-host-or-ddns> --qr
   --pair-timeout 120`.
   It asks the running gateway to bind the enrollment port, then prints the
   pairing code, SAS, gateway id, mTLS/enrollment URLs, and a scannable QR
   payload. On a terminal it then shows a live countdown: the port closes when
   the timeout expires, when the first enrollment succeeds, after 20 wrong
   pairing codes, or immediately when the operator presses `q`. The timeout is
   capped at 600 seconds by the pairing code lifetime. The same status screen
   is shown with or without `--qr`.
2. In the app's **Secure setup (mTLS)** section, scan the QR (or enter the
   host and pairing code).
3. The app fetches `/enroll/ca` over provisional TLS. That request carries no
   code or token. The QR carries the full CA fingerprint (`ca`), the gateway
   id (`gw`) and the SAS; a scan checks all three and stops on any mismatch.
   For a typed code the app shows the SAS, which the operator compares with
   the gateway console.
4. The app shows the gateway host, id and SAS and asks for confirmation
   either way, since the paired gateway can use the phone's capabilities.
5. The app generates a non-exportable P-256 key in the Android Keystore and
   posts the CSR with the pairing code over a connection pinned to that CA.
   The gateway returns the signed client certificate, CA, gateway id, and a
   bearer token bound to that certificate, and the app connects over mTLS.

The pairing code is single use. Enrollment and the bearer token come from the
same exchange, so there is no second code to enter. A request that fails
(a malformed CSR, a disk error) leaves the code usable and mints no token.

Closing a timed window, for any reason, also ends the connections it accepted
and expires the pairing code minted for it, on `/pair` as well. A permanent
window (`enroll = true`) leaves the gateway pairing code alone.

`--enroll-port <port>` overrides the enrollment listener and the port advertised
in the QR for that window. It requires `--pair-timeout`; without it the
configured `gateway.mtls.enroll_port` is used.

`--pair-timeout` requires `[gateway.mtls]` enabled and a running gateway on the
same host, because it uses the localhost-only admin token to open the window.
Without the flag, `enroll = true` keeps the port open from gateway startup
until a restart.

## Renewal

Client certificates live for 30 days. The app renews at half the lifetime over
the still-valid connection: it sends a fresh CSR (signed by a new Keystore key)
to `POST /api/mtls/renew` with the current client certificate and its bound
bearer token. The gateway signs a new leaf for the same device and returns it
with the CA. The old certificate stays valid until the new one completes its
first handshake, then it is revoked and its connections are closed. A response
lost in transit is therefore retried with the old certificate; the retry
revokes the replacement that was never used. The CA, server leaf and gateway
identity do not change, so peers do not need to re-pair.

Renewal accepts only certificates recorded in the ledger. A leaf issued with
`zeroclaw security issue-client-cert` is not renewable; issue a new one.

Renewal is not a periodic poll. A single deferred WorkManager job is armed for
the midpoint (batched with other background work and surviving reboots); if the
app is open at or after the midpoint, it renews before connecting instead.
**Settings → Transport → Renew certificate** triggers it manually. The stored
certificate and expiry are shown in the same section.

## Managing issued certificates

```sh
zeroclaw mtls list
zeroclaw mtls delete --device dev-1a2b3c4d
zeroclaw mtls delete --fingerprint <sha256>
```

`mtls list` reads the issued-certificate ledger. `mtls delete` revokes the
certificate in the running gateway, cancels its live connections, and appends
it to the on-disk CRL so a restart keeps it revoked. When a device has no live
certificate left, its bearer token is revoked and its row leaves the paired
device list. The command is loopback-only and sends the gateway admin token, so
run it on the gateway host as the gateway's user.

If the gateway is not running, `mtls delete` resolves the device's
fingerprints from the ledger and appends them to the CRL; the next start
applies the revocation, the token included.

Removing an mTLS device from the paired-device list revokes its certificates
too.

## Token binding

The bearer token an enrollment returns is bound to that device's certificates.
The gateway refuses it on any listener without a client certificate (the plain
gateway port, a relay-served dashboard) and with another device's
certificate, and it refuses a bound certificate presented with a different
token. Tokens from `/pair` and certificates issued out of band are unbound and
behave as before.

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
- The CA is the trust anchor and must be online for enrollment and renewal.
  Protect `ca.key` with `ZEROCLAW_CA_PASSPHRASE`; anyone holding it can mint
  any client identity.
- The bearer pairing token remains the authorization credential. The client
  certificate proves the device; it does not replace pairing. Enrollment
  tokens are bound to their certificates (see Token binding).
- Revocation: `zeroclaw mtls delete`, or list the leaf's SHA-256 fingerprint
  in the CRL file and restart.
- On Android the client key lives in the Keystore and cannot be exported. The
  bearer token is sealed with a Keystore key, and the app's storage is
  excluded from backup and device transfer, so a pairing never follows a
  backup to another phone.
- With mTLS on, the app never falls back to a plain connection: broken
  material fails the request instead of sending the token elsewhere.
- Do not put IPs or DNS names in the identity. Use them only in the client's
  dial address.
