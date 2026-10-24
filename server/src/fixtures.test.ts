import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { cp01FixtureInput } from "./fixtures.js";

// Android and server tests consume the same file. Do not maintain a server copy.
const shared = JSON.parse(readFileSync(new URL("../../contracts/fixtures/cp01-fixtures.json", import.meta.url), "utf8"));

test("provider input matches A's shared contract", () => {
  assert.deepEqual(cp01FixtureInput, {
    requestId: shared.sos_create.request_id,
    offerId: shared.offer_publish.offer_id,
    category: shared.sos_create.category,
    coarseArea: shared.sos_create.coarse_area,
  });
});

test("CP01 fixture is deterministic and uses the frozen contract version", () => {
  assert.equal(shared.contract_version, "ghost.contracts.v1");
  assert.equal(shared.sos_create.request_id, "11111111-1111-4111-8111-111111111111");
  assert.equal(shared.sos_create.category, "WATER");
  assert.equal(shared.offer_publish.offer_id, "22222222-2222-4222-8222-222222222222");
  assert.equal(shared.offer_publish.quantity, 20);
  assert.deepEqual(shared.sync_upload.packets[0], shared.wire_packet);
});
