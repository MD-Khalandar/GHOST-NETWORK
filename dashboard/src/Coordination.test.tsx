import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { FindHelp, ProposalCard, runLabel, type AiProposal } from "./Coordination";

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

test("expired suggestions cannot appear current between polls and completed checks distinguish empty outcomes", () => {
  const proposal: AiProposal = { proposal_id: "proposal", request_id: "request", request_description: "Need water",
    resource_kind: "offer", resource_id: "offer", resource_label: null, resource_type: 1, unit: "litres", coarse_area: "Station",
    request_area: "<script>area</script>", quantity: 2, explanation: "Possible supply", freshness: "current", created_at_ms: 1000,
    request_expires_at_ms: 4000, resource_expires_at_ms: 3000 };
  const current = renderToStaticMarkup(<ProposalCard proposal={proposal} now={2000}/>);
  assert.match(current, /pending human review/); assert.match(current, /&lt;script&gt;area/);
  const expired = renderToStaticMarkup(<ProposalCard proposal={proposal} now={3000}/>);
  assert.match(expired, /Outdated/); assert.doesNotMatch(expired, /pending human review/);
  const run = { run_id: "run", request_id: "request", status: "completed", error_code: null, created_at_ms: 1000 };
  assert.match(runLabel({ ...run, candidate_count: 0, proposal_count: 0 }), /No eligible/);
  assert.match(runLabel({ ...run, candidate_count: 3, proposal_count: 0 }), /No suitable matches/);
  assert.match(runLabel({ ...run, candidate_count: 3, proposal_count: 2 }), /2 suggestions for review/);
  assert.match(runLabel({ ...run, status: "error", error_code: "INVALID_MODEL_OUTPUT" }), /rejected/);
});
