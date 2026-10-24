import assert from "node:assert/strict";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { generateKeyPairSync, randomUUID } from "node:crypto";
import type { Database } from "./database.js";
import { initializeUploadLimits, UploadLimiter, UploadThrottled } from "./upload-limits.js";
import { buildApp } from "./app.js";
import { SigningIdentity } from "./security.js";
import { createPacket } from "./protocol.js";

const adapter = (pg: PGlite): Database => ({ query: (sql, values) => pg.query(sql, values),
  transaction: work => pg.transaction(c => work(c)), close: () => pg.close() });
const pem = () => generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ format: "pem", type: "pkcs8" }).toString();

test("upload budgets count packets atomically by device, shared IP and server and survive limiter replacement", async () => {
  const pg = new PGlite(), db = adapter(pg); let clock = 1792860000000;
  await initializeUploadLimits(db);
  try {
    const limits = { perDevice: 3, perIp: 4, global: 6 };
    const limiter = new UploadLimiter(db, limits, () => clock);
    await limiter.admit("phone-a", "shared-ip", 3);
    await assert.rejects(limiter.admit("phone-a", "shared-ip", 1), UploadThrottled);
    await limiter.admit("phone-b", "shared-ip", 1);
    await assert.rejects(limiter.admit("phone-b", "shared-ip", 1), UploadThrottled);
    await limiter.admit("phone-b", "another-ip", 2); // An IP rejection did not consume the device's budget.
    await assert.rejects(limiter.admit("phone-c", "third-ip", 1), UploadThrottled);
    const restored = new UploadLimiter(db, limits, () => clock);
    await assert.rejects(restored.admit("phone-c", "third-ip", 1), UploadThrottled);
    clock += 60001;
    const concurrent = await Promise.allSettled(Array.from({ length: 4 }, () => restored.admit("phone-c", "third-ip", 1)));
    assert.equal(concurrent.filter(r => r.status === "fulfilled").length, 3);
    assert.equal(concurrent.filter(r => r.status === "rejected").length, 1);
    const stored = JSON.stringify((await pg.query("SELECT scope FROM upload_windows")).rows);
    assert.doesNotMatch(stored, /shared-ip|another-ip|third-ip/);
  } finally { await pg.close(); }
});

test("enrolled uploads are bounded across renewal, return retryable 429, and recover without dropping or poisoning reports", async () => {
  const pg = new PGlite(); let clock = 1792860000000;
  const phone = SigningIdentity.fromPem(pem());
  const app = buildApp({ database: adapter(pg), cloudSigningKeyPem: pem(), now: () => clock,
    uploadLimits: { perDevice: 2, perIp: 10, global: 20 } });
  async function enroll(): Promise<string> {
    const c = (await app.inject({ method: "POST", url: "/v1/gateway/challenge", payload: { public_key: phone.publicKey.toString("base64url") } })).json();
    const result = await app.inject({ method: "POST", url: "/v1/gateway/enroll", payload: {
      challenge_bytes: c.signed_bytes, signature: phone.sign(Buffer.from(c.signed_bytes, "base64url")).toString("base64url") } });
    assert.equal(result.statusCode, 200);
    return JSON.parse(Buffer.from(result.json().signed_bytes, "base64url").toString().split("\n").slice(1).join("\n")).token;
  }
  const packet = () => createPacket(phone, 1, { request_id: randomUUID(), category: 3, description: "Need water",
    consent: { public_mesh_allowed: true, cloud_processing_allowed: true } },
  { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: clock, expiresAtMs: clock + 3600000 });
  const upload = (token: string, packets: ReturnType<typeof packet>[], key: string = randomUUID()) => app.inject({ method: "POST", url: "/v1/sync/upload",
    headers: { authorization: `Bearer ${token}` }, payload: { idempotency_key: key, packets } });
  try {
    const token = await enroll();
    assert.equal((await upload(token, [packet(), packet()])).statusCode, 200);
    const renewed = await enroll();
    const saved = packet(); const response = await upload(renewed, [saved], "retry-after-limit");
    assert.equal(response.statusCode, 429); assert.equal(response.json().retryable, true);
    assert.ok(Number(response.headers["retry-after"]) >= 1);
    assert.equal((await pg.query("SELECT * FROM sos_reports")).rows.length, 2);
    assert.equal((await pg.query("SELECT * FROM sync_uploads WHERE idempotency_key = 'retry-after-limit'")).rows.length, 0);
    clock += 60001;
    assert.equal((await upload(renewed, [saved], "retry-after-limit")).statusCode, 200);
    assert.equal((await pg.query("SELECT * FROM sos_reports")).rows.length, 3);
  } finally { await app.close(); }
});
