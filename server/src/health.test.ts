import assert from "node:assert/strict";
import test from "node:test";
import { buildApp } from "./app.js";
import { cp01FixtureInput } from "./fixtures.js";
import { FakeModelProvider } from "./model-provider.js";

test("GET /v1/health reports process/config health without secrets", async () => {
  const app = buildApp();

  try {
    const response = await app.inject({ method: "GET", url: "/v1/health" });

    assert.equal(response.statusCode, 200);
    assert.deepEqual(response.json(), {
      status: "ok",
      contract_version: "ghost.contracts.v1",
      database: "not_checked",
      model_provider: "configured",
    });
    assert.doesNotMatch(response.body, /postgresql|cp01-test|api[_-]?key/i);
  } finally {
    await app.close();
  }
});

test("fake provider returns the same bounded decision for the same fixture", async () => {
  const provider = new FakeModelProvider("cp01-test");

  assert.deepEqual(await provider.decide(cp01FixtureInput), await provider.decide(cp01FixtureInput));
  assert.deepEqual(await provider.decide(cp01FixtureInput), {
    schema_version: 1,
    action: "finish",
    arguments: {},
    reason: "CP01 fake provider; no coordination executed.",
  });
});
