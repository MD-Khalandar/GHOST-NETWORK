import assert from "node:assert/strict";
import { generateKeyPairSync, randomUUID } from "node:crypto";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { buildApp } from "./app.js";
import { createPacket } from "./protocol.js";
import { SigningIdentity, tokenHash, type Principal } from "./security.js";

const now = 1792860000000;
const pem = () => generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ format: "pem", type: "pkcs8" }).toString();
const cloudPem = pem(), origin = SigningIdentity.fromPem(pem());
const roles = ["gateway", "viewer", "operator"] as const;
const tokens = { gateway: "g".repeat(43), viewer: "v".repeat(43), operator: "o".repeat(43) };
const principals: Principal[] = roles.map(role => ({ id: role, role, token_sha256: tokenHash(tokens[role]),
  ...(role === "gateway" ? { gateway_node_id: origin.nodeId } : {}) }));
const headers = (role: keyof typeof tokens) => ({ authorization: `Bearer ${tokens[role]}` });

test("dashboard reads real uploads with bounded pagination, detail lookup and role separation", async () => {
  const pg = new PGlite();
  let clock = now;
  const app = buildApp({ principals, cloudSigningKeyPem: cloudPem, now: () => clock, database: {
    query: (sql, values) => pg.query(sql, values),
    transaction: work => pg.transaction(connection => work(connection)), close: () => pg.close(),
  } });
  try {
    const empty = await app.inject({ url: "/v1/dashboard/sos", headers: headers("viewer") });
    assert.equal(empty.statusCode, 200); assert.deepEqual(empty.json(), { items: [], next_before: null });
    const packets = [1, 2, 3].map(index => createPacket(origin, 1, {
      request_id: randomUUID(), category: index, description: index === 1 ? "<script>alert('SOS')</script>" : `Help ${index}`,
      ...(index === 1 ? { people_count: 2, coarse_area: "Station", landmark: "North gate", latitude: 0, longitude: 44.25 } : {}),
      consent: { public_mesh_allowed: true, cloud_processing_allowed: true },
    }, { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: now - 1000, expiresAtMs: now + 3600000 }));
    for (const packet of packets) {
      assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", headers: headers("gateway"),
        payload: { idempotency_key: randomUUID(), packets: [packet] } })).statusCode, 200);
    }
    const first = await app.inject({ url: "/v1/dashboard/sos?limit=2", headers: headers("viewer") });
    assert.equal(first.statusCode, 200); assert.equal(first.headers["cache-control"], "no-store");
    assert.deepEqual(first.json().items.map((item: { description: string }) => item.description), ["Help 3", "Help 2"]);
    const cursor = first.json().next_before;
    const second = await app.inject({ url: `/v1/dashboard/sos?limit=2&before=${cursor}`, headers: headers("operator") });
    const report = second.json().items[0];
    assert.equal(second.json().items.length, 1); assert.equal(second.json().next_before, null);
    assert.equal(report.description, "<script>alert('SOS')</script>");
    assert.equal(report.people_count, 2); assert.equal(report.coarse_area, "Station"); assert.equal(report.landmark, "North gate");
    assert.equal(report.latitude, 0); assert.equal(report.longitude, 44.25);
    assert.equal(first.json().items[0].latitude, null); assert.equal(first.json().items[0].longitude, null);
    assert.equal(typeof report.created_at_ms, "number"); assert.equal(report.received_at_ms, now);
    assert.equal(typeof report.cloud_revision, "number");
    assert.doesNotMatch(second.body, /immutable_envelope|signature|cloud_ack|sender_node_id|token_sha256/);
    const detail = await app.inject({ url: `/v1/dashboard/sos/${report.request_id}`, headers: headers("viewer") });
    assert.deepEqual(detail.json(), report);
    clock += 7200000;
    // Expiry never masquerades as resolution or deletes previously received reports.
    const expired = await app.inject({ url: `/v1/dashboard/sos/${report.request_id}`, headers: headers("operator") });
    assert.equal(expired.statusCode, 200); assert.ok(expired.json().expires_at_ms < clock);
    assert.equal((await app.inject({ url: `/v1/dashboard/sos/${randomUUID()}`, headers: headers("viewer") })).statusCode, 404);
    for (const url of ["/v1/dashboard/sos", `/v1/dashboard/sos/${report.request_id}`]) {
      assert.equal((await app.inject({ url })).statusCode, 401);
      assert.equal((await app.inject({ url, headers: headers("gateway") })).statusCode, 403);
    }
    for (const query of ["limit=101", "limit=0", "limit=1.5", "limit=2&limit=3", "before=0", "before=9007199254740992", "before=1%27", "sort=description"]) {
      assert.equal((await app.inject({ url: `/v1/dashboard/sos?${query}`, headers: headers("viewer") })).statusCode, 400, query);
    }
    assert.equal((await app.inject({ url: "/v1/dashboard/sos/not-a-uuid", headers: headers("viewer") })).statusCode, 400);
  } finally { await app.close(); }
});

test("dashboard storage failures stay retryable and do not leak database errors", async () => {
  const app = buildApp({ principals, cloudSigningKeyPem: cloudPem, database: {
    transaction: async work => work({ query: async () => ({ rows: [] }) }),
    query: async () => { throw new Error("secret-database-password"); }, close: async () => {},
  } });
  try {
    for (const url of ["/v1/dashboard/sos", `/v1/dashboard/sos/${randomUUID()}`]) {
      const result = await app.inject({ url, headers: headers("viewer") });
      assert.equal(result.statusCode, 503); assert.equal(result.json().retryable, true);
      assert.doesNotMatch(result.body, /secret-database-password/);
    }
  } finally { await app.close(); }
});
