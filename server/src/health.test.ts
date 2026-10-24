import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "./app.js";

test("health is public and reports process availability only", async () => {
  const app = buildApp();
  try {
    const response = await app.inject({ method: "GET", url: "/v1/health" });
    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), { status: "ok" });
  } finally { await app.close(); }
});
