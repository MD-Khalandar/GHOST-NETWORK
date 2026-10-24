import protobuf from "protobufjs";
import { fileURLToPath } from "node:url";
import { nodeId, publicKey, SigningIdentity, verifySignature } from "./security.js";

const root = new protobuf.Root().loadSync(
  fileURLToPath(new URL("../../contracts/ghost.proto", import.meta.url)), { keepCase: true },
).resolveAll();
export const MAX_PACKET_BYTES = 32768;
export const MAX_ENVELOPE_BYTES = 24576;
export const MAX_PAYLOAD_BYTES = 16384;
export const MAX_TTL_MS = 86400000;
export const CLOCK_SKEW_MS = 300000;
export const payloadTypes = ["", "SosCreateV1", "SosUpdateV1", "HelperProfilePublishV1", "HelperProfileUpdateV1",
  "OfferPublishV1", "OfferUpdateV1", "CloudAckV1", "MatchProposedV1", "MatchDeclinedV1", "MatchAcceptedV1",
  "MatchConfirmedV1", "DeliveryReceiptV1"];
export const REJECTION_CODES = ["MALFORMED_PACKET", "TOO_LARGE", "UNSUPPORTED_VERSION", "UNSUPPORTED_KIND",
  "INVALID_TIME", "EXPIRED", "INVALID_HOPS", "INVALID_KEY", "SIGNER_MISMATCH", "BAD_SIGNATURE",
  "INVALID_PAYLOAD", "INVALID_ACTOR", "UNKNOWN_AUTHORITY", "UNTRUSTED_CLOUD", "CONSENT_REQUIRED"] as const;
export type Rejection = typeof REJECTION_CODES[number];
export class PacketError extends Error { constructor(readonly code: Rejection) { super(code); } }
const reject = (code: Rejection): never => { throw new PacketError(code); };
const check = (condition: unknown, code: Rejection): void => { if (!condition) reject(code); };
export interface WirePacket {
  immutable_envelope_bytes: string; signature: string;
  relay: { remaining_hops: number; hop_count: number; previous_node_id?: string | null };
}
export interface Authority { ownerNodeId: string; cloudProcessingAllowed: boolean }
export type AuthorityLookup = (kind: "request" | "offer" | "helper" | "proposal" | "receipt", id: string, version?: number) => Authority | undefined;
export interface ValidationOptions { nowMs: number; trustedCloudNodeId?: string; lookup?: AuthorityLookup; forCloud?: boolean }
export interface VerifiedPacket { envelope: Record<string, any>; payload: Record<string, any>; packet: WirePacket }

export function encode(typeName: string, value: Record<string, unknown>): Buffer {
  const type = root.lookupType(`ghost.contracts.v1.${typeName}`);
  const error = type.verify(value);
  if (error) throw new Error(`Invalid ${typeName} construction`);
  return Buffer.from(type.encode(canonical(type, value)).finish());
}
// Match protoc's proto3 default elision while preserving optional-field presence.
function canonical(type: protobuf.Type, value: Record<string, any>): protobuf.Message {
  const result: Record<string, any> = {};
  for (const field of type.fieldsArray) {
    const v = value[field.name];
    if (v === undefined || v === null) continue;
    const message = field.resolvedType instanceof protobuf.Type ? field.resolvedType : undefined;
    if (field.repeated) {
      if (v.length) result[field.name] = message ? v.map((item: Record<string, any>) => canonical(message, item)) : v;
    } else if (message) result[field.name] = canonical(message, v);
    else if (field.partOf || !(v === 0 || v === false || v === "" || (field.type === "bytes" && v.length === 0))) result[field.name] = v;
  }
  return type.create(result);
}
export function decode(typeName: string, bytes: Uint8Array): Record<string, any> {
  const type = root.lookupType(`ghost.contracts.v1.${typeName}`);
  const reader = protobuf.Reader.create(bytes);
  reader.string = () => new TextDecoder("utf-8", { fatal: true }).decode(reader.bytes());
  return type.toObject(type.decode(reader), { longs: Number, bytes: Buffer });
}
export function fromBase64(value: unknown, maximum: number): Buffer {
  check(typeof value === "string", "MALFORMED_PACKET");
  check((value as string).length <= Math.ceil(maximum * 4 / 3), "TOO_LARGE");
  check(/^[A-Za-z0-9_-]+$/.test(value as string), "MALFORMED_PACKET");
  const bytes = Buffer.from(value as string, "base64url");
  check(bytes.length <= maximum, "TOO_LARGE");
  check(bytes.toString("base64url") === value, "MALFORMED_PACKET");
  return bytes;
}
const uuid = (v: unknown): boolean => typeof v === "string" && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(v);
const integer = (v: unknown, min: number, max = Number.MAX_SAFE_INTEGER): boolean => typeof v === "number" && Number.isSafeInteger(v) && v >= min && v <= max;
const text = (v: unknown, max: number, required = false): boolean => v === undefined ? !required : typeof v === "string" && v.length <= max && (!required || v.trim().length > 0);
const hex = (v: Buffer | undefined): string => v?.toString("hex") ?? "";

export function validatePacket(packet: WirePacket, options: ValidationOptions): VerifiedPacket {
  try { return validate(packet, options); }
  catch (error) { if (error instanceof PacketError) throw error; throw new PacketError("MALFORMED_PACKET"); }
}
function validate(packet: WirePacket, options: ValidationOptions): VerifiedPacket {
  check(packet && typeof packet === "object", "MALFORMED_PACKET");
  const bytes = fromBase64(packet.immutable_envelope_bytes, MAX_ENVELOPE_BYTES);
  const signature = fromBase64(packet.signature, 72);
  check(signature.length >= 8, "BAD_SIGNATURE");
  const relay = packet.relay;
  check(relay && integer(relay.remaining_hops, 0, 4) && integer(relay.hop_count, 0, 4) && relay.remaining_hops + relay.hop_count === 4 &&
    (relay.hop_count === 0 ? !relay.previous_node_id : /^[a-f0-9]{64}$/.test(relay.previous_node_id ?? "")), "INVALID_HOPS");
  const e = decode("ImmutableEnvelopeV1", bytes);
  check(e.version === 1, "UNSUPPORTED_VERSION");
  check(integer(e.kind, 1, 12), "UNSUPPORTED_KIND");
  check(uuid(e.packet_id) && uuid(e.event_id) && [1, 2, 3].includes(e.priority) && e.payload_format === 1, "INVALID_PAYLOAD");
  check(integer(options.nowMs, 1) && integer(e.created_at_ms, 1) && integer(e.expires_at_ms, 1) &&
    e.expires_at_ms > e.created_at_ms && e.expires_at_ms - e.created_at_ms <= MAX_TTL_MS && e.created_at_ms <= options.nowMs + CLOCK_SKEW_MS, "INVALID_TIME");
  check(e.expires_at_ms > options.nowMs, "EXPIRED");
  check(e.destination && (e.destination.kind === 1 ? !e.destination.node_id?.length : e.destination.kind === 2 && e.destination.node_id?.length === 32), "INVALID_PAYLOAD");
  check(e.payload_bytes?.length > 0 && e.payload_bytes.length <= MAX_PAYLOAD_BYTES, "TOO_LARGE");
  check(e.sender_signing_public_key?.length === 91 && e.sender_node_id?.length === 32, "INVALID_KEY");
  try { publicKey(e.sender_signing_public_key); } catch { reject("INVALID_KEY"); }
  const sender = hex(e.sender_node_id);
  check(nodeId(e.sender_signing_public_key) === sender, "SIGNER_MISMATCH");
  let valid = false;
  try { valid = verifySignature(e.sender_signing_public_key, bytes, signature); } catch { /* fail closed */ }
  check(valid, "BAD_SIGNATURE");
  const p = decode(payloadTypes[e.kind]!, e.payload_bytes);
  const cloud = [7, 8, 11].includes(e.kind);
  check(!cloud || (!options.forCloud && sender === options.trustedCloudNodeId), "UNTRUSTED_CLOUD");
  check(cloud ? e.destination.kind === 2 : [1, 2, 3, 4, 5, 6, 9, 10].includes(e.kind) ? e.destination.kind === 1 : true, "INVALID_PAYLOAD");
  let authority: Authority | undefined;
  const owner = (kind: Parameters<AuthorityLookup>[0], id: string, version?: number): void => {
    authority = options.lookup?.(kind, id, version);
    check(authority, "UNKNOWN_AUTHORITY");
    check(authority?.ownerNodeId === sender, "INVALID_ACTOR");
  };
  const payloadCheck = (condition: unknown): void => check(condition, "INVALID_PAYLOAD");
  switch (e.kind) {
    case 1:
      payloadCheck(uuid(p.request_id) && integer(p.category, 1, 6) && text(p.description, 2000, true) &&
        (p.people_count === undefined || integer(p.people_count, 1, 1000)) && text(p.coarse_area, 120) && text(p.landmark, 120) &&
        (p.latitude === undefined || typeof p.latitude === "number" && Number.isFinite(p.latitude) && p.latitude >= -90 && p.latitude <= 90) &&
        (p.longitude === undefined || typeof p.longitude === "number" && Number.isFinite(p.longitude) && p.longitude >= -180 && p.longitude <= 180) &&
        (p.latitude === undefined) === (p.longitude === undefined));
      authority = options.lookup?.("request", p.request_id);
      check(!authority || authority.ownerNodeId === sender, "INVALID_ACTOR");
      break;
    case 2:
      payloadCheck(uuid(p.request_id) && integer(p.state, 1, 4) && text(p.note, 500)); owner("request", p.request_id); break;
    case 3: case 4:
      check(hex(p.owner_node_id) === sender, "INVALID_ACTOR");
      payloadCheck(text(p.display_name, 80) && text(p.coarse_area, 120) && (p.help_types?.length ?? 0) <= 10 &&
        (p.help_types ?? []).every((n: number) => integer(n, 1, 7)) &&
        (p.availability === undefined ? e.kind === 4 : integer(p.availability, 1, 3)));
      authority = options.lookup?.("helper", sender); break;
    case 5: case 6:
      check(hex(p.owner_node_id) === sender, "INVALID_ACTOR");
      payloadCheck(uuid(p.offer_id) && (e.kind === 5 ? integer(p.resource_type, 1, 6) : integer(p.state, 1, 2)) &&
        (p.quantity === undefined || integer(p.quantity, 0, 1000000)) && text(p.unit, 40) && text(p.coarse_area, 120) &&
        text(p.note, 500) && (p.available_until_ms === undefined || integer(p.available_until_ms, e.created_at_ms + 1)));
      if (e.kind === 6) owner("offer", p.offer_id);
      else { authority = options.lookup?.("offer", p.offer_id); check(!authority || authority.ownerNodeId === sender, "INVALID_ACTOR"); }
      break;
    case 7:
      payloadCheck(uuid(p.origin_event_id) && integer(p.status, 1, 3) && integer(p.cloud_revision, 1) && integer(p.received_at_ms, 1, options.nowMs + CLOCK_SKEW_MS) && text(p.error_code, 80)); break;
    case 8:
      payloadCheck(uuid(p.proposal_id) && integer(p.version, 1, 2147483647) && uuid(p.request_id) && uuid(p.offer_id) && uuid(p.approval_id) && text(p.explanation, 1000, true)); break;
    case 9: case 10:
      payloadCheck(uuid(p.proposal_id) && integer(p.version, 1, 2147483647) && text(p.reason, 500) && text(p.note, 500));
      check(hex(p.offer_owner_node_id) === sender, "INVALID_ACTOR"); owner("proposal", p.proposal_id, p.version); break;
    case 11:
      payloadCheck(uuid(p.proposal_id) && integer(p.version, 1, 2147483647) && uuid(p.request_id) && uuid(p.offer_id) && uuid(p.allocation_id)); break;
    case 12:
      payloadCheck(uuid(p.packet_id) && integer(p.receipt_type, 1, 2) && integer(p.observed_at_ms, 1, options.nowMs + CLOCK_SKEW_MS));
      check(hex(p.recipient_node_id) === sender, "INVALID_ACTOR"); owner("receipt", p.packet_id); break;
  }
  if ([1, 3, 5].includes(e.kind)) check(p.consent?.public_mesh_allowed === true, "CONSENT_REQUIRED");
  if (options.forCloud) {
    let allowed = [1, 3, 5].includes(e.kind) ? p.consent?.cloud_processing_allowed === true : authority?.cloudProcessingAllowed === true;
    if ([2, 4, 6].includes(e.kind)) {
      const consent = e.kind === 2 ? p.cloud_processing_allowed : p.consent ? p.consent.cloud_processing_allowed === true : undefined;
      if (consent === false) {
        // Minimal owner-authorized revocations may reach the cloud without new report text.
        allowed = p.note === undefined && p.display_name === undefined && p.coarse_area === undefined &&
          p.unit === undefined && p.quantity === undefined && p.available_until_ms === undefined &&
          !p.help_types?.length && p.availability === undefined &&
          encode(payloadTypes[e.kind]!, p).equals(e.payload_bytes) && encode("ImmutableEnvelopeV1", e).equals(bytes);
      } else if (consent === true) allowed = true;
    }
    check(allowed, "CONSENT_REQUIRED");
  }
  return { envelope: e, payload: p, packet };
}

export function createPacket(identity: SigningIdentity, kind: number, payload: Record<string, unknown>, options: {
  packetId: string; eventId: string; createdAtMs: number; expiresAtMs: number; destinationNodeId?: string;
}): WirePacket {
  const envelope = encode("ImmutableEnvelopeV1", {
    version: 1, packet_id: options.packetId, event_id: options.eventId,
    sender_node_id: Buffer.from(identity.nodeId, "hex"), sender_signing_public_key: identity.publicKey,
    destination: options.destinationNodeId ? { kind: 2, node_id: Buffer.from(options.destinationNodeId, "hex") } : { kind: 1 },
    kind, created_at_ms: options.createdAtMs, expires_at_ms: options.expiresAtMs, priority: kind === 1 ? 3 : 1,
    payload_format: 1, payload_bytes: encode(payloadTypes[kind]!, payload),
  });
  return { immutable_envelope_bytes: envelope.toString("base64url"), signature: identity.sign(envelope).toString("base64url"), relay: { remaining_hops: 4, hop_count: 0 } };
}

export function signCloudPacket(identity: SigningIdentity, kind: number, payload: Record<string, unknown>, options: Parameters<typeof createPacket>[3]): WirePacket {
  if (![7, 8, 11].includes(kind) || !options.destinationNodeId) throw new Error("INVALID_CLOUD_EVENT");
  return createPacket(identity, kind, payload, options);
}
