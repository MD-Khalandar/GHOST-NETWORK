import { createHash, randomUUID } from "node:crypto";
import type { Database, SqlConnection } from "./database.js";
import { PacketError, signCloudPacket, type VerifiedPacket, type WirePacket } from "./protocol.js";
import type { SigningIdentity } from "./security.js";

export class UploadConflict extends Error {
  constructor(readonly code: "IDEMPOTENCY_CONFLICT" | "REPORT_ID_CONFLICT") { super(code); }
}
export interface UploadResponse {
  results: Array<{ event_id: string; status: "ACCEPTED" | "DUPLICATE"; cloud_revision: number }>;
  packets: WirePacket[];
}

export class SosStore {
  constructor(private readonly database: Database, private readonly signer: SigningIdentity,
              private readonly now: () => number = Date.now) {}

  async upload(principalId: string, key: string, packets: VerifiedPacket[]): Promise<UploadResponse> {
    // Mutable relay metadata and alternate valid ECDSA signatures do not change
    // a report's identity. Preserve order to replay the same batch response.
    const hash = createHash("sha256").update(JSON.stringify(packets.map(p => p.packet.immutable_envelope_bytes))).digest("hex");
    return this.database.transaction(async connection => {
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
        .sort((a, b) => a.packet.payload.request_id.localeCompare(b.packet.payload.request_id));
      for (const { packet: verified, index } of ordered) {
        const { envelope: e, payload: p, packet } = verified;
        if (e.expires_at_ms <= this.now()) throw new PacketError("EXPIRED");
        const bytes = Buffer.from(packet.immutable_envelope_bytes, "base64url");
        const revision = Number((await connection.query("SELECT nextval('cloud_revision_seq') AS revision")).rows[0].revision);
        const received = this.now();
        const ack = signCloudPacket(this.signer, 7, { origin_event_id: e.event_id, status: 1,
          cloud_revision: revision, received_at_ms: received }, {
          packetId: randomUUID(), eventId: randomUUID(), createdAtMs: received,
          expiresAtMs: received + 86400000, destinationNodeId: e.sender_node_id.toString("hex"),
        });
        const saved = await connection.query(`INSERT INTO sos_reports (
          event_id, packet_id, request_id, sender_node_id, immutable_envelope, origin_signature,
          category, description, people_count, coarse_area, landmark, public_mesh_allowed, cloud_processing_allowed,
          created_at_ms, expires_at_ms, received_at_ms, cloud_revision, cloud_ack
        ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,TRUE,TRUE,$12,$13,$14,$15,$16::jsonb)
        ON CONFLICT DO NOTHING RETURNING cloud_revision`, [e.event_id, e.packet_id, p.request_id,
          e.sender_node_id.toString("hex"), bytes, Buffer.from(packet.signature, "base64url"), p.category,
          p.description, p.people_count ?? null, p.coarse_area ?? null, p.landmark ?? null,
          e.created_at_ms, e.expires_at_ms, received, revision, JSON.stringify(ack)]);
        const duplicate = !saved.rows.length;
        const stored = duplicate ? await this.existing(connection, verified, bytes) : { cloud_revision: revision, cloud_ack: ack };
        response.results[index] = { event_id: e.event_id, status: duplicate ? "DUPLICATE" : "ACCEPTED", cloud_revision: Number(stored.cloud_revision) };
        response.packets[index] = stored.cloud_ack as WirePacket;
      }
      await connection.query("UPDATE sync_uploads SET response = $3::jsonb WHERE principal_id = $1 AND idempotency_key = $2", [principalId, key, JSON.stringify(response)]);
      // The caller receives success only after this transaction commits.
      return response;
    });
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
