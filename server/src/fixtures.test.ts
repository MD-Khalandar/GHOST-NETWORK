import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { cp01FixtureInput } from "./fixtures.js";

test("server fixture copy and provider input match A's shared contract", () => {
  const shared = JSON.parse(readFileSync(new URL("../../contracts/fixtures/cp01-fixtures.json", import.meta.url), "utf8"));
  const local = JSON.parse(readFileSync(new URL("../test/fixtures/cp01-fixtures.json", import.meta.url), "utf8"));
  assert.deepEqual(local, shared);
  assert.deepEqual(cp01FixtureInput, {
    requestId: shared.sos_create.request_id,
    offerId: shared.offer_publish.offer_id,
    category: shared.sos_create.category,
    coarseArea: shared.sos_create.coarse_area,
  });
});

test("CP01 fixture is deterministic and uses the frozen contract version", () => {
  const fixtureUrl = new URL("../test/fixtures/cp01-fixtures.json", import.meta.url);
  const fixture = JSON.parse(readFileSync(fixtureUrl, "utf8")) as {
    contract_version: string;
    sos_create: { request_id: string; category: string };
    offer_publish: { offer_id: string; quantity: number };
  };

  assert.equal(fixture.contract_version, "ghost.contracts.v1");
  assert.equal(fixture.sos_create.request_id, "11111111-1111-4111-8111-111111111111");
  assert.equal(fixture.sos_create.category, "WATER");
  assert.equal(fixture.offer_publish.offer_id, "22222222-2222-4222-8222-222222222222");
  assert.equal(fixture.offer_publish.quantity, 20);
});
