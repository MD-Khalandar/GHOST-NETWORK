import assert from "node:assert/strict";
import { createPrivateKey } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createPacket, decode, encode, PacketError, REJECTION_CODES, validatePacket, type AuthorityLookup, type WirePacket } from "./protocol.js";
import { nodeId, SigningIdentity, verifySignature } from "./security.js";

const fixture = JSON.parse(readFileSync(new URL("../../contracts/fixtures/cp02-signatures.json", import.meta.url), "utf8"));
const identity = (data = fixture.device) => new SigningIdentity(createPrivateKey({ key: Buffer.from(data.test_only_private_pkcs8, "base64url"), format: "der", type: "pkcs8" }));
const packet = (kind: number): WirePacket => structuredClone(fixture.packets.find((p: { kind: number }) => p.kind === kind).packet);
const lookup: AuthorityLookup = (kind, id, version) => {
  const expected = { request: fixture.ids.request, offer: fixture.ids.offer, helper: fixture.device.node_id, proposal: fixture.ids.proposal, receipt: fixture.ids.receipt_packet }[kind];
  return id === expected && (kind !== "proposal" || version === 2) ? { ownerNodeId: fixture.device.node_id, cloudProcessingAllowed: true } : undefined;
};
const options = { nowMs: fixture.now_ms, trustedCloudNodeId: fixture.cloud.node_id, lookup };
function resign(kind: number, change: (envelope: Record<string, any>) => void): WirePacket {
  const p = packet(kind), e = decode("ImmutableEnvelopeV1", Buffer.from(p.immutable_envelope_bytes, "base64url"));
  change(e);
  const raw = encode("ImmutableEnvelopeV1", e);
  return { ...p, immutable_envelope_bytes: raw.toString("base64url"), signature: identity().sign(raw).toString("base64url") };
}
function rejects(p: WirePacket, code: string, overrides = {}): void {
  assert.throws(() => validatePacket(p, { ...options, ...overrides }), (error: unknown) => error instanceof PacketError && error.code === code);
}

test("all twelve signed fixture families validate and match canonical protobuf bytes", () => {
  assert.deepEqual(REJECTION_CODES, fixture.rejection_codes);
  assert.equal(nodeId(Buffer.from(fixture.device.public_key, "base64url")), fixture.device.node_id);
  for (const vector of fixture.packets) {
    const result = validatePacket(vector.packet, options);
    assert.equal(result.envelope.kind, vector.kind);
    assert.deepEqual(encode("ImmutableEnvelopeV1", result.envelope), Buffer.from(vector.packet.immutable_envelope_bytes, "base64url"));
    assert.deepEqual(encode("WirePacketV1", { immutable_envelope_bytes: Buffer.from(vector.packet.immutable_envelope_bytes, "base64url"), signature: Buffer.from(vector.packet.signature, "base64url"), relay: vector.packet.relay }), Buffer.from(vector.wire_bytes, "base64url"));
  }
});

test("signature tampering, key mismatch, expiry, unsupported version and bad hops are rejected", () => {
  const bad = packet(1); bad.signature = Buffer.alloc(70, 1).toString("base64url"); rejects(bad, "BAD_SIGNATURE");
  rejects(resign(1, e => { e.sender_node_id = Buffer.alloc(32); }), "SIGNER_MISMATCH");
  rejects(resign(1, e => { e.version = 2; }), "UNSUPPORTED_VERSION");
  rejects(packet(1), "EXPIRED", { nowMs: fixture.now_ms + 21600000 });
  const hops = packet(1); hops.relay.remaining_hops = 5; rejects(hops, "INVALID_HOPS");
  const huge = packet(1); huge.immutable_envelope_bytes = "a".repeat(40000); rejects(huge, "TOO_LARGE");
  rejects(resign(1, e => { e.sender_signing_public_key = Buffer.alloc(91); }), "INVALID_KEY");
});

test("cloud identity is pinned, cannot be uploaded by a gateway, and cannot be replaced by a phone", () => {
  rejects(packet(7), "UNTRUSTED_CLOUD", { trustedCloudNodeId: undefined });
  rejects(packet(7), "UNTRUSTED_CLOUD", { forCloud: true });
  const forged = resign(7, e => {
    e.sender_node_id = Buffer.from(fixture.device.node_id, "hex"); e.sender_signing_public_key = Buffer.from(fixture.device.public_key, "base64url");
  });
  rejects(forged, "UNTRUSTED_CLOUD");
});

test("owner actions require verified existing ownership, exact proposal version and actual receipt destination", () => {
  rejects(packet(2), "UNKNOWN_AUTHORITY", { lookup: undefined });
  rejects(packet(6), "INVALID_ACTOR", { lookup: () => ({ ownerNodeId: fixture.cloud.node_id, cloudProcessingAllowed: true }) });
  rejects(packet(10), "UNKNOWN_AUTHORITY", { lookup: (_kind: string, _id: string, version: number) => version === 1 ? { ownerNodeId: fixture.device.node_id } : undefined });
  rejects(packet(12), "INVALID_ACTOR", { lookup: () => ({ ownerNodeId: fixture.cloud.node_id, cloudProcessingAllowed: true }) });
});

test("public/cloud consent are separate and minimal signed revocations do not carry new report text", () => {
  const source = validatePacket(packet(1), options);
  const make = (kind: number, payload: Record<string, unknown>) => createPacket(identity(), kind, payload, {
    packetId: source.envelope.packet_id, eventId: source.envelope.event_id, createdAtMs: fixture.now_ms, expiresAtMs: fixture.now_ms + 10000,
  });
  const localOnly = make(1, { ...source.payload, consent: { public_mesh_allowed: true, cloud_processing_allowed: false } });
  validatePacket(localOnly, options);
  rejects(localOnly, "CONSENT_REQUIRED", { forCloud: true });
  rejects(make(1, { ...source.payload, consent: {} }), "CONSENT_REQUIRED");
  const revocation = make(2, { request_id: fixture.ids.request, state: 1, cloud_processing_allowed: false });
  validatePacket(revocation, { ...options, forCloud: true });
  rejects(make(2, { request_id: fixture.ids.request, state: 1, cloud_processing_allowed: false, note: "New private content" }), "CONSENT_REQUIRED", { forCloud: true });
  validatePacket(make(4, { owner_node_id: Buffer.from(fixture.device.node_id, "hex"), consent: {} }), { ...options, forCloud: true });
  rejects(make(4, { owner_node_id: Buffer.from(fixture.device.node_id, "hex"), consent: {}, display_name: "New private content" }), "CONSENT_REQUIRED", { forCloud: true });
  const revokedEnvelope = decode("ImmutableEnvelopeV1", Buffer.from(revocation.immutable_envelope_bytes, "base64url"));
  revokedEnvelope.payload_bytes = Buffer.concat([revokedEnvelope.payload_bytes, Buffer.from([0xa2, 0x06, 1, 120])]);
  const hidden = encode("ImmutableEnvelopeV1", revokedEnvelope);
  rejects({ ...revocation, immutable_envelope_bytes: hidden.toString("base64url"), signature: identity().sign(hidden).toString("base64url") }, "CONSENT_REQUIRED", { forCloud: true });
});

test("server signing produces verifiable signatures and origin bytes survive relay metadata changes", () => {
  const p = packet(1), raw = Buffer.from(p.immutable_envelope_bytes, "base64url");
  assert.ok(verifySignature(identity().publicKey, raw, identity().sign(raw)));
  const relayed = { ...p, relay: { remaining_hops: 3, hop_count: 1, previous_node_id: fixture.cloud.node_id } };
  assert.equal(validatePacket(relayed, options).packet.immutable_envelope_bytes, p.immutable_envelope_bytes);
  assert.equal(relayed.signature, p.signature);
});
