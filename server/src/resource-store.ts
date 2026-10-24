import type { Database, SqlConnection } from "./database.js";
import { decode, PacketError, type VerifiedPacket, type WirePacket } from "./protocol.js";
export class UploadConflict extends Error {
  constructor(readonly code: "IDEMPOTENCY_CONFLICT" | "REPORT_ID_CONFLICT") { super(code); }
}

/** Immutable owner-signed publications; helper views select the latest owner report. */
export async function saveResource(connection: SqlConnection, verified: VerifiedPacket, bytes: Buffer,
  revision: number, received: number, ack: WirePacket): Promise<{ duplicate: boolean; row: Record<string, any> }> {
  const { envelope: e, payload: p, packet } = verified;
  const owner = e.sender_node_id.toString("hex");
  const entity = e.kind === 3 ? owner : p.offer_id;
  if (e.kind === 6 && !p.original_offer_envelope?.length) throw new PacketError("INVALID_PAYLOAD");
  const base = e.kind === 6 ? decode("OfferPublishV1", decode("ImmutableEnvelopeV1", p.original_offer_envelope).payload_bytes) : p;
  if (e.kind === 5 || e.kind === 6) {
    const previous = (await connection.query("SELECT kind, owner_node_id, immutable_envelope FROM resource_events WHERE entity_id = $1 AND kind IN (5,6) ORDER BY created_at_ms DESC, event_id DESC LIMIT 1", [entity])).rows[0];
    if (previous) {
      const previousOriginal = previous.kind === 5 ? Buffer.from(previous.immutable_envelope) :
        decode("OfferUpdateV1", decode("ImmutableEnvelopeV1", previous.immutable_envelope).payload_bytes).original_offer_envelope;
      const original = e.kind === 5 ? bytes : p.original_offer_envelope;
      if (previous.owner_node_id !== owner || !Buffer.from(previousOriginal).equals(original)) throw new UploadConflict("REPORT_ID_CONFLICT");
    }
  }
  if (e.kind === 3 && !p.help_types?.length || e.kind === 5 && p.quantity !== undefined && p.quantity < 1)
    throw new PacketError("INVALID_PAYLOAD");
  const data = e.kind === 3 ? { display_name: p.display_name ?? null, coarse_area: p.coarse_area ?? null,
    help_types: [...new Set(p.help_types)], availability: p.availability } : {
    resource_type: base.resource_type, quantity: p.quantity ?? null, unit: p.unit ?? null,
    coarse_area: p.coarse_area ?? null, available_until_ms: Math.min(p.available_until_ms ?? e.expires_at_ms, e.expires_at_ms), state: p.state ?? 1,
  };
  const saved = await connection.query(`INSERT INTO resource_events
    (event_id, packet_id, kind, entity_id, owner_node_id, immutable_envelope, origin_signature, data,
     created_at_ms, expires_at_ms, received_at_ms, cloud_revision, cloud_ack)
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8::jsonb,$9,$10,$11,$12,$13::jsonb)
    ON CONFLICT DO NOTHING RETURNING cloud_revision, cloud_ack`, [e.event_id, e.packet_id, e.kind, entity, owner, bytes,
    Buffer.from(packet.signature, "base64url"), JSON.stringify(data), e.created_at_ms, e.expires_at_ms, received, revision, JSON.stringify(ack)]);
  if (saved.rows.length) return { duplicate: false, row: saved.rows[0] };
  const found = await connection.query(`SELECT immutable_envelope, cloud_revision, cloud_ack FROM resource_events
    WHERE event_id = $1 OR packet_id = $2 OR ($4 = 5 AND kind = 5 AND entity_id = $3)`, [e.event_id, e.packet_id, entity, e.kind]);
  if (found.rows.length !== 1 || !Buffer.from(found.rows[0].immutable_envelope).equals(bytes)) throw new UploadConflict("REPORT_ID_CONFLICT");
  return { duplicate: true, row: found.rows[0] };
}

export async function listResources(database: Database, kind: 3 | 5, limit: number, before?: number, now=Date.now()) {
  const result = await database.query(`SELECT * FROM (
    SELECT DISTINCT ON (entity_id) entity_id, owner_node_id, data, created_at_ms, expires_at_ms, received_at_ms, cloud_revision
    FROM resource_events WHERE kind = $1 OR ($1 = 5 AND kind = 6) ORDER BY entity_id, created_at_ms DESC, event_id DESC
    ) latest WHERE ($2::bigint IS NULL OR cloud_revision < $2) ORDER BY cloud_revision DESC LIMIT $3`, [kind, before ?? null, limit + 1]);
  const balances = await database.query(`SELECT resource_id, SUM(quantity) FILTER(WHERE state=2) AS reserved,
    SUM(quantity) FILTER(WHERE state=6) AS consumed,COUNT(*) FILTER(WHERE state=2) AS commitments FROM assistance_cases WHERE state IN(2,6) GROUP BY resource_id`);
  const items = result.rows.slice(0, limit).map(row => ({ ...row.data, ...(kind === 5 ? { offer_id: row.entity_id } : {}),
    ...(kind===5 ? (()=>{const b=balances.rows.find(b=>b.resource_id===row.entity_id);const reserved=Number(b?.reserved??0),consumed=Number(b?.consumed??0);
      return {reserved_quantity:reserved,consumed_quantity:consumed,available_quantity:(row.data.state??1)!==1 || Number(row.expires_at_ms)<=now?0:row.data.quantity===null?null:Math.max(0,Number(row.data.quantity)-reserved-consumed)};})() : {}),
    ...(kind===3?{committed_count:Number(balances.rows.find(b=>b.resource_id===row.entity_id)?.commitments??0)}:{}),
    owner_node_id: row.owner_node_id, created_at_ms: Number(row.created_at_ms), expires_at_ms: Number(row.expires_at_ms),
    received_at_ms: Number(row.received_at_ms), cloud_revision: Number(row.cloud_revision) }));
  return { items, next_before: result.rows.length > limit ? items.at(-1)!.cloud_revision : null };
}
