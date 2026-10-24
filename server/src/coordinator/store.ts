import { randomUUID } from "node:crypto";
import { candidateSql } from "./candidates.js";
import type { Database, SqlConnection } from "../database.js";
import { CANDIDATES_PER_KIND, CoordinatorFailure, UUID, type MatchContext, type MatchDraft,
  type ResourceSource, type ReviewProposal, type RunResult, type SosSource, type SourceVersion } from "./types.js";

/** Run after initializeDatabase. Source publications stay in their existing tables. */
export async function initializeCoordinator(database: Database): Promise<void> {
  await database.transaction(async connection => {
    await connection.query("SELECT pg_advisory_xact_lock(715422701)");
    await connection.query(`CREATE TABLE IF NOT EXISTS coordinator_runs (
      run_id UUID PRIMARY KEY, request_id UUID NOT NULL, model_name TEXT,
      status TEXT NOT NULL CHECK (status IN ('running','completed','unavailable','error')),
      error_code TEXT, created_at_ms BIGINT NOT NULL, finished_at_ms BIGINT
    )`);
    await connection.query(`CREATE TABLE IF NOT EXISTS coordinator_proposals (
      proposal_id UUID PRIMARY KEY, run_id UUID NOT NULL REFERENCES coordinator_runs(run_id),
      request_id UUID NOT NULL, resource_kind TEXT NOT NULL CHECK (resource_kind IN ('helper','offer')),
      resource_id TEXT NOT NULL, owner_node_id TEXT NOT NULL,
      request_event_id UUID NOT NULL REFERENCES sos_reports(event_id), request_revision BIGINT NOT NULL,
      resource_event_id UUID NOT NULL REFERENCES resource_events(event_id), resource_revision BIGINT NOT NULL,
      quantity INTEGER CHECK (quantity > 0 AND quantity <= 1000000),
      explanation TEXT NOT NULL CHECK (length(explanation) BETWEEN 1 AND 1000),
      status TEXT NOT NULL DEFAULT 'pending_review' CHECK (status = 'pending_review'),
      created_at_ms BIGINT NOT NULL,
      CHECK (resource_kind <> 'helper' OR quantity IS NULL),
      UNIQUE (request_event_id, resource_event_id)
    )`);
    await connection.query("CREATE INDEX IF NOT EXISTS coordinator_review ON coordinator_proposals(created_at_ms DESC, proposal_id)");
    await connection.query(`CREATE TABLE IF NOT EXISTS coordinator_jobs (
      request_id UUID PRIMARY KEY REFERENCES sos_reports(request_id), input_revision BIGINT NOT NULL,
      queued BOOLEAN NOT NULL DEFAULT TRUE, requested_at_ms BIGINT NOT NULL,
      next_attempt_at_ms BIGINT NOT NULL DEFAULT 0, attempts INTEGER NOT NULL DEFAULT 0
    )`);
    await connection.query("CREATE INDEX IF NOT EXISTS coordinator_due ON coordinator_jobs(next_attempt_at_ms) WHERE queued");
    await connection.query("CREATE INDEX IF NOT EXISTS coordinator_request_runs ON coordinator_runs(request_id, created_at_ms DESC)");
    await connection.query(`CREATE TABLE IF NOT EXISTS coordinator_lease (
      id INTEGER PRIMARY KEY CHECK (id = 1), owner TEXT, expires_at_ms BIGINT NOT NULL DEFAULT 0
    )`);
    await connection.query("INSERT INTO coordinator_lease(id) VALUES (1) ON CONFLICT DO NOTHING");
    await connection.query("ALTER TABLE coordinator_jobs ADD COLUMN IF NOT EXISTS input_hash TEXT");
    await connection.query("ALTER TABLE coordinator_runs ADD COLUMN IF NOT EXISTS candidate_count INTEGER");
    await connection.query("ALTER TABLE coordinator_runs ADD COLUMN IF NOT EXISTS proposal_count INTEGER");
    await connection.query(`CREATE OR REPLACE VIEW coordinator_resource_latest AS
      SELECT DISTINCT ON (entity_id) * FROM resource_events WHERE kind IN (3,5,6)
      ORDER BY entity_id, created_at_ms DESC, event_id DESC`);
    await connection.query("CREATE INDEX IF NOT EXISTS coordinator_effective_resource ON resource_events(entity_id, created_at_ms DESC, event_id DESC)");
    await connection.query(`CREATE OR REPLACE VIEW coordinator_resource_available AS
      SELECT r.event_id,r.packet_id,r.kind,r.entity_id,r.owner_node_id,r.immutable_envelope,r.origin_signature,
        CASE WHEN r.kind=3 AND COALESCE(a.busy,0)>0 THEN r.data || '{"availability":2}'::jsonb
        WHEN r.kind<>3 AND r.data->>'quantity' IS NOT NULL THEN r.data || jsonb_build_object('quantity',
          GREATEST(0,(r.data->>'quantity')::integer-COALESCE(a.used,0))) ELSE r.data END AS data,
        r.created_at_ms,r.expires_at_ms,r.received_at_ms,r.cloud_revision,r.cloud_ack
      FROM coordinator_resource_latest r LEFT JOIN (
        SELECT resource_id,COUNT(*) FILTER(WHERE state=2) AS busy,SUM(quantity)::integer AS used FROM assistance_cases WHERE state IN(2,6,7)
        GROUP BY resource_id) a ON a.resource_id=r.entity_id`);
    await connection.query(`CREATE TABLE IF NOT EXISTS coordinator_model_budget (
      window_start_ms BIGINT PRIMARY KEY, calls INTEGER NOT NULL CHECK (calls > 0)
    )`);
  });
}

const source = (row: Record<string, any>): SourceVersion => ({ event_id: row.event_id, cloud_revision: Number(row.cloud_revision) });
const sameSource = (a: SourceVersion, b: SourceVersion): boolean => a.event_id === b.event_id && a.cloud_revision === b.cloud_revision;

async function readRequest(connection: SqlConnection, requestId: string, now: number): Promise<SosSource> {
  const { rows } = await connection.query(`SELECT request_id, event_id, cloud_revision, category, description,
    people_count, coarse_area, landmark, expires_at_ms FROM sos_reports
    WHERE request_id = $1 AND state = 1 AND expires_at_ms > $2 AND public_mesh_allowed AND cloud_processing_allowed FOR SHARE`, [requestId, now]);
  if (!rows.length) throw new CoordinatorFailure("REQUEST_UNAVAILABLE");
  const row = rows[0];
  return { request_id: row.request_id, source: source(row), category: Number(row.category), description: row.description,
    people_count: row.people_count === null ? null : Number(row.people_count), coarse_area: row.coarse_area,
    landmark: row.landmark, expires_at_ms: Number(row.expires_at_ms) };
}

function resource(row: Record<string, any>, now: number): ResourceSource | undefined {
  const data = row.data;
  const helper = Number(row.kind) === 3;
  const expires = Math.min(Number(row.expires_at_ms), helper ? Infinity : Number(data.available_until_ms ?? row.expires_at_ms));
  if (!Number.isSafeInteger(expires) || expires <= now) return;
  if (helper ? data.availability !== 1 || !Array.isArray(data.help_types) || !data.help_types.length :
    data.state !== undefined && data.state !== 1) return;
  const quantity = data.quantity ?? null;
  if (!helper && quantity !== null && (!Number.isSafeInteger(quantity) || quantity <= 0 || quantity > 1000000)) return;
  return { kind: helper ? "helper" : "offer", resource_id: row.entity_id, owner_node_id: row.owner_node_id,
    source: source(row), coarse_area: data.coarse_area ?? null, help_types: helper ? data.help_types : [],
    resource_type: helper ? null : data.resource_type, quantity: helper ? null : quantity,
    unit: helper ? null : data.unit ?? null, expires_at_ms: expires };
}

async function readResources(connection: SqlConnection, now: number, ids?: string[], area?: string | null): Promise<ResourceSource[]> {
  const resources: ResourceSource[] = [];
  for (const kind of [3, 5]) {
    // Pick latest BEFORE expiry/availability filtering: never resurrect an older available report.
    // Match the ingestion/dashboard ordering, including the event-ID tie breaker.
    const { rows } = await connection.query(`SELECT * FROM (
      SELECT DISTINCT ON (entity_id) entity_id, event_id, owner_node_id, kind, data,
        created_at_ms, expires_at_ms, cloud_revision
      FROM coordinator_resource_available
      WHERE (CASE WHEN $1=5 THEN kind IN(5,6) ELSE kind=$1 END) AND ($2::text[] IS NULL OR entity_id = ANY($2))
      ORDER BY entity_id, created_at_ms DESC, event_id DESC
    ) latest WHERE expires_at_ms > $3
      AND ($1 <> 3 OR data->>'availability' = '1')
      AND ($1 <> 5 OR (COALESCE(data->>'state', '1') = '1'
        AND COALESCE((data->>'available_until_ms')::bigint, expires_at_ms) > $3
        AND (data->>'quantity' IS NULL OR (data->>'quantity')::bigint > 0)))
    ORDER BY CASE WHEN data->>'coarse_area' = $4 THEN 0 ELSE 1 END,
      created_at_ms DESC, event_id DESC LIMIT $5`, [kind, ids ?? null, now, area ?? null, ids ? ids.length : CANDIDATES_PER_KIND]);
    for (const row of rows) {
      const eligible = resource(row, now);
      if (eligible) resources.push(eligible);
    }
  }
  return resources;
}

function validQuantity(draft: MatchDraft, current: ResourceSource): boolean {
  return current.kind === "helper" || current.quantity === null ? draft.quantity === null :
    Number.isSafeInteger(draft.quantity) && draft.quantity! > 0 && draft.quantity! <= current.quantity;
}

const projection = (row: Record<string, any>): ReviewProposal => ({
  proposal_id: row.proposal_id, run_id: row.run_id, status: "pending_review", request_id: row.request_id,
  resource_kind: row.resource_kind, resource_id: row.resource_id, owner_node_id: row.owner_node_id,
  request_source: { event_id: row.request_event_id, cloud_revision: Number(row.request_revision) },
  resource_source: { event_id: row.resource_event_id, cloud_revision: Number(row.resource_revision) },
  quantity: row.quantity === null ? null : Number(row.quantity), explanation: row.explanation, created_at_ms: Number(row.created_at_ms),
});

export class CoordinatorStore {
  constructor(private readonly database: Database) {}

  async reserveModelCall(now: number, limit: number): Promise<boolean> {
    const window = Math.floor(now / 3600000) * 3600000;
    const result = await this.database.query(`INSERT INTO coordinator_model_budget(window_start_ms, calls) VALUES ($1,1)
      ON CONFLICT (window_start_ms) DO UPDATE SET calls = coordinator_model_budget.calls + 1
      WHERE coordinator_model_budget.calls < $2 RETURNING calls`, [window, limit]);
    return result.rows.length === 1;
  }

  async modelBudget(now: number, limit: number) {
    const window = Math.floor(now / 3600000) * 3600000;
    const { rows } = await this.database.query("SELECT calls FROM coordinator_model_budget WHERE window_start_ms = $1", [window]);
    return { used: Number(rows[0]?.calls ?? 0), limit, resets_at_ms: window + 3600000 };
  }

  async start(requestId: string, model: string | null, now: number): Promise<string> {
    const id = randomUUID();
    await this.database.query(`INSERT INTO coordinator_runs (run_id, request_id, model_name, status, created_at_ms)
      VALUES ($1,$2,$3,'running',$4)`, [id, requestId, model, now]);
    return id;
  }

  async context(requestId: string, now: number): Promise<MatchContext> {
    return this.database.transaction(async connection => {
      await connection.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
      const request = await readRequest(connection, requestId, now);
      const { rows } = await connection.query(`SELECT candidates.* FROM sos_reports s
        CROSS JOIN LATERAL (${candidateSql}) candidates WHERE s.request_id = $2
        ORDER BY CASE WHEN candidates.kind = 3 THEN 0 ELSE 1 END, candidates.candidate_rank`, [now, requestId]);
      return { request, resources: rows.map(row => resource(row, now)).filter((r): r is ResourceSource => !!r) };
    });
  }

  async fail(runId: string, status: "unavailable" | "error", code: RunResult["code"], now: number): Promise<void> {
    await this.database.query(`UPDATE coordinator_runs SET status = $2, error_code = $3, finished_at_ms = $4
      WHERE run_id = $1 AND status = 'running'`, [runId, status, code, now]);
  }

  async recordCandidates(runId: string, count: number): Promise<void> {
    await this.database.query("UPDATE coordinator_runs SET candidate_count = $2 WHERE run_id = $1 AND status = 'running'", [runId, count]);
  }

  async save(runId: string, context: MatchContext, drafts: MatchDraft[], now: number, leaseOwner?: string): Promise<string[]> {
    return this.database.transaction(async connection => {
      await connection.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
      if (leaseOwner) {
        const lease = await connection.query("SELECT owner, expires_at_ms FROM coordinator_lease WHERE id = 1 FOR UPDATE");
        if (lease.rows[0]?.owner !== leaseOwner || Number(lease.rows[0].expires_at_ms) <= now) throw new CoordinatorFailure("STALE_SOURCE");
      }
      const request = await readRequest(connection, context.request.request_id, now);
      if (!sameSource(request.source, context.request.source)) throw new CoordinatorFailure("STALE_SOURCE");
      const current = drafts.length ? await readResources(connection, now, drafts.map(d => d.resource_id)) : [];
      const ids: string[] = [];
      for (const draft of drafts) {
        const original = context.resources.find(r => r.kind === draft.resource_kind && r.resource_id === draft.resource_id);
        const latest = current.find(r => r.kind === draft.resource_kind && r.resource_id === draft.resource_id);
        if (!original || !latest || !sameSource(original.source, latest.source) || !validQuantity(draft, latest))
          throw new CoordinatorFailure("STALE_SOURCE");
        const result = await connection.query(`INSERT INTO coordinator_proposals (
          proposal_id, run_id, request_id, resource_kind, resource_id, owner_node_id,
          request_event_id, request_revision, resource_event_id, resource_revision, quantity, explanation, created_at_ms
        ) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13)
        ON CONFLICT (request_event_id, resource_event_id) DO NOTHING RETURNING proposal_id`, [
          randomUUID(), runId, request.request_id, latest.kind, latest.resource_id, latest.owner_node_id,
          request.source.event_id, request.source.cloud_revision, latest.source.event_id, latest.source.cloud_revision,
          draft.quantity, draft.explanation, now]);
        const existing = result.rows.length ? result : await connection.query(`SELECT proposal_id FROM coordinator_proposals
          WHERE request_event_id = $1 AND resource_event_id = $2`, [request.source.event_id, latest.source.event_id]);
        ids.push(existing.rows[0].proposal_id);
      }
      await connection.query("UPDATE coordinator_runs SET status = 'completed', finished_at_ms = $2, proposal_count = $3 WHERE run_id = $1", [runId, now, ids.length]);
      return ids;
    });
  }

  async listProposals(limit = 30): Promise<ReviewProposal[]> {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100) throw new RangeError("Invalid proposal limit");
    const { rows } = await this.database.query(`SELECT * FROM coordinator_proposals
      WHERE status = 'pending_review' ORDER BY created_at_ms DESC, proposal_id LIMIT $1`, [limit]);
    return rows.map(projection);
  }

  async review(now: number) { return (await this.reviewPage(now)).items; }

  async reviewPage(now: number, limit = 30, before?: { createdAt: number; id: string }, requestId?: string) {
    if (!Number.isSafeInteger(limit) || limit < 1 || limit > 100 ||
        before && (!Number.isSafeInteger(before.createdAt) || before.createdAt < 1 || !UUID.test(before.id)) ||
        requestId !== undefined && !UUID.test(requestId)) throw new RangeError("Invalid proposal pagination");
    const { rows } = await this.database.query(`SELECT p.*, s.description AS request_description,
      s.coarse_area AS request_area, s.state AS request_state, s.expires_at_ms AS request_expires_at_ms,
      s.cloud_revision AS current_request_revision,
      r.data AS resource_data, current.kind AS current_kind, current.data AS current_data,
      current.entity_id AS current_entity, current.owner_node_id AS current_owner,
      current.event_id AS current_event, current.cloud_revision AS current_revision,
      current.expires_at_ms AS current_expiry
      FROM coordinator_proposals p
      JOIN sos_reports s ON s.event_id = p.request_event_id
      JOIN resource_events r ON r.event_id = p.resource_event_id
      LEFT JOIN coordinator_resource_available current ON current.entity_id = p.resource_id
      WHERE p.status = 'pending_review' AND NOT EXISTS (SELECT 1 FROM assistance_cases a WHERE a.proposal_id=p.proposal_id) AND ($2::bigint IS NULL OR (p.created_at_ms, p.proposal_id) < ($2, $3::uuid))
        AND ($4::uuid IS NULL OR p.request_id = $4)
      ORDER BY p.created_at_ms DESC, p.proposal_id DESC LIMIT $1`, [limit + 1, before?.createdAt ?? null, before?.id ?? null, requestId ?? null]);
    const items = rows.slice(0, limit).map(row => {
      const proposal = projection(row);
      const latest = row.current_event ? resource({ kind: row.current_kind, data: row.current_data,
        entity_id: row.current_entity, owner_node_id: row.current_owner, event_id: row.current_event,
        cloud_revision: row.current_revision, expires_at_ms: row.current_expiry }, now) : undefined;
      const current = row.request_state === 1 && Number(row.request_expires_at_ms) > now &&
        Number(row.current_request_revision) === proposal.request_source.cloud_revision && latest &&
        sameSource(latest.source, proposal.resource_source) && validQuantity(proposal, latest);
      return { ...proposal, request_description: row.request_description, request_area: row.request_area,
        request_expires_at_ms: Number(row.request_expires_at_ms),
        resource_label: row.resource_kind === "helper" ? row.resource_data.display_name ?? "Unnamed helper" : null,
        resource_type: row.resource_data.resource_type ?? null, unit: row.resource_data.unit ?? null,
        coarse_area: row.resource_data.coarse_area ?? null, resource_expires_at_ms: latest?.expires_at_ms ?? null,
        freshness: current ? "current" as const : "stale" as const };
    });
    const last = items.at(-1);
    return { items, next_before: rows.length > limit && last ? `${last.created_at_ms}.${last.proposal_id}` : null, evaluated_at_ms: now };
  }

  async history() {
    const { rows } = await this.database.query(`SELECT run_id, request_id, model_name, status, error_code,
      created_at_ms, finished_at_ms, candidate_count, proposal_count FROM coordinator_runs ORDER BY created_at_ms DESC, run_id DESC LIMIT 20`);
    return rows.map(row => ({ run_id: row.run_id as string, request_id: row.request_id as string,
      model_name: row.model_name as string | null, status: row.status as string, error_code: row.error_code as string | null,
      created_at_ms: Number(row.created_at_ms),
      candidate_count: row.candidate_count === null ? null : Number(row.candidate_count),
      proposal_count: row.proposal_count === null ? null : Number(row.proposal_count),
      finished_at_ms: row.finished_at_ms === null ? null : Number(row.finished_at_ms) }));
  }

  /** Advisory freshness check only. Future approval must recheck inside its allocation transaction. */
  async checkProposal(proposalId: string, now: number): Promise<"current" | "stale" | "not_found"> {
    if (!UUID.test(proposalId)) return "not_found";
    return this.database.transaction(async connection => {
      await connection.query("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
      const { rows } = await connection.query("SELECT * FROM coordinator_proposals WHERE proposal_id = $1", [proposalId]);
      if (!rows.length) return "not_found";
      const proposal = projection(rows[0]);
      try {
        const request = await readRequest(connection, proposal.request_id, now);
        const latest = (await readResources(connection, now, [proposal.resource_id]))
          .find(r => r.kind === proposal.resource_kind && r.resource_id === proposal.resource_id);
        return latest && sameSource(request.source, proposal.request_source) && sameSource(latest.source, proposal.resource_source) &&
          validQuantity(proposal, latest) ? "current" : "stale";
      } catch (error) {
        if (error instanceof CoordinatorFailure && error.code === "REQUEST_UNAVAILABLE") return "stale";
        throw error;
      }
    });
  }
}
