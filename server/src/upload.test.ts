import assert from "node:assert/strict";
import { generateKeyPairSync, randomUUID } from "node:crypto";
import { mkdir, mkdtemp, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { buildApp } from "./app.js";
import type { Database } from "./database.js";
import { SosStore } from "./sos-store.js";
import { createPacket, validatePacket, decode, type WirePacket } from "./protocol.js";
import { SigningIdentity, tokenHash, type Principal } from "./security.js";

const now = 1792860000000;
const pem = () => generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ format: "pem", type: "pkcs8" }).toString();
const cloudPem = pem(), cloud = SigningIdentity.fromPem(cloudPem), origin = SigningIdentity.fromPem(pem());
const token = "g".repeat(43);
const principals: Principal[] = [{ id: "gateway", role: "gateway", token_sha256: tokenHash(token), gateway_node_id: "a".repeat(64) }];
const headers = { authorization: `Bearer ${token}` };
const make = (description = "Need water'; SELECT 1;", requestId = randomUUID(), consent = true, ids = { packetId: randomUUID(), eventId: randomUUID() }) =>
  createPacket(origin, 1, { request_id: requestId, category: 3, description,
    consent: { public_mesh_allowed: true, cloud_processing_allowed: consent } }, {
    ...ids, createdAtMs: now - 1000, expiresAtMs: now + 21600000,
  });
const adapter = (pg: PGlite): Database => ({
  query: (sql, values) => pg.query(sql, values),
  transaction: work => pg.transaction(connection => work(connection)),
  close: () => pg.close(),
});
const appFor = (database: Database) => buildApp({ principals, cloudSigningKeyPem: cloudPem, database, now: () => now });
const upload = (app: ReturnType<typeof buildApp>, packets: WirePacket[], key: string = randomUUID()) =>
  app.inject({ method: "POST", url: "/v1/sync/upload", headers, payload: { idempotency_key: key, packets } });

test("owner-signed cancellations and resolutions arrive before SOS, deduplicate, and cannot be undone by a late original", async () => {
  const pg = new PGlite(); const app = appFor(adapter(pg));
  try {
    for (const state of [5, 6]) {
      const original = make(); const base = decode("ImmutableEnvelopeV1", Buffer.from(original.immutable_envelope_bytes, "base64url"));
      const closureData = { request_id: decode("SosCreateV1", base.payload_bytes).request_id, state,
        original_sos_envelope: Buffer.from(original.immutable_envelope_bytes, "base64url"), original_sos_signature: Buffer.from(original.signature, "base64url") };
      const close = (data = closureData, signer = origin, expiry = base.expires_at_ms) => createPacket(signer, 2, data,
        { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now, expiresAtMs: expiry });
      const stranger = SigningIdentity.fromPem(pem());
      assert.equal((await upload(app, [close(closureData, stranger)])).json().code, "INVALID_ACTOR");
      assert.equal((await upload(app, [close({ ...closureData, original_sos_signature: Buffer.alloc(70) })])).json().code, "BAD_SIGNATURE");
      assert.equal((await upload(app, [close(closureData, origin, base.expires_at_ms + 1)])).json().code, "INVALID_PAYLOAD");
      const packet = close(); const first = await upload(app, [packet], `closure-${state}`);
      assert.equal(first.statusCode, 200, first.body);
      const ack = validatePacket(first.json().packets[0], { nowMs: now, trustedCloudNodeId: cloud.nodeId });
      assert.equal(ack.payload.origin_event_id, first.json().results[0].event_id);
      assert.equal(ack.envelope.destination.node_id.toString("hex"), origin.nodeId);
      assert.deepEqual((await upload(app, [packet], `closure-${state}`)).json(), first.json());
      assert.equal((await upload(app, [packet])).json().results[0].status, "DUPLICATE");
      assert.equal((await upload(app, [original])).statusCode, 200);
      const saved = (await pg.query<{ state: number; immutable_envelope: Uint8Array }>("SELECT state, immutable_envelope FROM sos_reports WHERE request_id = $1", [closureData.request_id])).rows[0];
      assert.equal(saved.state, state);
      assert.equal((await new SosStore(adapter(pg), cloud, () => now).find(closureData.request_id))!.state, state);
      assert.deepEqual(Buffer.from(saved.immutable_envelope), Buffer.from(original.immutable_envelope_bytes, "base64url"));
      assert.equal((await pg.query("SELECT * FROM sos_closures WHERE request_id = $1", [closureData.request_id])).rows.length, 1);
    }
    const privateReport = make("Nearby only", randomUUID(), false);
    const base = decode("ImmutableEnvelopeV1", Buffer.from(privateReport.immutable_envelope_bytes, "base64url"));
    const closed = createPacket(origin, 2, { request_id: decode("SosCreateV1", base.payload_bytes).request_id, state: 5,
      original_sos_envelope: Buffer.from(privateReport.immutable_envelope_bytes, "base64url"), original_sos_signature: Buffer.from(privateReport.signature, "base64url") },
    { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now, expiresAtMs: base.expires_at_ms });
    assert.equal((await upload(app, [closed])).json().code, "CONSENT_REQUIRED");
    assert.equal((await pg.query("SELECT * FROM sos_closures")).rows.length, 2);
  } finally { await app.close(); }
});

test("SOS and signed cloud acknowledgement survive restart; replay and another gateway path do not duplicate storage", async t => {
  const root = fileURLToPath(new URL("../../.tmp/", import.meta.url));
  await mkdir(root, { recursive: true });
  const dir = await mkdtemp(join(root, "cloud-sos-"));
  t.after(async () => { if (dirname(dir) !== root.replace(/[\\/]$/, "")) throw new Error("Unexpected test database path"); await rm(dir, { recursive: true, force: true }); });
  const packet = make();
  const pg = new PGlite(dir); const app = appFor(adapter(pg));
  let first;
  try {
    const result = await upload(app, [packet], "saved-batch");
    assert.equal(result.statusCode, 200); first = result.json();
    assert.equal(first.results[0].status, "ACCEPTED");
    const verified = validatePacket(first.packets[0], { nowMs: now, trustedCloudNodeId: cloud.nodeId });
    assert.equal(verified.envelope.destination.node_id.toString("hex"), origin.nodeId);
    assert.equal(verified.payload.origin_event_id, first.results[0].event_id);
    assert.equal(verified.payload.status, 1);
    assert.equal(verified.payload.cloud_revision, first.results[0].cloud_revision);
    const rows = (await pg.query("SELECT * FROM sos_reports")).rows as Record<string, any>[];
    assert.equal(rows.length, 1);
    assert.deepEqual(Buffer.from(rows[0].immutable_envelope), Buffer.from(packet.immutable_envelope_bytes, "base64url"));
    assert.deepEqual(Buffer.from(rows[0].origin_signature), Buffer.from(packet.signature, "base64url"));
    assert.equal(rows[0].description, "Need water'; SELECT 1;");
    const base = validatePacket(packet, { nowMs: now, forCloud: true });
    const closure = createPacket(origin, 2, { request_id: base.payload.request_id, state: 6,
      original_sos_envelope: Buffer.from(packet.immutable_envelope_bytes, "base64url"), original_sos_signature: Buffer.from(packet.signature, "base64url") },
    { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now, expiresAtMs: base.envelope.expires_at_ms });
    assert.equal((await upload(app, [closure])).statusCode, 200);
  } finally { await app.close(); }

  const reopened = new PGlite(dir); const restored = appFor(adapter(reopened));
  try {
    const relayed = { ...packet, relay: { remaining_hops: 0, hop_count: 4, previous_node_id: "b".repeat(64) } };
    const replay = await upload(restored, [relayed], "saved-batch");
    assert.equal(replay.statusCode, 200); assert.deepEqual(replay.json(), first);
    const duplicate = await upload(restored, [relayed]);
    assert.equal(duplicate.statusCode, 200); assert.equal(duplicate.json().results[0].status, "DUPLICATE");
    assert.deepEqual(duplicate.json().packets, first.packets);
    assert.equal((await reopened.query("SELECT event_id FROM sos_reports")).rows.length, 1);
    assert.equal((await new SosStore(adapter(reopened), cloud, () => now).list(10)).items[0].state, 6);
    assert.equal((await reopened.query("SELECT * FROM sos_closures")).rows.length, 1);
  } finally { await restored.close(); }
});

test("invalid consent, tampering and conflicting IDs leave no partial reports or poisoned idempotency keys", async () => {
  const pg = new PGlite(); const app = appFor(adapter(pg));
  try {
    const privateReport = make("Private report", randomUUID(), false);
    assert.equal((await upload(app, [privateReport])).json().code, "CONSENT_REQUIRED");
    const original = make("First content", "11111111-1111-4111-8111-111111111111");
    assert.equal((await upload(app, [original, { ...make(), signature: "AA" }])).statusCode, 400);
    assert.equal((await pg.query("SELECT event_id FROM sos_reports")).rows.length, 0);
    assert.equal((await upload(app, [original], "fixed-key")).statusCode, 200);
    const e = validatePacket(original, { nowMs: now, forCloud: true }).envelope;
    const conflict = make("Changed content", "11111111-1111-4111-8111-111111111111", true, { packetId: e.packet_id, eventId: e.event_id });
    const fresh = make("New report", "00000000-0000-4000-8000-000000000001");
    const result = await upload(app, [fresh, conflict], "rollback-batch");
    assert.equal(result.statusCode, 409); assert.equal(result.json().code, "REPORT_ID_CONFLICT");
    assert.equal((await pg.query("SELECT event_id FROM sos_reports")).rows.length, 1);
    assert.equal((await upload(app, [fresh], "fixed-key")).json().code, "IDEMPOTENCY_CONFLICT");
    // A failed batch did not reserve its key or retain its first valid report.
    const recovered = await upload(app, [fresh], "rollback-batch");
    assert.equal(recovered.statusCode, 200); assert.equal(recovered.json().results[0].status, "ACCEPTED");
    const results = await Promise.all([upload(app, [original]), upload(app, [original])]);
    results.forEach(response => assert.equal(response.json().results[0].status, "DUPLICATE"));
    assert.equal((await pg.query("SELECT event_id FROM sos_reports")).rows.length, 2);
  } finally { await app.close(); }
});

test("a commit failure produces no success or signed receipt and the same upload can recover", async () => {
  const pg = new PGlite(); const db = adapter(pg);
  let failCommit = false;
  const app = appFor({ ...db, transaction: work => db.transaction(async connection => {
    const response = await work(connection);
    if (failCommit) throw new Error("private-password private-location");
    return response;
  }) });
  try {
    await app.ready(); failCommit = true;
    const original = make(); const base = validatePacket(original, { nowMs: now, forCloud: true });
    const packet = createPacket(origin, 2, { request_id: base.payload.request_id, state: 5,
      original_sos_envelope: Buffer.from(original.immutable_envelope_bytes, "base64url"), original_sos_signature: Buffer.from(original.signature, "base64url") },
    { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now, expiresAtMs: base.envelope.expires_at_ms });
    const failed = await upload(app, [packet], "retryable");
    assert.equal(failed.statusCode, 503); assert.equal(failed.json().retryable, true);
    assert.doesNotMatch(failed.body, /private-password|private-location|packets|ACCEPTED/);
    assert.equal((await pg.query("SELECT event_id FROM sos_reports")).rows.length, 0);
    assert.equal((await pg.query("SELECT event_id FROM sos_closures")).rows.length, 0);
    assert.equal((await pg.query("SELECT principal_id FROM sync_uploads")).rows.length, 0);
    failCommit = false;
    const recovered = await upload(app, [packet], "retryable");
    assert.equal(recovered.statusCode, 200); assert.equal(recovered.json().results[0].status, "ACCEPTED");
    assert.equal((await new SosStore(db, cloud, () => now).find(base.payload.request_id))!.state, 5);
  } finally { await app.close(); }
});
