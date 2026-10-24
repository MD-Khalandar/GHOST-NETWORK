import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { FindHelp, ProposalCard, type AiProposal } from "./Coordination";

test("AI suggestions escape model text, show stale status and give run controls only to operators", () => {
  const proposal: AiProposal = { proposal_id: "proposal", request_id: "request", request_description: "<script>SOS</script>",
    resource_kind: "offer", resource_id: "offer", resource_label: null, resource_type: 1, unit: "litres", coarse_area: null,
    quantity: 2, explanation: "<img src=x onerror=alert(1)>", freshness: "stale", created_at_ms: 1 };
  const html = renderToStaticMarkup(<ProposalCard proposal={proposal}/>);
  assert.match(html, /Outdated/); assert.match(html, /2 litres/); assert.doesNotMatch(html, /<script>|<img /);
  assert.match(html, /Nothing is reserved/);
  assert.doesNotMatch(renderToStaticMarkup(<FindHelp requestId="request" token="fixture" operator={false} onAccessLost={() => {}}/>), /<button/);
  assert.match(renderToStaticMarkup(<FindHelp requestId="request" token="fixture" operator onAccessLost={() => {}}/>), /Find help with AI/);
});
