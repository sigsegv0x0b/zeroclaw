#!/usr/bin/env node
// Smoke test for the identity-based gateway mTLS transport (`[gateway.mtls]`).
//
// Validates exactly what the design promises:
//   * the server certificate chains to the provided CA;
//   * its identity is the stable URI SAN `spiffe://zeroclaw/gateway/<id>`,
//     not the dialed hostname/IP (so DDNS names and rotating IPs are fine);
//   * a client certificate is mandatory;
//   * a normal HTTP request and, optionally, pairing + session listing work
//     over mTLS.
//
// Usage:
//   node scripts/mtls-smoke.mjs \
//     --host home.example.net --port 42618 \
//     --ca ca.crt --cert client-phone.crt --key client-phone.key \
//     --gateway-id gw_1a2b3c4d5e6f7788 \
//     [--code PAIRING-CODE] [--json]
//
// The host may be a public IP, a DDNS name, or a LAN address. It is only the
// dial target; identity is checked from the certificate.

import { readFileSync } from "node:fs";
import https from "node:https";
import tls from "node:tls";
import { URL } from "node:url";

const args = parseArgs(process.argv.slice(2));
const port = Number(args.port ?? 42618);
const host = args.host;
const ca = readFileSync(required(args.ca, "--ca"), "utf8");
const cert = readFileSync(required(args.cert, "--cert"), "utf8");
const key = readFileSync(required(args.key, "--key"), "utf8");
const gatewayId = required(args["gateway-id"], "--gateway-id");
const expectedIdentity = `URI:spiffe://zeroclaw/gateway/${gatewayId}`;
const timeoutMs = Number(args.timeout ?? 15000);

const failures = [];
const notes = [];

function pass(name, detail = "") {
  console.log(`ok   ${name}${detail ? ` — ${detail}` : ""}`);
}

function fail(name, detail) {
  failures.push(name);
  console.log(`FAIL ${name} — ${detail}`);
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (!token.startsWith("--")) continue;
    const name = token.slice(2);
    const next = argv[i + 1];
    if (next === undefined || next.startsWith("--")) {
      out[name] = true;
    } else {
      out[name] = next;
      i += 1;
    }
  }
  return out;
}

function required(value, flag) {
  if (!value) {
    console.error(`missing ${flag}`);
    process.exit(2);
  }
  return value;
}

function identityOf(certificate) {
  const san = certificate.subjectaltname || "";
  return san;
}

function checkServerIdentity(_hostname, certificate) {
  const san = identityOf(certificate);
  const entries = san.split(", ").map((entry) => entry.trim());
  if (!entries.includes(expectedIdentity)) {
    return new Error(
      `gateway identity mismatch: expected ${expectedIdentity}, certificate SANs are ${san}`,
    );
  }
  return undefined;
}

function request({ path, method = "GET", token, body, withClientCert = true }) {
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
    const req = https.request(
      {
        host,
        port,
        path,
        method,
        ca,
        cert: withClientCert ? cert : undefined,
        key: withClientCert ? key : undefined,
        rejectUnauthorized: true,
        checkServerIdentity,
        timeout: timeoutMs,
        headers: {
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(payload
            ? { "Content-Type": "application/json", "Content-Length": payload.length }
            : {}),
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let json;
          try {
            json = text ? JSON.parse(text) : undefined;
          } catch {
            json = undefined;
          }
          resolve({ status: res.statusCode, text, json, socket: res.socket });
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error(`timeout after ${timeoutMs}ms`)));
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function main() {
  console.log(`dialing ${host}:${port} (address is not part of the identity)`);
  console.log(`expected gateway identity: spiffe://zeroclaw/gateway/${gatewayId}`);

  // 1. TLS + identity + health.
  const health = await request({ path: "/health" });
  const peer = health.socket?.getPeerCertificate?.();
  if (peer) {
    console.log(`peer identity: ${identityOf(peer)}`);
    console.log(`peer fingerprint: sha256 ${(peer.fingerprint256 || "").toLowerCase()}`);
  }
  if (health.status !== 200) {
    fail("health", `HTTP ${health.status}: ${health.text}`);
  } else {
    pass("mTLS handshake + identity SAN", "GET /health => 200");
  }

  // 2. Pair (optional) and list sessions.
  let token = args.token;
  if (!token && args.code) {
    const pair = await request({
      path: "/api/pair",
      method: "POST",
      body: { code: args.code, device_name: "mtls-smoke", device_type: "test" },
    });
    if (pair.status !== 200 || !pair.json?.token) {
      fail("pair", `HTTP ${pair.status}: ${pair.text}`);
    } else {
      token = pair.json.token;
      pass("pairing over mTLS", "token issued");
    }
  }
  if (token) {
    const sessions = await request({ path: "/api/sessions", token });
    if (sessions.status !== 200) {
      fail("sessions", `HTTP ${sessions.status}: ${sessions.text}`);
    } else {
      pass("authenticated API over mTLS", "GET /api/sessions => 200");
    }
  } else {
    notes.push("no --code/--token: skipped pairing and the authenticated API check");
  }

  // 3. A client certificate is mandatory: the same request without one must
  //    fail at or before the TLS handshake.
  await new Promise((resolve) => {
    const req = https.request(
      {
        host,
        port,
        path: "/health",
        ca,
        rejectUnauthorized: true,
        checkServerIdentity,
        timeout: timeoutMs,
      },
      (res) => {
        fail("client cert required", `unexpected HTTP ${res.statusCode} without a client cert`);
        res.resume();
        resolve();
      },
    );
    req.on("timeout", () => req.destroy(new Error("timeout")));
    req.on("error", () => {
      pass("client cert required", "handshake rejected without a client certificate");
      resolve();
    });
    req.end();
  });

  if (notes.length) {
    for (const note of notes) console.log(`note ${note}`);
  }
  if (failures.length) {
    console.error(`\n${failures.length} check(s) failed: ${failures.join(", ")}`);
    process.exit(1);
  }
  console.log("\nall mTLS transport checks passed");
}

main().catch((error) => {
  console.error(`error: ${error.stack || error.message}`);
  process.exit(1);
});
