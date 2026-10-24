import type { Database } from "../database.js";
import { CloudCoordinator } from "./coordinator.js";
import { CoordinatorFailure, UUID, type ProposalModel } from "./types.js";
import { CoordinatorStore } from "./store.js";
import { randomUUID } from "node:crypto";
import { inputHashSql } from "./candidates.js";

/** Durable queue inside the existing server. One model call at a time across instances. */
export class CoordinatorWorker {
  private timer?: ReturnType<typeof setInterval>;
  private active?: Promise<void>;
  private stopped = true;
  private wakeAgain = false;
  readonly store: CoordinatorStore;
  private readonly coordinator: CloudCoordinator;
  constructor(private readonly database: Database, readonly model: ProposalModel, private readonly now = Date.now,
    private readonly maxCallsPerHour = 120,private readonly autoReview?:()=>Promise<boolean>) {
    this.store = new CoordinatorStore(database);
    this.coordinator = new CloudCoordinator(this.store, model, now, maxCallsPerHour);
  }
  start() {
    this.stopped = false;
    if (!this.model.available && !this.autoReview) return;
    this.timer = setInterval(() => this.wake(), 15000);
    this.timer.unref(); this.wake();
  }
  wake() {
    if (this.stopped || !this.model.available && !this.autoReview) return;
    if (this.active) { this.wakeAgain = true; return; }
    this.active = this.tick().catch(() => { /* Durable jobs retry after storage recovers. No record/provider errors in logs. */ })
      .finally(() => {
        this.active = undefined;
        if (this.wakeAgain && !this.stopped) { this.wakeAgain = false; this.wake(); }
      });
  }
  async stop() { this.stopped = true; clearInterval(this.timer); await this.active; }

  async enqueue(requestId: string): Promise<boolean> {
    if (!UUID.test(requestId)) throw new CoordinatorFailure("REQUEST_UNAVAILABLE");
    const { rows } = await this.database.query(`SELECT s.cloud_revision, j.queued, j.requested_at_ms FROM sos_reports s
      LEFT JOIN coordinator_jobs j ON j.request_id = s.request_id
      WHERE s.request_id = $1 AND s.state = 1 AND s.expires_at_ms > $2 AND s.public_mesh_allowed AND s.cloud_processing_allowed`, [requestId, this.now()]);
    if (!rows.length) throw new CoordinatorFailure("REQUEST_UNAVAILABLE");
    if (rows[0].queued) return true;
    if (rows[0].requested_at_ms !== null && rows[0].requested_at_ms !== undefined && this.now() - Number(rows[0].requested_at_ms) < 30000) return false;
    await this.database.query(`INSERT INTO coordinator_jobs(request_id, input_revision, requested_at_ms)
      VALUES ($1,$2,$3) ON CONFLICT (request_id) DO UPDATE SET queued = TRUE,
      requested_at_ms = EXCLUDED.requested_at_ms, next_attempt_at_ms = 0, attempts = 0`, [requestId, rows[0].cloud_revision, this.now()]);
    this.wake(); return true;
  }

  private async discover() {
    // A generation advances only when this SOS's candidate set changes. Expiry,
    // withdrawals and type changes are discovered even without a new upload.
    await this.database.query(`WITH current_resources AS MATERIALIZED (SELECT * FROM coordinator_resource_available), inputs AS MATERIALIZED (
      SELECT s.request_id, s.created_at_ms, ${inputHashSql} AS input_hash FROM sos_reports s
      WHERE s.state = 1 AND s.expires_at_ms > $1 AND s.public_mesh_allowed AND s.cloud_processing_allowed
    ) INSERT INTO coordinator_jobs(request_id, input_revision, requested_at_ms, input_hash)
      SELECT i.request_id, 1, $1, i.input_hash FROM inputs i
      LEFT JOIN coordinator_jobs j ON j.request_id = i.request_id
      WHERE j.request_id IS NULL OR j.input_hash IS DISTINCT FROM i.input_hash
      ORDER BY i.created_at_ms, i.request_id LIMIT 20
      ON CONFLICT (request_id) DO UPDATE SET
        input_hash = EXCLUDED.input_hash, input_revision = coordinator_jobs.input_revision + 1,
        queued = TRUE, attempts = 0, next_attempt_at_ms = 0, requested_at_ms = EXCLUDED.requested_at_ms
      WHERE coordinator_jobs.input_hash IS DISTINCT FROM EXCLUDED.input_hash`, [this.now()]);
  }
  private async tick() {
    if(await this.autoReview?.())this.wakeAgain=true;
    if(!this.model.available)return;
    await this.discover();
    for (let index = 0; index < 5 && !this.stopped; index++) {
      const token = randomUUID();
      const job = await this.database.transaction(async connection => {
        const lease = await connection.query(`UPDATE coordinator_lease SET owner = $1, expires_at_ms = $2
          WHERE id = 1 AND (owner IS NULL OR expires_at_ms <= $3) RETURNING owner`, [token, this.now() + 90000, this.now()]);
        if (!lease.rows.length) return null;
        const { rows } = await connection.query(`SELECT j.request_id, j.attempts, j.input_revision FROM coordinator_jobs j
          JOIN sos_reports s ON s.request_id = j.request_id
          WHERE j.queued AND s.state = 1 AND j.next_attempt_at_ms <= $1 AND s.expires_at_ms > $1
          ORDER BY j.requested_at_ms, j.request_id FOR UPDATE OF j SKIP LOCKED LIMIT 1`, [this.now()]);
        if (!rows.length) {
          await connection.query("UPDATE coordinator_lease SET owner = NULL WHERE id = 1 AND owner = $1", [token]);
          return null;
        }
        await connection.query(`UPDATE coordinator_runs SET status = 'error', error_code = 'STORAGE_UNAVAILABLE', finished_at_ms = $2
          WHERE request_id = $1 AND status = 'running'`, [rows[0].request_id, this.now()]);
        return rows[0];
      });
      if (!job) break;
      // No database transaction stays open while awaiting the provider. Fencing at save
      // prevents a process that lost its lease from committing suggestions.
      const heartbeat = setInterval(() => { void this.database.query(`UPDATE coordinator_lease SET expires_at_ms = $2
        WHERE id = 1 AND owner = $1`, [token, this.now() + 90000]).catch(() => {}); }, 15000);
      heartbeat.unref();
      try {
        const result = await this.coordinator.run(job.request_id, token);
        if(await this.autoReview?.())this.wakeAgain=true;
        if (result.code === "MODEL_BUDGET_EXHAUSTED") {
          // A budget pause is not a failed delivery or an exhausted retry. Keep
          // the job durable until the next hourly window.
          await this.database.query(`UPDATE coordinator_jobs SET queued = TRUE, next_attempt_at_ms = $2
            WHERE request_id = $1 AND EXISTS (SELECT 1 FROM coordinator_lease WHERE id = 1 AND owner = $3 AND expires_at_ms > $4)`,
            [job.request_id, Math.floor(this.now() / 3600000) * 3600000 + 3600000, token, this.now()]);
          continue;
        }
        const attempts = Number(job.attempts) + 1;
        const retry = attempts < 3 && ["MODEL_ERROR", "STORAGE_UNAVAILABLE", "STALE_SOURCE"].includes(result.code ?? "");
        await this.database.query(`UPDATE coordinator_jobs SET queued = (input_revision > $5 OR $2),
          attempts = CASE WHEN input_revision > $5 THEN 0 ELSE $3 END,
          next_attempt_at_ms = CASE WHEN input_revision > $5 THEN 0::bigint ELSE $4::bigint END
          WHERE request_id = $1 AND EXISTS (SELECT 1 FROM coordinator_lease WHERE id = 1 AND owner = $6 AND expires_at_ms > $7)`,
          [job.request_id, retry, attempts, this.now() + 15000 * 2 ** attempts, job.input_revision, token, this.now()]);
      } finally {
        clearInterval(heartbeat);
        await this.database.query("UPDATE coordinator_lease SET owner = NULL WHERE id = 1 AND owner = $1", [token]);
      }
      if (index === 4) this.wakeAgain = true; // Continue draining bounded batches without a 15-second gap.
    }
  }
  async status() {
    const { rows } = await this.database.query(`SELECT j.request_id, j.attempts, j.next_attempt_at_ms, COUNT(*) OVER() AS total_queued FROM coordinator_jobs j
      JOIN sos_reports s ON s.request_id = j.request_id WHERE j.queued AND s.state = 1 AND s.expires_at_ms > $1
      ORDER BY j.requested_at_ms LIMIT 30`, [this.now()]);
    return { configured: this.model.available, model: this.model.name, budget: await this.store.modelBudget(this.now(), this.maxCallsPerHour),
      queued_total: Number(rows[0]?.total_queued ?? 0), queued: rows.map(row => ({ request_id: row.request_id, attempts: row.attempts,
      next_attempt_at_ms: Number(row.next_attempt_at_ms) })), runs: await this.store.history() };
  }
}
