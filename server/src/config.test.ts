import assert from "node:assert/strict";
import test from "node:test";
import { ConfigValidationError, loadConfig } from "./config.js";
import { createProposalModel } from "./coordinator/model.js";
import { GeminiProposalModel } from "./coordinator/gemini-model.js";
import { OpenAIProposalModel } from "./coordinator/openai-model.js";

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

test("Gemini is explicitly selected with its own key; existing OpenAI configuration still works", () => {
  const gemini = createProposalModel(loadConfig({ GHOST_AI_PROVIDER: "gemini", GEMINI_API_KEY: "google-test-key", GHOST_COORDINATOR_MODEL: "gemini-test-model" }));
  assert.ok(gemini instanceof GeminiProposalModel); assert.equal(gemini.available, true); assert.equal(gemini.name, "gemini-test-model");
  const openai = createProposalModel(loadConfig({ OPENAI_API_KEY: "openai-test-key", GHOST_COORDINATOR_MODEL: "openai-test-model" }));
  assert.ok(openai instanceof OpenAIProposalModel); assert.equal(openai.available, true);
  assert.equal(createProposalModel(loadConfig({ GHOST_AI_PROVIDER: "gemini", OPENAI_API_KEY: "wrong-provider-key", GHOST_COORDINATOR_MODEL: "gemini-test-model" })).available, false);
  assert.equal(createProposalModel(loadConfig({ GHOST_AI_PROVIDER: "gemini", GEMINI_API_KEY: "google-test-key" })).available, false);
  assert.throws(() => loadConfig({ GHOST_AI_PROVIDER: "invalid-provider-secret" }), error => {
    assert.ok(error instanceof ConfigValidationError); assert.doesNotMatch(error.message, /invalid-provider-secret/); return true;
  });
});
