import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

test("startup fails clearly when required configuration is absent", () => {
  const serverDirectory = fileURLToPath(new URL("..", import.meta.url));
  const result = spawnSync(process.execPath, ["--import", "tsx", "src/index.ts"], {
    cwd: serverDirectory,
    encoding: "utf8",
    env: {
      ...process.env,
      GHOST_ENV: "test",
      GHOST_MODEL_PROVIDER: "fake",
      GHOST_DATABASE_URL: "",
      GHOST_MODEL: "",
      GHOST_OPENAI_API_KEY: "",
    },
  });

  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Configuration error/);
  assert.match(result.stderr, /GHOST_DATABASE_URL is required/);
  assert.match(result.stderr, /GHOST_MODEL is required/);
});
