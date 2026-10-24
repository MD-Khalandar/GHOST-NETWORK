import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { ResourceCard, type ResourceRecord } from "./Resources";

test("helper and supplier cards escape owner text and distinguish expiry from confirmed help", () => {
  const helper: ResourceRecord = { owner_node_id: "a".repeat(64), display_name: "<script>helper</script>", coarse_area: "<img src=x>",
    help_types: [1, 6], availability: 1, expires_at_ms: 4000, received_at_ms: 2000, created_at_ms: 1000, cloud_revision: 1 };
  const html = renderToStaticMarkup(<ResourceCard item={helper} kind="helpers" now={3000}/>);
  assert.doesNotMatch(html, /<script>|<img /); assert.match(html, /First aid · Water/);
  assert.match(html, /Reported available/); assert.match(html, /Physical delivery still needs confirmation/);
  const supply = renderToStaticMarkup(<ResourceCard item={{ ...helper, offer_id: "offer", resource_type: 1, quantity: 20, unit: "litres", available_until_ms: 2500 }} kind="offers" now={3000}/>);
  assert.match(supply, /Expired/); assert.match(supply, /20 litres/);
  const withdrawn = renderToStaticMarkup(<ResourceCard item={{ ...helper, resource_type: 1, quantity: 8, state: 2 }} kind="offers" now={3000}/>);
  assert.match(withdrawn, /Withdrawn/); assert.doesNotMatch(withdrawn, /Reported available/);
});
