import { randomBytes, randomUUID } from "node:crypto";
import type { FastifyInstance } from "fastify";
import type { Database } from "./database.js";
import { nodeId, publicKey, SigningIdentity, tokenHash, verifySignature, type Principal } from "./security.js";

const CHALLENGE_TTL = 60_000;
const SESSION_TTL = 24 * 60 * 60_000;
const CHALLENGE_DOMAIN = "GHOST-GATEWAY-CHALLENGE-V1\n";
const SESSION_DOMAIN = "GHOST-GATEWAY-SESSION-V1\n";
export const ENROLLMENT_REQUESTS_PER_IP_MINUTE = 600;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
interface SignedReply { signed_bytes: string; signature: string }
class EnrollmentError extends Error {
  constructor(readonly status: number, readonly code: string) { super(code); }
}
function decode(value: unknown, maximum: number): Buffer {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]+$/.test(value) || value.length > maximum * 2)
    throw new EnrollmentError(400, "INVALID_ENROLLMENT");
  const bytes = Buffer.from(value, "base64url");
  if (bytes.length > maximum || bytes.toString("base64url") !== value)
    throw new EnrollmentError(400, "INVALID_ENROLLMENT");
  return bytes;
}

export async function initializeEnrollment(database: Database): Promise<void> {
  await database.transaction(async connection => {
    await connection.query("SELECT pg_advisory_xact_lock(715422701)");
    await connection.query(`CREATE TABLE IF NOT EXISTS gateway_devices (
      node_id TEXT PRIMARY KEY, token_sha256 TEXT UNIQUE NOT NULL,
      expires_at_ms BIGINT NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE
    )`);
    await connection.query(`CREATE TABLE IF NOT EXISTS gateway_challenges (
      challenge_id UUID PRIMARY KEY, node_id TEXT NOT NULL, public_key BYTEA NOT NULL,
      signed_bytes BYTEA NOT NULL, expires_at_ms BIGINT NOT NULL
    )`);
    await connection.query("CREATE INDEX IF NOT EXISTS gateway_challenge_expiry ON gateway_challenges(expires_at_ms)");
    await connection.query("CREATE INDEX IF NOT EXISTS gateway_challenge_node ON gateway_challenges(node_id)");
  });
}

/** An enrolled key grants upload access, never human identity or dashboard authority. */
export class GatewayEnrollment {
  private readonly limits = new Map<string, { since: number; count: number }>();
  private window = { since: 0, count: 0 };
  constructor(private readonly database: Database, private readonly signer: SigningIdentity,
    private readonly now: () => number = Date.now) {}

  private admit(ip: string) {
    const now = this.now();
    for (const [key, value] of this.limits) if (now - value.since >= 60_000) this.limits.delete(key);
    if (now - this.window.since >= 60_000) this.window = { since: now, count: 0 };
    const value = this.limits.get(ip) ?? { since: now, count: 0 };
    if (++this.window.count > 2000 || value.count >= ENROLLMENT_REQUESTS_PER_IP_MINUTE || !this.limits.has(ip) && this.limits.size >= 1024)
      throw new EnrollmentError(429, "ENROLLMENT_BUSY");
    value.count++;
    this.limits.set(ip, value);
  }

  private sign(bytes: Buffer): SignedReply {
    return { signed_bytes: bytes.toString("base64url"), signature: this.signer.sign(bytes).toString("base64url") };
  }

  async challenge(body: unknown, ip: string): Promise<SignedReply> {
    this.admit(ip);
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length !== 1)
      throw new EnrollmentError(400, "INVALID_ENROLLMENT");
    const key = decode((body as { public_key?: unknown }).public_key, 91);
    try { if (key.length !== 91) throw new Error(); publicKey(key); }
    catch { throw new EnrollmentError(400, "INVALID_KEY"); }
    const node = nodeId(key);
    const id = randomUUID();
    const expiry = this.now() + CHALLENGE_TTL;
    const bytes = Buffer.from(CHALLENGE_DOMAIN + JSON.stringify({ challenge_id: id, node_id: node,
      nonce: randomBytes(32).toString("base64url"), expires_at_ms: expiry }));
    const challengeBytes = await this.database.transaction(async connection => {
      await connection.query("SELECT pg_advisory_xact_lock(715422703)");
      const device = await connection.query("SELECT revoked FROM gateway_devices WHERE node_id = $1", [node]);
      if (device.rows[0]?.revoked) throw new EnrollmentError(403, "DEVICE_REVOKED");
      await connection.query("DELETE FROM gateway_challenges WHERE expires_at_ms <= $1", [this.now()]);
      // A public key is not proof of possession. Reuse its unexpired challenge so
      // another caller cannot revoke an in-progress handshake by submitting it.
      const existing = await connection.query("SELECT signed_bytes FROM gateway_challenges WHERE node_id = $1 ORDER BY expires_at_ms DESC LIMIT 1", [node]);
      if (existing.rows.length) return Buffer.from(existing.rows[0].signed_bytes);
      const count = await connection.query("SELECT COUNT(*) AS count FROM gateway_challenges");
      if (Number(count.rows[0].count) >= 1024) throw new EnrollmentError(429, "ENROLLMENT_BUSY");
      await connection.query(`INSERT INTO gateway_challenges VALUES ($1,$2,$3,$4,$5)`, [id, node, key, bytes, expiry]);
      return bytes;
    });
    return this.sign(challengeBytes);
  }

  async enroll(body: unknown, ip: string): Promise<SignedReply> {
    this.admit(ip);
    if (!body || typeof body !== "object" || Array.isArray(body) || Object.keys(body).length !== 2)
      throw new EnrollmentError(400, "INVALID_ENROLLMENT");
    const request = body as { challenge_bytes?: unknown; signature?: unknown };
    const bytes = decode(request.challenge_bytes, 1024);
    const signature = decode(request.signature, 72);
    let id: string;
    try {
      const raw = bytes.toString("utf8");
      if (!raw.startsWith(CHALLENGE_DOMAIN)) throw new Error();
      id = JSON.parse(raw.slice(CHALLENGE_DOMAIN.length)).challenge_id;
      if (!UUID.test(id)) throw new Error();
    } catch { throw new EnrollmentError(400, "INVALID_ENROLLMENT"); }
    return this.database.transaction(async connection => {
      await connection.query("SELECT pg_advisory_xact_lock(715422703)");
      const { rows } = await connection.query("DELETE FROM gateway_challenges WHERE challenge_id = $1 RETURNING *", [id]);
      const challenge = rows[0];
      if (!challenge || Number(challenge.expires_at_ms) <= this.now() || !Buffer.from(challenge.signed_bytes).equals(bytes))
        throw new EnrollmentError(401, "CHALLENGE_EXPIRED");
      let valid = false;
      try { valid = verifySignature(Buffer.from(challenge.public_key), bytes, signature); } catch { /* Reject below. */ }
      if (!valid) throw new EnrollmentError(401, "INVALID_PROOF");
      const device = await connection.query("SELECT revoked FROM gateway_devices WHERE node_id = $1", [challenge.node_id]);
      if (device.rows[0]?.revoked) throw new EnrollmentError(403, "DEVICE_REVOKED");
      if (!device.rows.length) {
        const count = await connection.query("SELECT COUNT(*) AS count FROM gateway_devices");
        if (Number(count.rows[0].count) >= 100000) throw new EnrollmentError(429, "ENROLLMENT_BUSY");
      }
      const token = randomBytes(32).toString("base64url");
      const expiry = this.now() + SESSION_TTL;
      await connection.query(`INSERT INTO gateway_devices(node_id, token_sha256, expires_at_ms) VALUES ($1,$2,$3)
        ON CONFLICT (node_id) DO UPDATE SET token_sha256=EXCLUDED.token_sha256, expires_at_ms=EXCLUDED.expires_at_ms`,
        [challenge.node_id, tokenHash(token), expiry]);
      return this.sign(Buffer.from(SESSION_DOMAIN + JSON.stringify({ node_id: challenge.node_id, token, expires_at_ms: expiry })));
    });
  }

  async authenticate(header: string | undefined): Promise<Principal | undefined> {
    if (!header?.startsWith("Bearer ")) return;
    const token = header.slice(7);
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) return;
    const { rows } = await this.database.query(`SELECT node_id FROM gateway_devices
      WHERE token_sha256=$1 AND expires_at_ms>$2 AND NOT revoked`, [tokenHash(token), this.now()]);
    if (rows.length) return { id: `device-${rows[0].node_id}`, role: "gateway", gateway_node_id: rows[0].node_id,
      token_sha256: tokenHash(token) };
  }
}

export function registerEnrollment(app: FastifyInstance, enrollment?: GatewayEnrollment): void {
  for (const action of ["challenge", "enroll"] as const) {
    app.post(`/v1/gateway/${action}`, { bodyLimit: 4096 }, async (request, reply) => {
      reply.header("Cache-Control", "no-store");
      if (!enrollment) return reply.code(503).send({ code: "ENROLLMENT_UNAVAILABLE", retryable: true });
      try { return await enrollment[action](request.body, request.ip); }
      catch (error) {
        const known = error instanceof EnrollmentError;
        return reply.code(known ? error.status : 503).send({ code: known ? error.code : "ENROLLMENT_UNAVAILABLE",
          retryable: !known || error.status === 429 });
      }
    });
  }
}
