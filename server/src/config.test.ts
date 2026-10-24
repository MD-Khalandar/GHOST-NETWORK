import assert from "node:assert/strict";
import test from "node:test";
import { ConfigValidationError, loadConfig } from "./config.js";

test("local startup needs no unused database or model configuration", () => {
  assert.deepEqual(loadConfig({}), { environment: "development", host: "127.0.0.1", port: 3000, authPrincipals: [] });
});

test("invalid listen port and environment fail clearly", () => {
  for (const environment of [{ GHOST_PORT: "0" }, { GHOST_PORT: "65536" }, { GHOST_PORT: "NaN" }, { GHOST_ENV: "unknown" }]) {
    assert.throws(() => loadConfig(environment), ConfigValidationError);
  }
});
