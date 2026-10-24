import assert from "node:assert/strict";
import test from "node:test";
import { ConfigValidationError, loadConfig } from "./config.js";

const validEnvironment = {
  GHOST_ENV: "test",
  GHOST_HOST: "127.0.0.1",
  GHOST_PORT: "3010",
  GHOST_DATABASE_URL: "postgresql://localhost:5432/ghost_test",
  GHOST_MODEL_PROVIDER: "fake",
  GHOST_MODEL: "cp01-test",
} satisfies NodeJS.ProcessEnv;

test("loads a fake-provider config without a provider secret", () => {
  const config = loadConfig(validEnvironment);

  assert.equal(config.environment, "test");
  assert.equal(config.port, 3010);
  assert.equal(config.modelProvider, "fake");
  assert.equal(config.model, "cp01-test");
  assert.equal(config.openAiApiKey, undefined);
});

test("rejects missing required configuration with variable names", () => {
  assert.throws(
    () => loadConfig({ GHOST_MODEL_PROVIDER: "fake" }),
    (error: unknown) => {
      assert.ok(error instanceof ConfigValidationError);
      assert.match(error.message, /GHOST_DATABASE_URL is required/);
      assert.match(error.message, /GHOST_MODEL is required/);
      return true;
    },
  );
});

test("requires the OpenAI secret only for the OpenAI adapter", () => {
  assert.throws(
    () => loadConfig({ ...validEnvironment, GHOST_MODEL_PROVIDER: "openai" }),
    (error: unknown) => {
      assert.ok(error instanceof ConfigValidationError);
      assert.match(error.message, /GHOST_OPENAI_API_KEY is required/);
      return true;
    },
  );
});

test("rejects non-PostgreSQL database URLs", () => {
  assert.throws(
    () => loadConfig({ ...validEnvironment, GHOST_DATABASE_URL: "https://example.invalid/database" }),
    (error: unknown) => {
      assert.ok(error instanceof ConfigValidationError);
      assert.match(error.message, /GHOST_DATABASE_URL must use postgres/);
      return true;
    },
  );
});
