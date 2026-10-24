import { initializeAssistance } from "./assistance.js";
import { Pool } from "pg";

export interface SqlConnection {
  query(sql: string, values?: unknown[]): Promise<{ rows: Record<string, any>[] }>;
}
export interface Database extends SqlConnection {
  transaction<T>(work: (connection: SqlConnection) => Promise<T>): Promise<T>;
  close(): Promise<void>;
}

export function postgresDatabase(connectionString: string): Database {
  const pool = new Pool({ connectionString, max: 10, connectionTimeoutMillis: 5000,
    statement_timeout: 10000, query_timeout: 10000, application_name: "ghost" });
  // Idle connection failures must not crash the process or log credentials.
  // Active operations report a retryable failure through the upload handler.
  pool.on("error", () => {});
  return {
    query: (sql, values) => pool.query(sql, values),
    async transaction<T>(work: (connection: SqlConnection) => Promise<T>): Promise<T> {
      const client = await pool.connect();
      try {
        await client.query("BEGIN");
        const result = await work(client);
        await client.query("COMMIT");
        return result;
      } catch (error) {
        await client.query("ROLLBACK").catch(() => {});
        throw error;
      } finally { client.release(); }
    },
    close: () => pool.end(),
  };
}

export async function initializeDatabase(database: Database): Promise<void> {
  await database.transaction(async connection => {
    // Serialize first-time schema creation across server instances only.
    await connection.query("SELECT pg_advisory_xact_lock(715422701)");
    await connection.query("CREATE SEQUENCE IF NOT EXISTS cloud_revision_seq AS bigint MINVALUE 1 MAXVALUE 9007199254740991");
    await connection.query(`CREATE TABLE IF NOT EXISTS sos_reports (
      event_id UUID PRIMARY KEY,
      packet_id UUID NOT NULL UNIQUE,
      request_id UUID NOT NULL UNIQUE,
      sender_node_id TEXT NOT NULL,
      immutable_envelope BYTEA NOT NULL,
      origin_signature BYTEA NOT NULL,
      category SMALLINT NOT NULL CHECK (category BETWEEN 1 AND 6),
      description TEXT NOT NULL,
      people_count INTEGER,
      coarse_area TEXT,
      landmark TEXT,
      latitude DOUBLE PRECISION,
      longitude DOUBLE PRECISION,
      public_mesh_allowed BOOLEAN NOT NULL CHECK (public_mesh_allowed),
      cloud_processing_allowed BOOLEAN NOT NULL CHECK (cloud_processing_allowed),
      created_at_ms BIGINT NOT NULL,
      expires_at_ms BIGINT NOT NULL,
      received_at_ms BIGINT NOT NULL,
      cloud_revision BIGINT NOT NULL UNIQUE,
      cloud_ack JSONB NOT NULL
    )`);
    await connection.query("ALTER TABLE sos_reports ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION");
    await connection.query("ALTER TABLE sos_reports ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION");
    await connection.query("ALTER TABLE sos_reports ADD COLUMN IF NOT EXISTS state SMALLINT NOT NULL DEFAULT 1 CHECK (state IN (1,5,6))");
    await connection.query(`CREATE TABLE IF NOT EXISTS sos_closures (
      event_id UUID PRIMARY KEY, packet_id UUID NOT NULL UNIQUE, request_id UUID NOT NULL REFERENCES sos_reports(request_id),
      immutable_envelope BYTEA NOT NULL, origin_signature BYTEA NOT NULL,
      state SMALLINT NOT NULL CHECK (state IN (5,6)), created_at_ms BIGINT NOT NULL,
      expires_at_ms BIGINT NOT NULL, received_at_ms BIGINT NOT NULL,
      cloud_revision BIGINT NOT NULL UNIQUE, cloud_ack JSONB NOT NULL
    )`);
    await connection.query("CREATE INDEX IF NOT EXISTS sos_closure_request ON sos_closures(request_id, created_at_ms DESC, event_id DESC)");
    await connection.query(`CREATE TABLE IF NOT EXISTS sync_uploads (
      principal_id TEXT NOT NULL,
      idempotency_key TEXT NOT NULL,
      content_hash TEXT NOT NULL,
      response JSONB,
      created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      PRIMARY KEY (principal_id, idempotency_key)
    )`);
    await connection.query("CREATE INDEX IF NOT EXISTS sync_uploads_created_at ON sync_uploads(created_at)");
    await connection.query(`CREATE TABLE IF NOT EXISTS resource_events (
      event_id UUID PRIMARY KEY, packet_id UUID NOT NULL UNIQUE,
      kind SMALLINT NOT NULL CHECK (kind IN (3,5,6)), entity_id TEXT NOT NULL, owner_node_id TEXT NOT NULL,
      immutable_envelope BYTEA NOT NULL, origin_signature BYTEA NOT NULL, data JSONB NOT NULL,
      created_at_ms BIGINT NOT NULL, expires_at_ms BIGINT NOT NULL, received_at_ms BIGINT NOT NULL,
      cloud_revision BIGINT NOT NULL UNIQUE, cloud_ack JSONB NOT NULL
    )`);
    await connection.query("CREATE UNIQUE INDEX IF NOT EXISTS resource_offer_id ON resource_events(entity_id) WHERE kind = 5");
    await connection.query("CREATE INDEX IF NOT EXISTS resource_latest ON resource_events(kind, entity_id, created_at_ms DESC, event_id DESC)");
    await connection.query("ALTER TABLE resource_events DROP CONSTRAINT IF EXISTS resource_events_kind_check");
    await connection.query("ALTER TABLE resource_events ADD CONSTRAINT resource_events_kind_check CHECK (kind IN (3,5,6))");
    await initializeAssistance(connection);
    // Stable read surface for matching: one effective version per offer, including withdrawals.
    await connection.query(`CREATE OR REPLACE VIEW resource_offer_latest AS
      SELECT DISTINCT ON (entity_id) * FROM resource_events WHERE kind IN (5,6)
      ORDER BY entity_id, created_at_ms DESC, event_id DESC`);
  });
}
