import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { buildApp } from "./app.js";
import { ConfigValidationError, loadConfig } from "./config.js";
import { parsePrincipals, tokenHash, type Principal } from "./security.js";

const fixture = JSON.parse(readFileSync(new URL("../../contracts/fixtures/security-vectors.json", import.meta.url), "utf8"));
const roles = ["gateway", "viewer", "operator"] as const;
const tokens = { gateway: "g".repeat(43), viewer: "v".repeat(43), operator: "o".repeat(43) };
const principals: Principal[] = roles.map(role => ({ id: role, role, token_sha256: tokenHash(tokens[role]), ...(role === "gateway" ? { gateway_node_id: "a".repeat(64) } : {}) }));

test("gateway/viewer/operator roles are isolated on every protected route", async () => {
  const app = buildApp({ principals });
  try {
    for (const role of roles) {
      const headers = { authorization: `Bearer ${tokens[role]}` };
      const session = await app.inject({ method: "GET", url: "/v1/auth/session", headers });
      assert.equal(session.statusCode, 200); assert.equal(session.json().role, role);
      assert.doesNotMatch(session.body, /token_sha256|private|BEGIN|gggg|vvvv|oooo/);
      for (const url of ["/v1/dashboard/sos", "/v1/dashboard/sos/any", "/v1/dashboard/helpers", "/v1/dashboard/offers", "/v1/dashboard/runs", "/v1/dashboard/proposals"]) {
        const allowedStatus = url === "/v1/dashboard/sos/any" ? 400 : 503;
        assert.equal((await app.inject({ method: "GET", url, headers })).statusCode, role === "gateway" ? 403 : allowedStatus);
        assert.equal((await app.inject({ method: "GET", url })).statusCode, 401);
      }
      for (const action of ["approve", "reject"]) {
        assert.equal((await app.inject({ method: "POST", url: `/v1/dashboard/proposals/any/${action}`, headers, payload: { role: "operator" } })).statusCode, role === "operator" ? 400 : 403);
      }
      assert.equal((await app.inject({ method: "GET", url: "/v1/sync/download", headers })).statusCode, role === "gateway" ? 503 : 403);
      assert.equal((await app.inject({ method: "POST", url: "/v1/sync/download-ack", headers, payload: {} })).statusCode, role === "gateway" ? 400 : 403);
    }
    assert.equal((await app.inject({ method: "GET", url: "/v1/auth/session", headers: { authorization: `Bearer ${"x".repeat(43)}` } })).statusCode, 401);
  } finally { await app.close(); }
});

test("upload verifies origin independently of gateway and reports unconfigured storage honestly", async () => {
  const app = buildApp({ principals, now: () => fixture.now_ms });
  const payload = { idempotency_key: "test", packets: [fixture.packets[0].packet] };
  const headers = { authorization: `Bearer ${tokens.gateway}` };
  try {
    // This is a relayed origin: the authenticated gateway is not required to be the origin signer.
    const response = await app.inject({ method: "POST", url: "/v1/sync/upload", headers, payload });
    assert.equal(response.statusCode, 503);
    const tampered = structuredClone(payload); tampered.packets[0].signature = "AA";
    assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", headers, payload: tampered })).statusCode, 400);
    assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", headers, payload: { ...payload, receipts: [{ recipient_node_id: fixture.device.node_id }] } })).statusCode, 400);
    assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", headers: { authorization: `Bearer ${tokens.operator}` }, payload })).statusCode, 403);
    assert.equal((await app.inject({ method: "POST", url: "/v1/sync/upload", payload })).statusCode, 401);
    const invalidJson = await app.inject({ method: "POST", url: "/v1/sync/upload", headers: { ...headers, "content-type": "application/json" }, payload: '{"privateSOS":"secret-location",' });
    assert.equal(invalidJson.statusCode, 400);
    assert.doesNotMatch(invalidJson.body, /privateSOS|secret-location/);
  } finally { await app.close(); }
});

test("malformed/duplicate role records and unsafe production config fail without revealing secrets", () => {
  assert.throws(() => parsePrincipals(JSON.stringify([principals[0], principals[0]])));
  assert.throws(() => parsePrincipals(JSON.stringify([{ ...principals[1], role: "admin" }])));
  assert.throws(() => parsePrincipals(JSON.stringify([{ ...principals[0], gateway_node_id: "fake" }])));
  assert.throws(() => loadConfig({ GHOST_ENV: "production", GHOST_CLOUD_SIGNING_KEY_PEM: "private-secret" }), (error: unknown) => {
    assert.ok(error instanceof ConfigValidationError); assert.doesNotMatch(error.message, /private-secret/); return true;
  });
});
