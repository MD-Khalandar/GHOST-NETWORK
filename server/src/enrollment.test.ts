import assert from "node:assert/strict";
import { generateKeyPairSync, randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { buildApp } from "./app.js";
import { createPacket } from "./protocol.js";
import { SigningIdentity, tokenHash, verifySignature } from "./security.js";
import type { Database } from "./database.js";
import { ENROLLMENT_REQUESTS_PER_IP_MINUTE } from "./enrollment.js";

const pem = () => generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ type: "pkcs8", format: "pem" }).toString();
const cloudPem = pem(), cloud = SigningIdentity.fromPem(cloudPem);
const phone = SigningIdentity.fromPem(pem()), other = SigningIdentity.fromPem(pem());
const viewerToken = "v".repeat(43);
const adapter = (pg: PGlite): Database => ({ query: (sql, args) => pg.query(sql, args),
  transaction: work => pg.transaction(connection => work(connection)), close: () => pg.close() });
const appFor = (pg: PGlite, now: () => number) => buildApp({ database: adapter(pg), cloudSigningKeyPem: cloudPem, now,
  principals: [{ id: "viewer", role: "viewer", token_sha256: tokenHash(viewerToken) }] });
const bytes = (response: any) => Buffer.from(response.signed_bytes, "base64url");
const payload = (response: any) => JSON.parse(bytes(response).toString().split("\n").slice(1).join("\n"));
const headers = (token: string) => ({ authorization: `Bearer ${token}` });
async function challenge(app: ReturnType<typeof buildApp>, key = phone) {
  const response = await app.inject({ method: "POST", url: "/v1/gateway/challenge", payload: { public_key: key.publicKey.toString("base64url") } });
  assert.equal(response.statusCode, 200);
  const reply = response.json();
  assert.equal(verifySignature(cloud.publicKey, bytes(reply), Buffer.from(reply.signature, "base64url")), true);
  return reply;
}
async function enroll(app: ReturnType<typeof buildApp>, key = phone) {
  const c = await challenge(app, key);
  const r = await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: {
    challenge_bytes: c.signed_bytes, signature: key.sign(bytes(c)).toString("base64url") } });
  assert.equal(r.statusCode, 200);
  assert.equal(verifySignature(cloud.publicKey, bytes(r.json()), Buffer.from(r.json().signature, "base64url")), true);
  const value = payload(r.json());
  assert.equal(value.node_id, key.nodeId);
  return value;
}

test("a device enrolls without a preissued token, uploads relayed signed SOS, and never gains dashboard authority", async () => {
  let now = 1792860000000;
  const pg = new PGlite(), app = appFor(pg, () => now);
  try {
    const session = await enroll(app);
    const identity = await app.inject({ method: "GET", url: "/v1/auth/session", headers: headers(session.token) });
    assert.equal(identity.statusCode, 200); assert.equal(identity.json().role, "gateway");
    assert.equal(identity.json().gateway_node_id, phone.nodeId);
    for (const path of ["sos", "helpers", "offers", "runs", "proposals"])
      assert.equal((await app.inject({ method: "GET", url: `/v1/dashboard/${path}`, headers: headers(session.token) })).statusCode, 403);
    assert.equal((await app.inject({ method: "POST", url: `/v1/dashboard/sos/${randomUUID()}/coordinate`, headers: headers(session.token) })).statusCode, 403);
    const packet = createPacket(other, 1, { request_id: randomUUID(), category: 3, description: "Need drinking water",
      consent: { public_mesh_allowed: true, cloud_processing_allowed: true } }, { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now, expiresAtMs: now + 3600000 });
    const upload = { idempotency_key: "auto-upload", packets: [packet] };
    const saved = await app.inject({ method: "POST", url: "/v1/sync/upload", headers: headers(session.token), payload: upload });
    assert.equal(saved.statusCode, 200); assert.equal(saved.json().results[0].status, "ACCEPTED");
    assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", headers: headers(session.token), payload: upload })).json().results[0].status, "ACCEPTED");
    assert.equal((await pg.query("SELECT * FROM sos_reports")).rows.length, 1);
    assert.equal((await app.inject({ method: "GET", url: "/v1/dashboard/sos", headers: headers(viewerToken) })).statusCode, 200);
    const privatePacket = createPacket(other, 1, { request_id: randomUUID(), category: 3, description: "Keep local",
      consent: { public_mesh_allowed: true, cloud_processing_allowed: false } }, { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now, expiresAtMs: now + 3600000 });
    assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", headers: headers(session.token),
      payload: { idempotency_key: "private", packets: [privatePacket] } })).json().code, "CONSENT_REQUIRED");
    const stored = (await pg.query<{ token_sha256: string }>("SELECT token_sha256 FROM gateway_devices")).rows[0];
    assert.equal(stored.token_sha256, tokenHash(session.token)); assert.notEqual(stored.token_sha256, session.token);
    now += 2000;
    const renewed = await enroll(app);
    assert.equal((await app.inject({ method: "GET", url: "/v1/auth/session", headers: headers(session.token) })).statusCode, 401);
    assert.equal((await app.inject({ method: "GET", url: "/v1/auth/session", headers: headers(renewed.token) })).statusCode, 200);
    now = renewed.expires_at_ms;
    assert.equal((await app.inject({ method: "GET", url: "/v1/auth/session", headers: headers(renewed.token) })).statusCode, 401);
  } finally { await app.close(); }
});

test("enrollment rejects another key, changed challenge bytes, replay, expired challenges and extra role fields", async () => {
  let now = 1792860000000;
  const app = appFor(new PGlite(), () => now);
  try {
    const c = await challenge(app);
    const proof = { challenge_bytes: c.signed_bytes, signature: phone.sign(bytes(c)).toString("base64url") };
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: { ...proof, signature: other.sign(bytes(c)).toString("base64url") } })).statusCode, 401);
    const changed = Buffer.from(bytes(c).toString().replace(phone.nodeId, other.nodeId));
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: {
      challenge_bytes: changed.toString("base64url"), signature: phone.sign(changed).toString("base64url") } })).statusCode, 401);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: { ...proof, role: "operator" } })).statusCode, 400);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: proof })).statusCode, 200);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: proof })).statusCode, 401);
    const expired = await challenge(app);
    now += 60001;
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: {
      challenge_bytes: expired.signed_bytes, signature: phone.sign(bytes(expired)).toString("base64url") } })).statusCode, 401);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/challenge", payload: { public_key: "bad" } })).statusCode, 400);
  } finally { await app.close(); }
});

test("device credentials survive service/database restart and revocation prevents re-enrollment", async () => {
  const dir = await mkdtemp(join(tmpdir(), "ghost-enrollment-"));
  const now = 1792860000000;
  let app = appFor(new PGlite(dir), () => now);
  try {
    const session = await enroll(app);
    await app.close();
    const pg = new PGlite(dir); app = appFor(pg, () => now);
    assert.equal((await app.inject({ method: "GET", url: "/v1/auth/session", headers: headers(session.token) })).statusCode, 200);
    await pg.query("UPDATE gateway_devices SET revoked=TRUE WHERE node_id=$1", [phone.nodeId]);
    assert.equal((await app.inject({ method: "GET", url: "/v1/auth/session", headers: headers(session.token) })).statusCode, 401);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/challenge", payload: { public_key: phone.publicKey.toString("base64url") } })).statusCode, 403);
  } finally { await app.close(); await rm(dir, { recursive: true, force: true }); }
});

test("handshake endpoints are bounded and report missing storage honestly", async () => {
  const unavailable = buildApp();
  try { assert.equal((await unavailable.inject({ method: "POST", url: "/v1/gateway/challenge", payload: {} })).statusCode, 503); }
  finally { await unavailable.close(); }
  let now = 1792860000000;
  const app = appFor(new PGlite(), () => now);
  try {
    for (let i=0;i<ENROLLMENT_REQUESTS_PER_IP_MINUTE;i++) assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/challenge", payload: {} })).statusCode, 400);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/challenge", payload: {} })).statusCode, 429);
    now += 60001;
    await challenge(app);
    const large = await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: { challenge_bytes: "A".repeat(10000), signature: "AA" } });
    assert.equal(large.statusCode, 413);
  } finally { await app.close(); }
});

test("another caller cannot replace a device's pending challenge, and repeated requests stay bounded", async () => {
  const pg = new PGlite(); const app = appFor(pg, () => 1792860000000);
  try {
    const first = await challenge(app);
    const otherCaller = await app.inject({ method: "POST", url: "/v1/gateway/challenge", remoteAddress: "198.51.100.2",
      payload: { public_key: phone.publicKey.toString("base64url") } });
    assert.equal(otherCaller.statusCode, 200);
    assert.equal(otherCaller.json().signed_bytes, first.signed_bytes);
    assert.equal((await pg.query("SELECT * FROM gateway_challenges")).rows.length, 1);
    const proof = { challenge_bytes: first.signed_bytes, signature: phone.sign(bytes(first)).toString("base64url") };
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: proof })).statusCode, 200);
    assert.equal((await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: proof })).statusCode, 401);
  } finally { await app.close(); }
});

test("a shared Wi-Fi or carrier IP can enroll many phones without the old fifteen-phone cutoff", async () => {
  const app = appFor(new PGlite(), () => 1792860000000);
  try {
    for (let index = 0; index < 30; index++) await enroll(app, SigningIdentity.fromPem(pem()));
  } finally { await app.close(); }
});
