import assert from "node:assert/strict";
import test from "node:test";
import { ConfigValidationError, loadConfig } from "./config.js";

test("health-only local startup needs no database or model configuration", () => {
  assert.deepEqual(loadConfig({}), { environment: "development", host: "127.0.0.1", port: 3000, authPrincipals: [] });
});

test("database configuration requires PostgreSQL and a signing key without exposing credentials", () => {
  for (const value of ["sqlite://private-secret", "postgres://user:private-secret@localhost/ghost"]) {
    assert.throws(() => loadConfig({ GHOST_DATABASE_URL: value }), (error: unknown) => {
      assert.ok(error instanceof ConfigValidationError);
      assert.doesNotMatch(error.message, /private-secret/); return true;
    });
  }
});

test("invalid listen port and environment fail clearly", () => {
  for (const environment of [{ GHOST_PORT: "0" }, { GHOST_PORT: "65536" }, { GHOST_PORT: "NaN" }, { GHOST_ENV: "unknown" }]) {
    assert.throws(() => loadConfig(environment), ConfigValidationError);
  }
});
