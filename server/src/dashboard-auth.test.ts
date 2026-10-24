import assert from "node:assert/strict";
import test from "node:test";
import { generateKeyPairSync, randomUUID } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PGlite } from "@electric-sql/pglite";
import { buildApp } from "./app.js";
import type { Database } from "./database.js";
import { disableDashboardAccount, hashPassword, saveDashboardAccount } from "./dashboard-auth.js";
import { tokenHash } from "./security.js";

const password = "water shelter transport 2026";
const pem = generateKeyPairSync("ec", { namedCurve: "prime256v1" }).privateKey.export({ format: "pem", type: "pkcs8" }).toString();
const origin = "https://ghost.example";
const deskHeaders = { origin, "x-ghost-desk": "1" };
const adapter = (pg: PGlite): Database => ({ query: (sql, values) => pg.query(sql, values),
  transaction: work => pg.transaction(c => work(c)), close: () => pg.close() });
const cookie = (response: { headers: Record<string, unknown> }) => String(response.headers["set-cookie"]).split(";")[0];
const token = (response: { headers: Record<string, unknown> }) => cookie(response).split("=")[1];
async function fixture(path?: string) {
  const pg = new PGlite(path), database = adapter(pg); let time = 1792860000000;
  const bearer = "g".repeat(43);
  const app = buildApp({ database, cloudSigningKeyPem: pem, dashboardOrigin: origin, now: () => time,
    principals: [{ id: "gateway", role: "gateway", token_sha256: tokenHash(bearer), gateway_node_id: "a".repeat(64) }] });
  await app.ready();
  const login = (username = "operator", supplied = password, headers: Record<string, string> = {}) => app.inject({ method: "POST", url: "/v1/auth/login",
    headers: { ...deskHeaders, ...headers }, payload: { username, password: supplied } });
  return { app, pg, database, login, bearer, now: () => time, advance: (ms: number) => { time += ms; } };
}

test("password login issues a persistent protected cookie; restore works without exposing a bearer credential", async () => {
  const f = await fixture();
  try {
    await saveDashboardAccount(f.database, "Operator", "operator", password);
    const signedIn = await f.login(" OPERATOR ");
    assert.equal(signedIn.statusCode, 200, signedIn.body);
    assert.equal(signedIn.json().username, "operator"); assert.equal(signedIn.json().role, "operator");
    assert.match(String(signedIn.headers["set-cookie"]), /^__Host-ghost_desk=/);
    for (const flag of ["HttpOnly", "Secure", "SameSite=Strict", "Path=/", "Max-Age=604800"])
      assert.ok(String(signedIn.headers["set-cookie"]).includes(flag));
    assert.equal(signedIn.headers["cache-control"], "no-store");
    assert.doesNotMatch(signedIn.body, /password_hash|token_sha256|water shelter/);
    const headers = { cookie: cookie(signedIn) };
    const restored = await f.app.inject({ url: "/v1/auth/session", headers });
    assert.equal(restored.statusCode, 200); assert.deepEqual(restored.json(), signedIn.json());
    const sos = await f.app.inject({ url: "/v1/dashboard/sos", headers });
    assert.equal(sos.statusCode, 200);
    const saved = JSON.stringify((await f.pg.query("SELECT * FROM dashboard_users")).rows);
    assert.match(saved, /scrypt-65536-8-2/); assert.ok(!saved.includes(password));
    const sessions = JSON.stringify((await f.pg.query("SELECT * FROM dashboard_sessions")).rows);
    assert.ok(!sessions.includes(token(signedIn))); assert.ok(sessions.includes(tokenHash(token(signedIn))));
    const phoneSession = await f.app.inject({ url: "/v1/auth/session", headers: { authorization: `Bearer ${f.bearer}` } });
    assert.equal(phoneSession.statusCode, 200); assert.equal(phoneSession.json().role, "gateway");
    assert.equal((await f.app.inject({ url: "/v1/sync/download", headers })).statusCode, 401);
    assert.equal((await f.app.inject({ url: "/v1/dashboard/sos", headers: { authorization: `Bearer ${f.bearer}` } })).statusCode, 403);
  } finally { await f.app.close(); }
});

test("login and mutations reject forged origins and CSRF; viewer privileges cannot approve", async () => {
  const f = await fixture();
  try {
    await saveDashboardAccount(f.database, "operator", "operator", password);
    await saveDashboardAccount(f.database, "viewer", "viewer", password);
    assert.equal((await f.login("operator", password, { origin: "https://attacker.example" })).statusCode, 403);
    assert.equal((await f.login("operator", password, { "x-ghost-desk": "" })).statusCode, 403);
    assert.equal((await f.login("operator", password, { "sec-fetch-site": "cross-site" })).statusCode, 403);
    const session = await f.login(); const csrf = session.json().csrf_token;
    const url = `/v1/dashboard/proposals/${randomUUID()}/approve`;
    const attempt = (extra: Record<string, string>) => f.app.inject({ method: "POST", url,
      headers: { ...deskHeaders, cookie: cookie(session), ...extra } });
    assert.equal((await attempt({})).statusCode, 403);
    assert.equal((await attempt({ "x-csrf-token": "x".repeat(43) })).statusCode, 403);
    assert.equal((await attempt({ "x-csrf-token": csrf, origin: "https://attacker.example" })).statusCode, 403);
    const valid = await attempt({ "x-csrf-token": csrf });
    assert.equal(valid.statusCode, 409); assert.equal(valid.json().code, "PROPOSAL_NOT_FOUND");
    const viewer = await f.login("viewer");
    assert.equal((await f.app.inject({ method: "POST", url, headers: { ...deskHeaders,
      cookie: cookie(viewer), "x-csrf-token": viewer.json().csrf_token } })).statusCode, 403);
    assert.equal((await f.app.inject({ url: "/v1/dashboard/sos", headers: { cookie: cookie(viewer) } })).statusCode, 200);
    assert.equal((await f.app.inject({ method: "POST", url: "/v1/auth/logout", headers: { cookie: cookie(session) } })).statusCode, 403);
    const logout = await f.app.inject({ method: "POST", url: "/v1/auth/logout",
      headers: { ...deskHeaders, cookie: cookie(session), "x-csrf-token": csrf } });
    assert.equal(logout.statusCode, 204); assert.match(String(logout.headers["set-cookie"]), /Max-Age=0/);
    assert.equal((await f.app.inject({ url: "/v1/auth/session", headers: { cookie: cookie(session) } })).statusCode, 401);
    assert.equal((await f.app.inject({ url: "/v1/auth/session", headers: { cookie: `${cookie(viewer)}; ${cookie(viewer)}` } })).statusCode, 401);
  } finally { await f.app.close(); }
});

test("password/role reset, disable, idle timeout and absolute timeout revoke remembered sessions", async () => {
  const f = await fixture();
  const restore = (signedIn: Awaited<ReturnType<typeof f.login>>) => f.app.inject({ url: "/v1/auth/session", headers: { cookie: cookie(signedIn) } });
  try {
    await saveDashboardAccount(f.database, "operator", "operator", password);
    let session = await f.login();
    await saveDashboardAccount(f.database, "operator", "viewer", "a different password for viewer");
    assert.equal((await restore(session)).statusCode, 401);
    assert.equal((await f.login()).statusCode, 401);
    session = await f.login("operator", "a different password for viewer"); assert.equal(session.json().role, "viewer");
    await disableDashboardAccount(f.database, "operator");
    assert.equal((await restore(session)).statusCode, 401);
    assert.equal((await f.login("operator", "a different password for viewer")).statusCode, 401);
    await saveDashboardAccount(f.database, "operator", "operator", password);
    session = await f.login(); f.advance(86400001);
    assert.equal((await restore(session)).statusCode, 401);
    session = await f.login();
    for (let day = 0; day < 7; day++) { f.advance(23 * 3600000); assert.equal((await restore(session)).statusCode, 200); }
    f.advance(8 * 3600000); assert.equal((await restore(session)).statusCode, 401);
    const missing = await f.login("missing"); const wrong = await f.login("operator", "wrong password");
    assert.equal(missing.statusCode, 401); assert.deepEqual(missing.json(), wrong.json());
    await assert.rejects(hashPassword("short"));
    assert.notEqual(await hashPassword(password), await hashPassword(password));
  } finally { await f.app.close(); }
});

test("login throttling persists across handler replacement and does not leak submitted credentials", async () => {
  const f = await fixture();
  try {
    await saveDashboardAccount(f.database, "operator", "operator", password);
    const window = Math.floor(f.now() / 900000) * 900000;
    await f.pg.query("INSERT INTO dashboard_login_windows VALUES($1,$2,20)", [`user:${tokenHash("operator")}`, window]);
    const throttled = await f.login(); assert.equal(throttled.statusCode, 429); assert.ok(Number(throttled.headers["retry-after"]) > 0);
    assert.ok(!throttled.body.includes(password));
    f.advance(900001); assert.equal((await f.login()).statusCode, 200);
    const invalid = await f.app.inject({ method: "POST", url: "/v1/auth/login", headers: deskHeaders,
      payload: { username: "operator", password, role: "operator" } });
    assert.equal(invalid.statusCode, 400); assert.ok(!invalid.body.includes(password));
    const oversized = await f.app.inject({ method: "POST", url: "/v1/auth/login", headers: deskHeaders,
      payload: { username: "operator", password: "x".repeat(5000) } });
    assert.equal(oversized.statusCode, 413);
  } finally { await f.app.close(); }
});

test("remembered sessions survive a database/server restart; HTTPS cookies do not trust forwarded protocol headers", async () => {
  const path = await mkdtemp(join(tmpdir(), "ghost-desk-auth-")); const f = await fixture(path);
  let closed = false, restored: ReturnType<typeof buildApp> | undefined;
  try {
    await saveDashboardAccount(f.database, "operator", "operator", password);
    const session = await f.login(); await f.app.close(); closed = true;
    const pg = new PGlite(path);
    restored = buildApp({ database: adapter(pg), cloudSigningKeyPem: pem, dashboardOrigin: origin, now: f.now });
    const response = await restored.inject({ url: "/v1/auth/session", headers: { cookie: cookie(session) } });
    assert.equal(response.statusCode, 200); assert.equal(response.json().csrf_token, session.json().csrf_token);
    await pg.query("UPDATE dashboard_login_windows SET attempts=20 WHERE scope=$1", [`user:${tokenHash("operator")}`]);
    const busy = await restored.inject({ method: "POST", url: "/v1/auth/login", headers: deskHeaders, payload: { username: "operator", password } });
    assert.equal(busy.statusCode, 429);
    const localDatabase = adapter(new PGlite());
    const local = buildApp({ database: localDatabase, cloudSigningKeyPem: pem });
    try {
      await local.ready();
      // No public-origin setting: development uses the actual local transport, ignoring proxy headers.
      const unavailable = await local.inject({ method: "POST", url: "/v1/auth/login", headers: {
        origin: "https://ghost.example", "x-ghost-desk": "1", "x-forwarded-proto": "https" }, payload: { username: "operator", password } });
      assert.equal(unavailable.statusCode, 403);
      await saveDashboardAccount(localDatabase, "operator", "operator", password);
      const localSession = await local.inject({ method: "POST", url: "/v1/auth/login",
        headers: { origin: "http://localhost", "x-ghost-desk": "1" }, payload: { username: "operator", password } });
      assert.equal(localSession.statusCode, 200, localSession.body);
      assert.match(String(localSession.headers["set-cookie"]), /^ghost_desk_dev=/);
      assert.doesNotMatch(String(localSession.headers["set-cookie"]), /; Secure/);
      assert.equal((await local.inject({ url: "/v1/auth/session", headers: { cookie: cookie(localSession) } })).statusCode, 200);
    } finally { await local.close(); }
  } finally {
    if (restored) await restored.close(); if (!closed) await f.app.close();
    assert.ok(path.startsWith(join(tmpdir(), "ghost-desk-auth-")));
    await rm(path, { recursive: true, force: true });
  }
});
