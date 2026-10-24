import { applyAssistanceDecision, reconcileAssistance, checkCommittedEdit } from "./assistance.js";
import { createHash, randomUUID } from "node:crypto";
import type { Database, SqlConnection } from "./database.js";
import { PacketError, signCloudPacket, validatePacket, decode, type VerifiedPacket, type WirePacket } from "./protocol.js";
import type { SigningIdentity } from "./security.js";
import { saveResource, UploadConflict } from "./resource-store.js";
export { UploadConflict } from "./resource-store.js";
export interface UploadResponse {
  results: Array<{ event_id: string; status: "ACCEPTED" | "DUPLICATE"; cloud_revision: number }>;
  packets: WirePacket[];
}

// Only the operator-facing projection crosses this API, never signed packets/keys.
export interface DashboardSos {
  state: number;
  request_id: string;
  category: number;
  description: string;
  people_count: number | null;
  coarse_area: string | null;
  landmark: string | null;
  latitude: number | null;
  longitude: number | null;
  created_at_ms: number;
  expires_at_ms: number;
  received_at_ms: number;
  cloud_revision: number;
}
const dashboardColumns = "request_id, category, description, people_count, coarse_area, landmark, latitude, longitude, created_at_ms, expires_at_ms, received_at_ms, cloud_revision, state";
const dashboardReport = (row: Record<string, any>): DashboardSos => ({
  request_id: row.request_id, category: Number(row.category), description: row.description, state: Number(row.state),
  people_count: row.people_count === null ? null : Number(row.people_count),
  coarse_area: row.coarse_area, landmark: row.landmark,
  latitude: row.latitude === null ? null : Number(row.latitude),
  longitude: row.longitude === null ? null : Number(row.longitude),
  created_at_ms: Number(row.created_at_ms), expires_at_ms: Number(row.expires_at_ms),
  received_at_ms: Number(row.received_at_ms), cloud_revision: Number(row.cloud_revision),
});

export class SosStore {
  constructor(private readonly database: Database, private readonly signer: SigningIdentity,
              private readonly now: () => number = Date.now) {}

  async list(limit: number, before?: number): Promise<{ items: DashboardSos[]; next_before: number | null }> {
    // The existing unique cloud-revision index supports bounded keyset pagination.
    const result = before === undefined
      ? await this.database.query(`SELECT ${dashboardColumns} FROM sos_reports ORDER BY cloud_revision DESC LIMIT $1`, [limit + 1])
      : await this.database.query(`SELECT ${dashboardColumns} FROM sos_reports WHERE cloud_revision < $1 ORDER BY cloud_revision DESC LIMIT $2`, [before, limit + 1]);
    const items = result.rows.slice(0, limit).map(dashboardReport);
    return { items, next_before: result.rows.length > limit ? items.at(-1)!.cloud_revision : null };
  }

  async find(requestId: string): Promise<DashboardSos | null> {
    const result = await this.database.query(`SELECT ${dashboardColumns} FROM sos_reports WHERE request_id = $1`, [requestId]);
    return result.rows.length ? dashboardReport(result.rows[0]) : null;
  }

  async upload(principalId: string, key: string, packets: VerifiedPacket[]): Promise<UploadResponse> {
    // Mutable relay metadata and alternate valid ECDSA signatures do not change
    // a report's identity. Preserve order to replay the same batch response.
    const hash = createHash("sha256").update(JSON.stringify(packets.map(p => p.packet.immutable_envelope_bytes))).digest("hex");
    return this.database.transaction(async connection => {
      await connection.query("SELECT pg_advisory_xact_lock(715422706)");
      await reconcileAssistance(connection,this.signer,this.now());
      await connection.query("DELETE FROM sync_uploads WHERE created_at < CURRENT_TIMESTAMP - INTERVAL '7 days'");
      const inserted = await connection.query(`INSERT INTO sync_uploads (principal_id, idempotency_key, content_hash)
        VALUES ($1, $2, $3) ON CONFLICT DO NOTHING RETURNING principal_id`, [principalId, key, hash]);
      if (!inserted.rows.length) {
        const existing = (await connection.query("SELECT content_hash, response FROM sync_uploads WHERE principal_id = $1 AND idempotency_key = $2", [principalId, key])).rows[0];
        if (!existing || existing.content_hash !== hash) throw new UploadConflict("IDEMPOTENCY_CONFLICT");
        if (!existing.response) throw new Error("INCOMPLETE_UPLOAD");
        return existing.response as UploadResponse;
      }
      const response: UploadResponse = { results: [], packets: [] };
      // Consistent ordering avoids unique-index lock cycles for overlapping batches.
      const ordered = packets.map((packet, index) => ({ packet, index }))
        .sort((a, b) => `${a.packet.envelope.kind}:${a.packet.payload.request_id ?? a.packet.payload.offer_id ?? a.packet.envelope.sender_node_id.toString("hex")}`
          .localeCompare(`${b.packet.envelope.kind}:${b.packet.payload.request_id ?? b.packet.payload.offer_id ?? b.packet.envelope.sender_node_id.toString("hex")}`));
      for (const id of [...new Set(packets.flatMap(packet => [packet.envelope.event_id, packet.envelope.packet_id,
        ...(packet.payload.offer_id ? [`offer:${packet.payload.offer_id}`] : []),
        ...(packet.payload.request_id ? [`request:${packet.payload.request_id}`] : []),
        ...(packet.payload.original_sos_envelope?.length ? (() => { const e = decode("ImmutableEnvelopeV1", packet.payload.original_sos_envelope); return [e.event_id, e.packet_id]; })() : [])]))].sort())
        await connection.query("SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", [id]);
      for (const { packet: verified, index } of ordered) {
        const { envelope: e, payload: p, packet } = verified;
        if (e.expires_at_ms <= this.now()) throw new PacketError("EXPIRED");
        const bytes = Buffer.from(packet.immutable_envelope_bytes, "base64url");
        // Serialize cross-family ID checks too: IDs cannot describe both SOS and resources.
        const decisionIds=await connection.query("SELECT event_id FROM assistance_decisions WHERE event_id=$1 OR packet_id=$2",[e.event_id,e.packet_id]);
        if(e.kind!==17 && decisionIds.rows.length)throw new UploadConflict("REPORT_ID_CONFLICT");
        if(e.kind===17 && (await connection.query("SELECT event_id FROM resource_events WHERE event_id=$1 OR packet_id=$2",[e.event_id,e.packet_id])).rows.length)
          throw new UploadConflict("REPORT_ID_CONFLICT");
        const other = await connection.query([1, 2].includes(e.kind)
          ? "SELECT event_id FROM resource_events WHERE event_id = $1 OR packet_id = $2"
          : "SELECT event_id FROM sos_reports WHERE event_id = $1 OR packet_id = $2", [e.event_id, e.packet_id]);
        if (other.rows.length) throw new UploadConflict("REPORT_ID_CONFLICT");
        const closureIds = await connection.query("SELECT event_id FROM sos_closures WHERE event_id = $1 OR packet_id = $2", [e.event_id, e.packet_id]);
        if (closureIds.rows.length && e.kind !== 2) throw new UploadConflict("REPORT_ID_CONFLICT");
        if (e.kind === 2 && (await connection.query("SELECT event_id FROM sos_reports WHERE event_id = $1 OR packet_id = $2", [e.event_id, e.packet_id])).rows.length) throw new UploadConflict("REPORT_ID_CONFLICT");
        const revision = Number((await connection.query("SELECT nextval('cloud_revision_seq') AS revision")).rows[0].revision);
        const received = this.now();
        const ack = signCloudPacket(this.signer, 7, { origin_event_id: e.event_id, status: 1,
          cloud_revision: revision, received_at_ms: received }, {
          packetId: randomUUID(), eventId: randomUUID(), createdAtMs: received,
          expiresAtMs: received + 86400000, destinationNodeId: e.sender_node_id.toString("hex"),
        });
        if (e.kind === 17) {
          const saved = await applyAssistanceDecision(connection,this.signer,verified,revision,ack,received);
          response.results[index] = {event_id:e.event_id,status:saved.duplicate?"DUPLICATE":"ACCEPTED",cloud_revision:Number(saved.row.cloud_revision)};
          response.packets[index] = saved.row.cloud_ack;
          continue;
        }
        if ([3, 5, 6].includes(e.kind)) {
          if (e.kind===6 && p.state===1) await checkCommittedEdit(connection,p.offer_id,p.quantity??null,p.unit??"");
          const saved = await saveResource(connection, verified, bytes, revision, received, ack);
          response.results[index] = { event_id: e.event_id, status: saved.duplicate ? "DUPLICATE" : "ACCEPTED", cloud_revision: Number(saved.row.cloud_revision) };
          response.packets[index] = saved.row.cloud_ack;
          continue;
        }
        if (e.kind === 2) {
          const original = validatePacket({ immutable_envelope_bytes: p.original_sos_envelope.toString("base64url"),
            signature: p.original_sos_signature.toString("base64url"), relay: { remaining_hops: 4, hop_count: 0 } }, { nowMs: received, forCloud: true });
          const baseRevision = Number((await connection.query("SELECT nextval('cloud_revision_seq') AS revision")).rows[0].revision);
          const baseAck = signCloudPacket(this.signer, 7, { origin_event_id: original.envelope.event_id, status: 1,
            cloud_revision: baseRevision, received_at_ms: received }, { packetId: randomUUID(), eventId: randomUUID(),
            createdAtMs: received, expiresAtMs: received + 86400000, destinationNodeId: e.sender_node_id.toString("hex") });
          await this.saveReport(connection, original, Buffer.from(original.packet.immutable_envelope_bytes, "base64url"), baseRevision, received, baseAck);
          const saved = await connection.query(`INSERT INTO sos_closures(event_id, packet_id, request_id, immutable_envelope,
            origin_signature, state, created_at_ms, expires_at_ms, received_at_ms, cloud_revision, cloud_ack)
            VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11::jsonb) ON CONFLICT DO NOTHING RETURNING cloud_revision, cloud_ack`,
            [e.event_id, e.packet_id, p.request_id, bytes, Buffer.from(packet.signature, "base64url"), p.state, e.created_at_ms,
              e.expires_at_ms, received, revision, JSON.stringify(ack)]);
          const duplicate = !saved.rows.length;
          const found = duplicate ? await connection.query("SELECT immutable_envelope, cloud_revision, cloud_ack FROM sos_closures WHERE event_id = $1 OR packet_id = $2", [e.event_id, e.packet_id]) : saved;
          if (found.rows.length !== 1 || duplicate && !Buffer.from(found.rows[0].immutable_envelope).equals(bytes)) throw new UploadConflict("REPORT_ID_CONFLICT");
          await connection.query(`UPDATE sos_reports SET state = (SELECT state FROM sos_closures WHERE request_id = $1
            ORDER BY created_at_ms DESC, event_id DESC LIMIT 1) WHERE request_id = $1`, [p.request_id]);
          response.results[index] = { event_id: e.event_id, status: duplicate ? "DUPLICATE" : "ACCEPTED", cloud_revision: Number(found.rows[0].cloud_revision) };
          response.packets[index] = found.rows[0].cloud_ack;
          continue;
        }
        const stored = await this.saveReport(connection, verified, bytes, revision, received, ack);
        response.results[index] = { event_id: e.event_id, status: stored.duplicate ? "DUPLICATE" : "ACCEPTED", cloud_revision: Number(stored.cloud_revision) };
        response.packets[index] = stored.cloud_ack as WirePacket;
      }
      await reconcileAssistance(connection,this.signer,this.now());
      await connection.query("UPDATE sync_uploads SET response = $3::jsonb WHERE principal_id = $1 AND idempotency_key = $2", [principalId, key, JSON.stringify(response)]);
      return response;
    });
  }

  private async saveReport(connection: SqlConnection, verified: VerifiedPacket, bytes: Buffer, revision: number, received: number, ack: WirePacket) {
    const { envelope: e, payload: p, packet } = verified;
    const conflict = await connection.query(`SELECT event_id FROM resource_events WHERE event_id = $1 OR packet_id = $2
      UNION ALL SELECT event_id FROM sos_closures WHERE event_id = $1 OR packet_id = $2`, [e.event_id, e.packet_id]);
    if (conflict.rows.length) throw new UploadConflict("REPORT_ID_CONFLICT");
    const saved = await connection.query(`INSERT INTO sos_reports (
      event_id, packet_id, request_id, sender_node_id, immutable_envelope, origin_signature,
      category, description, people_count, coarse_area, landmark, latitude, longitude, public_mesh_allowed, cloud_processing_allowed,
      created_at_ms, expires_at_ms, received_at_ms, cloud_revision, cloud_ack
    ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,TRUE,TRUE,$14,$15,$16,$17,$18::jsonb)
    ON CONFLICT DO NOTHING RETURNING cloud_revision`, [e.event_id, e.packet_id, p.request_id,
      e.sender_node_id.toString("hex"), bytes, Buffer.from(packet.signature, "base64url"), p.category,
      p.description, p.people_count ?? null, p.coarse_area ?? null, p.landmark ?? null,
      p.latitude ?? null, p.longitude ?? null, e.created_at_ms, e.expires_at_ms, received, revision, JSON.stringify(ack)]);
    const duplicate = !saved.rows.length;
    const stored = duplicate ? await this.existing(connection, verified, bytes) : { cloud_revision: revision, cloud_ack: ack };
    return { cloud_revision: Number(stored.cloud_revision), cloud_ack: stored.cloud_ack as WirePacket, duplicate };
  }

  private async existing(connection: SqlConnection, verified: VerifiedPacket, bytes: Buffer): Promise<Record<string, any>> {
    const { envelope: e, payload: p } = verified;
    const found = await connection.query(`SELECT immutable_envelope, cloud_revision, cloud_ack FROM sos_reports
      WHERE event_id = $1 OR packet_id = $2 OR request_id = $3`, [e.event_id, e.packet_id, p.request_id]);
    if (found.rows.length !== 1 || !Buffer.from(found.rows[0].immutable_envelope).equals(bytes))
      throw new UploadConflict("REPORT_ID_CONFLICT");
    return found.rows[0];
  }
}
