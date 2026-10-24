import { createHmac, randomBytes, randomUUID, scrypt, timingSafeEqual } from "node:crypto";
import type { FastifyReply, FastifyRequest } from "fastify";
import type { Database, SqlConnection } from "./database.js";
import { tokenHash, type Principal } from "./security.js";

const SESSION_MS = 7 * 86400000;
const IDLE_MS = 86400000;
const LOGIN_WINDOW_MS = 15 * 60000;
const HASH_PREFIX = "scrypt-65536-8-2";
const USERNAME = /^[a-z][a-z0-9_.-]{2,63}$/;
export interface DeskSession {
  principal: Principal;
  username: string;
  csrf_token: string;
  expires_at_ms: number;
}
export class DeskAuthError extends Error {
  constructor(readonly status: number, readonly code: string, readonly retryAfter?: number) { super(code); }
}

export function dashboardOrigin(value: string): string {
  const url = new URL(value);
  if (!["http:", "https:"].includes(url.protocol) || url.username || url.password || url.search || url.hash ||
      url.pathname !== "/" || url.port === "0") throw new Error("Invalid dashboard origin");
  return url.origin;
}
export function normalizeUsername(username: string): string {
  const normalized = username.trim().toLowerCase();
  if (!USERNAME.test(normalized)) throw new Error("Username must be 3-64 characters: letters, numbers, dots, underscores or hyphens, starting with a letter");
  return normalized;
}
function derive(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => scrypt(password, salt, 32,
    { N: 65536, r: 8, p: 2, maxmem: 128 * 1024 * 1024 }, (error, key) => error ? reject(error) : resolve(key)));
}
export async function hashPassword(password: string): Promise<string> {
  if (password.length < 15 || password.length > 256) throw new Error("Use a password between 15 and 256 characters");
  const salt = randomBytes(16), hash = await derive(password, salt);
  return `${HASH_PREFIX}$${salt.toString("hex")}$${hash.toString("hex")}`;
}
async function verifyPassword(password: string, encoded?: string): Promise<boolean> {
  const parts = encoded?.split("$");
  const valid = parts?.length === 3 && parts[0] === HASH_PREFIX && /^[a-f0-9]{32}$/.test(parts[1]) && /^[a-f0-9]{64}$/.test(parts[2]);
  // Unknown/disabled users still perform the same bounded password work.
  const derived = await derive(password, valid ? Buffer.from(parts[1], "hex") : Buffer.alloc(16));
  const expected = valid ? Buffer.from(parts[2], "hex") : Buffer.alloc(32);
  return timingSafeEqual(derived, expected) && !!valid;
}
export async function initializeDashboardAuth(c: SqlConnection): Promise<void> {
  await c.query(`CREATE TABLE IF NOT EXISTS dashboard_users (
    user_id UUID PRIMARY KEY, username TEXT UNIQUE NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('viewer','operator')), password_hash TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE, credential_version BIGINT NOT NULL DEFAULT 1)`);
  await c.query(`CREATE TABLE IF NOT EXISTS dashboard_sessions (
    token_sha256 TEXT PRIMARY KEY, user_id UUID NOT NULL REFERENCES dashboard_users(user_id) ON DELETE CASCADE,
    credential_version BIGINT NOT NULL, created_at_ms BIGINT NOT NULL,
    last_seen_at_ms BIGINT NOT NULL, expires_at_ms BIGINT NOT NULL)`);
  await c.query("CREATE INDEX IF NOT EXISTS dashboard_session_user ON dashboard_sessions(user_id,created_at_ms)");
  await c.query(`CREATE TABLE IF NOT EXISTS dashboard_login_windows (
    scope TEXT NOT NULL, window_start_ms BIGINT NOT NULL, attempts INTEGER NOT NULL,
    PRIMARY KEY(scope,window_start_ms))`);
}

/** Local account management: resetting a password or role revokes every prior session. */
export async function saveDashboardAccount(database: Database, username: string, role: "viewer" | "operator", password: string) {
  const name = normalizeUsername(username);
  if (!["viewer", "operator"].includes(role)) throw new Error("Choose viewer or operator");
  const hash = await hashPassword(password);
  await database.transaction(async c => {
    await c.query("SELECT pg_advisory_xact_lock(715422708)");
    const user = await c.query(`INSERT INTO dashboard_users(user_id,username,role,password_hash) VALUES($1,$2,$3,$4)
      ON CONFLICT(username) DO UPDATE SET role=EXCLUDED.role,password_hash=EXCLUDED.password_hash,
      enabled=TRUE,credential_version=dashboard_users.credential_version+1 RETURNING user_id`, [randomUUID(), name, role, hash]);
    await c.query("DELETE FROM dashboard_sessions WHERE user_id=$1", [user.rows[0].user_id]);
  });
}
export async function disableDashboardAccount(database: Database, username: string) {
  await database.transaction(async c => {
    const { rows } = await c.query("UPDATE dashboard_users SET enabled=FALSE,credential_version=credential_version+1 WHERE username=$1 RETURNING user_id", [normalizeUsername(username)]);
    if (!rows.length) throw new Error("Account not found");
    await c.query("DELETE FROM dashboard_sessions WHERE user_id=$1", [rows[0].user_id]);
  });
}

export class DashboardAuth {
  private activeVerifications = 0;
  constructor(private readonly database: Database, private readonly origin?: string, private readonly now = Date.now) {
    if (origin) dashboardOrigin(origin);
  }
  private secure(request: FastifyRequest) { return this.origin ? this.origin.startsWith("https:") : request.protocol === "https"; }
  private cookieName(request: FastifyRequest) { return this.secure(request) ? "__Host-ghost_desk" : "ghost_desk_dev"; }
  private cookieToken(request: FastifyRequest): string | undefined {
    const values = (request.headers.cookie ?? "").split(";").map(part => part.trim().split("="))
      .filter(parts => parts[0] === this.cookieName(request));
    return values.length === 1 && values[0].length === 2 && /^[A-Za-z0-9_-]{43}$/.test(values[0][1]) ? values[0][1] : undefined;
  }
  private setCookie(request: FastifyRequest, reply: FastifyReply, token: string, maxAge: number) {
    reply.header("Set-Cookie", `${this.cookieName(request)}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${maxAge}${this.secure(request) ? "; Secure" : ""}`);
  }
  private csrf(token: string) { return createHmac("sha256", token).update("GHOST-DESK-CSRF-V1").digest("base64url"); }
  checkOrigin(request: FastifyRequest) {
    const expected = this.origin ?? dashboardOrigin(`${request.protocol}://${request.headers.host}`);
    const site = request.headers["sec-fetch-site"];
    if (request.headers["x-ghost-desk"] !== "1" || site && site !== "same-origin" ||
      (request.headers.origin ? request.headers.origin !== expected : site !== "same-origin"))
      throw new DeskAuthError(403, "CSRF_REJECTED");
  }
  checkMutation(request: FastifyRequest, session: DeskSession) {
    this.checkOrigin(request);
    const csrf = request.headers["x-csrf-token"];
    if (typeof csrf !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(csrf) ||
      !timingSafeEqual(Buffer.from(csrf), Buffer.from(session.csrf_token))) throw new DeskAuthError(403, "CSRF_REJECTED");
  }
  private session(row: Record<string, any>, token: string): DeskSession {
    return { principal: { id: `desk-${row.user_id}`, role: row.role, token_sha256: tokenHash(token) },
      username: row.username, csrf_token: this.csrf(token), expires_at_ms: Number(row.expires_at_ms) };
  }
  async authenticate(request: FastifyRequest): Promise<DeskSession | undefined> {
    const token = this.cookieToken(request);
    if (!token) return;
    const { rows } = await this.database.query(`UPDATE dashboard_sessions s SET last_seen_at_ms=$2 FROM dashboard_users u
      WHERE s.token_sha256=$1 AND s.user_id=u.user_id AND u.enabled AND s.credential_version=u.credential_version
      AND s.expires_at_ms>$2 AND s.last_seen_at_ms>$3
      RETURNING u.user_id,u.username,u.role,s.expires_at_ms`, [tokenHash(token), this.now(), this.now() - IDLE_MS]);
    return rows.length ? this.session(rows[0], token) : undefined;
  }
  private async admit(username: string, ip: string) {
    const now = this.now(), window = Math.floor(now / LOGIN_WINDOW_MS) * LOGIN_WINDOW_MS;
    await this.database.transaction(async c => {
      await c.query("SELECT pg_advisory_xact_lock(715422707)");
      await c.query("DELETE FROM dashboard_login_windows WHERE window_start_ms<$1", [window]);
      for (const [scope, limit] of [[`ip:${tokenHash(ip)}`, 50], [`user:${tokenHash(username)}`, 20], ["global", 200]] as const) {
        const { rows } = await c.query(`INSERT INTO dashboard_login_windows(scope,window_start_ms,attempts) VALUES($1,$2,1)
          ON CONFLICT(scope,window_start_ms) DO UPDATE SET attempts=dashboard_login_windows.attempts+1
          WHERE dashboard_login_windows.attempts<$3 RETURNING attempts`, [scope, window, limit]);
        if (!rows.length) throw new DeskAuthError(429, "LOGIN_BUSY", Math.max(1, Math.ceil((window + LOGIN_WINDOW_MS - now) / 1000)));
      }
    });
  }
  async login(request: FastifyRequest, reply: FastifyReply): Promise<DeskSession> {
    this.checkOrigin(request);
    const body = request.body as { username?: unknown; password?: unknown } | null;
    if (!request.headers["content-type"]?.startsWith("application/json") || !body || Array.isArray(body) ||
      Object.keys(body).some(key => !["username", "password"].includes(key)) || typeof body.username !== "string" ||
      body.username.length > 64 || typeof body.password !== "string" || body.password.length < 1 || body.password.length > 256)
      throw new DeskAuthError(400, "INVALID_LOGIN");
    let username: string;
    try { username = normalizeUsername(body.username); } catch { throw new DeskAuthError(401, "INVALID_CREDENTIALS"); }
    await this.admit(username, request.ip);
    if (this.activeVerifications >= 2) throw new DeskAuthError(429, "LOGIN_BUSY", 2);
    this.activeVerifications++;
    try {
      const user = (await this.database.query("SELECT * FROM dashboard_users WHERE username=$1", [username])).rows[0];
      const valid = await verifyPassword(body.password, user?.enabled ? user.password_hash : undefined);
      if (!user || !valid || !user.enabled) throw new DeskAuthError(401, "INVALID_CREDENTIALS");
      const token = randomBytes(32).toString("base64url"), now = this.now(), expiry = now + SESSION_MS;
      const session = await this.database.transaction(async c => {
        const current = (await c.query("SELECT * FROM dashboard_users WHERE user_id=$1 FOR UPDATE", [user.user_id])).rows[0];
        if (!current?.enabled || current.password_hash !== user.password_hash || current.credential_version !== user.credential_version)
          throw new DeskAuthError(401, "INVALID_CREDENTIALS");
        await c.query("DELETE FROM dashboard_sessions WHERE expires_at_ms<=$1 OR last_seen_at_ms<=$2", [now, now - IDLE_MS]);
        const previous = this.cookieToken(request);
        if (previous) await c.query("DELETE FROM dashboard_sessions WHERE token_sha256=$1", [tokenHash(previous)]);
        await c.query(`DELETE FROM dashboard_sessions WHERE token_sha256 IN
          (SELECT token_sha256 FROM dashboard_sessions WHERE user_id=$1 ORDER BY created_at_ms DESC LIMIT ALL OFFSET 9)`, [user.user_id]);
        await c.query(`INSERT INTO dashboard_sessions(token_sha256,user_id,credential_version,created_at_ms,last_seen_at_ms,expires_at_ms)
          VALUES($1,$2,$3,$4,$4,$5)`, [tokenHash(token), user.user_id, user.credential_version, now, expiry]);
        return this.session({ ...current, expires_at_ms: expiry }, token);
      });
      this.setCookie(request, reply, token, SESSION_MS / 1000);
      return session;
    } finally { this.activeVerifications--; }
  }
  async logout(request: FastifyRequest, reply: FastifyReply) {
    const session = await this.authenticate(request);
    if (session) this.checkMutation(request, session); else this.checkOrigin(request);
    const token = this.cookieToken(request);
    if (token) await this.database.query("DELETE FROM dashboard_sessions WHERE token_sha256=$1", [tokenHash(token)]);
    this.setCookie(request, reply, "", 0);
  }
}

export function deskSessionResponse(session: DeskSession) {
  return { id: session.principal.id, username: session.username, role: session.principal.role,
    csrf_token: session.csrf_token, expires_at_ms: session.expires_at_ms };
}
