import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

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
