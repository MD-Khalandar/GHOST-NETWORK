import assert from "node:assert/strict";
import { generateKeyPairSync, randomUUID } from "node:crypto";
import test from "node:test";
import { PGlite } from "@electric-sql/pglite";
import { buildApp } from "./app.js";
import { createPacket, validatePacket } from "./protocol.js";
import { SigningIdentity, tokenHash } from "./security.js";

test("signed helper/supply uploads deduplicate, preserve owner identity, and project only the latest helper report", async () => {
  const pem = () => generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ format: "pem", type: "pkcs8" }).toString();
  const origin = SigningIdentity.fromPem(pem()), stranger = SigningIdentity.fromPem(pem());
  const cloudPem = pem(), cloud = SigningIdentity.fromPem(cloudPem);
  const now = 1792860000000;
  const pg = new PGlite();
  const gatewayHeaders = { authorization: `Bearer ${"g".repeat(43)}` }, viewerHeaders = { authorization: `Bearer ${"v".repeat(43)}` };
  const app = buildApp({ cloudSigningKeyPem: cloudPem, now: () => now, principals: [
    { id: "gateway", role: "gateway", token_sha256: tokenHash("g".repeat(43)), gateway_node_id: stranger.nodeId },
    { id: "viewer", role: "viewer", token_sha256: tokenHash("v".repeat(43)) },
  ], database: { query: (sql, values) => pg.query(sql, values), transaction: work => pg.transaction(c => work(c)), close: () => pg.close() } });
  const consent = { public_mesh_allowed: true, cloud_processing_allowed: true };
  const make = (kind: number, data: Record<string, unknown>, time = now - 1000, signer = origin) => createPacket(signer, kind, data,
    { packetId: randomUUID(), eventId: randomUUID(), createdAtMs: time, expiresAtMs: now + 60000 });
  const helperData = { owner_node_id: Buffer.from(origin.nodeId, "hex"), display_name: "<script>helper</script>",
    coarse_area: "Station", help_types: [1, 6], availability: 1, consent };
  const offerData = { offer_id: randomUUID(), owner_node_id: Buffer.from(origin.nodeId, "hex"), resource_type: 1,
    quantity: 20, unit: "litres", coarse_area: "Station", available_until_ms: now + 50000, consent };
  const upload = (packets: ReturnType<typeof make>[], key: string = randomUUID()) => app.inject({ method: "POST", url: "/v1/sync/upload",
    headers: gatewayHeaders, payload: { idempotency_key: key, packets } });
  try {
    // Android withdraws earlier sharing with a minimal report; new private details stay local.
    const older = make(3, helperData), newer = make(3, {
      owner_node_id: helperData.owner_node_id, help_types: [7], availability: 2, consent,
    }, now);
    const offer = make(5, offerData);
    const first = await upload([newer, offer], "resource-batch");
    assert.equal(first.statusCode, 200, first.body);
    for (const packet of first.json().packets) {
      const ack = validatePacket(packet, { nowMs: now, trustedCloudNodeId: cloud.nodeId });
      assert.equal(ack.envelope.destination.node_id.toString("hex"), origin.nodeId);
    }
    assert.deepEqual((await upload([newer, offer], "resource-batch")).json(), first.json());
    assert.equal((await upload([older])).statusCode, 200);
    const helpers = await app.inject({ url: "/v1/dashboard/helpers", headers: viewerHeaders });
    assert.equal(helpers.statusCode, 200); assert.equal(helpers.json().items.length, 1);
    assert.equal(helpers.json().items[0].availability, 2);
    assert.equal(helpers.json().items[0].display_name, null);
    assert.equal(helpers.json().items[0].coarse_area, null);
    assert.equal(helpers.json().items[0].owner_node_id, origin.nodeId);
    assert.doesNotMatch(helpers.body, /cloud_ack|immutable_envelope|origin_signature|token_sha256/);
    const supplies = await app.inject({ url: "/v1/dashboard/offers?limit=1", headers: viewerHeaders });
    assert.equal(supplies.json().items[0].quantity, 20); assert.equal(supplies.json().items[0].available_until_ms, now + 50000);
    assert.equal((await upload([offer])).json().results[0].status, "DUPLICATE");
    assert.equal((await upload([make(5, { ...offerData, quantity: 30 })])).statusCode, 409);
    assert.equal((await upload([make(5, offerData, now, stranger)])).json().code, "INVALID_ACTOR");
    assert.equal((await upload([make(3, { ...helperData, consent: { ...consent, cloud_processing_allowed: false } })])).json().code, "CONSENT_REQUIRED");
    const before = (await pg.query("SELECT event_id FROM resource_events")).rows.length;
    const conflict = make(5, { ...offerData, quantity: 99 });
    assert.equal((await upload([make(3, helperData), conflict], "rolled-back")).statusCode, 409);
    assert.equal((await pg.query("SELECT event_id FROM resource_events")).rows.length, before);
    for (const url of ["/v1/dashboard/helpers", "/v1/dashboard/offers"]) {
      assert.equal((await app.inject({ url })).statusCode, 401);
      assert.equal((await app.inject({ url, headers: gatewayHeaders })).statusCode, 403);
    }
    const lateId = randomUUID();
    const lateBase = make(5, { ...offerData, offer_id: lateId });
    const editData = { offer_id: lateId, owner_node_id: Buffer.from(origin.nodeId, "hex"), state: 1, quantity: 8,
      unit: "litres", coarse_area: "East gate", available_until_ms: now + 50000, consent,
      original_offer_envelope: Buffer.from(lateBase.immutable_envelope_bytes, "base64url"),
      original_offer_signature: Buffer.from(lateBase.signature, "base64url") };
    const edited = make(6, editData, now - 500);
    const withdrawn = make(6, { ...editData, state: 2 }, now);
    assert.equal((await upload([withdrawn])).statusCode, 200); // proof permits update before creation
    assert.equal((await upload([lateBase, edited])).statusCode, 200);
    const latest = (await app.inject({ url: "/v1/dashboard/offers", headers: viewerHeaders })).json().items.find((item: any) => item.offer_id === lateId);
    assert.equal(latest.state, 2); assert.equal(latest.quantity, 8); assert.equal(latest.coarse_area, "East gate");
    assert.equal((await pg.query("SELECT * FROM resource_offer_latest WHERE entity_id = $1", [lateId])).rows.length, 1);
    assert.equal((await upload([withdrawn])).json().results[0].status, "DUPLICATE");
    assert.equal((await upload([make(6, { ...editData, owner_node_id: Buffer.from(stranger.nodeId, "hex") }, now, stranger)])).json().code, "INVALID_ACTOR");
    assert.equal((await upload([make(6, { ...editData, original_offer_signature: Buffer.alloc(70, 1) }, now)])).json().code, "BAD_SIGNATURE");
    assert.equal((await upload([make(6, { ...editData, available_until_ms: now + 60000 }, now)])).json().code, "INVALID_PAYLOAD");
  } finally { await app.close(); }
});
