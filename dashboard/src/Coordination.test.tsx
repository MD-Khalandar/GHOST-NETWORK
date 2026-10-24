import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";
import { FindHelp, ProposalCard, FollowUpPanel,ReviewModeControl, runLabel, type AiProposal } from "./Coordination";

test("follow-up distinguishes committed help from received help, escapes records and respects the SOS filter",()=>{
  const items=[{request_id:"request",specialist:"helper" as const,status:"committed",description:"<script>request</script>",detail:"Help committed; waiting for requester receipt confirmation",received_count:0,updated_at_ms:1},
    {request_id:"request",specialist:"supply" as const,status:"no_candidates",description:"Water",detail:"No untried available resources",received_count:1,updated_at_ms:1}];
  const html=renderToStaticMarkup(<FollowUpPanel items={items}/>);
  assert.match(html,/People and transport/);assert.match(html,/Supplies/);assert.match(html,/waiting for requester receipt/);
  assert.match(html,/1 receipt confirmed by requester/);assert.match(html,/further needs are not assumed fulfilled/);
  assert.doesNotMatch(html,/<script>/);
  assert.doesNotMatch(renderToStaticMarkup(<FollowUpPanel items={items} requestId="different"/>),/Water|&lt;script/);
});

test("operators can select persisted manual review; automatic invitations never imply helper acceptance",()=>{
  const automatic=renderToStaticMarkup(<ReviewModeControl mode={{manual_review:false,updated_at_ms:0}} operator busy={false} onChange={()=>{}}/>);
  assert.match(automatic,/Automatic invitations/);assert.match(automatic,/role="switch"/);
  assert.doesNotMatch(automatic,/checked=""/);assert.match(automatic,/Helpers still choose/);
  const manual=renderToStaticMarkup(<ReviewModeControl mode={{manual_review:true,updated_at_ms:1}} operator busy changing onChange={()=>{}}/>);
  assert.match(manual,/checked=""/);assert.match(manual,/disabled/);assert.match(manual,/Saving review mode/);
  const viewer=renderToStaticMarkup(<ReviewModeControl mode={{manual_review:false,updated_at_ms:0}} operator={false} busy={false} onChange={()=>{}}/>);
  assert.doesNotMatch(viewer,/role="switch"/);assert.match(viewer,/Automatic invitations/);
  const p:AiProposal={proposal_id:"p",request_id:"r",request_description:"Need water",resource_kind:"offer",resource_id:"o",resource_label:null,resource_type:1,unit:"litres",coarse_area:null,quantity:7,explanation:"Possible water",freshness:"current",created_at_ms:1};
  const card=renderToStaticMarkup(<ProposalCard proposal={p} operator manualReview={false} onReview={()=>{}}/>);
  assert.doesNotMatch(card,/pending human review|Approve invitation/);assert.match(card,/automatic checks pending/);assert.match(card,/Reject suggestion/);
});

test("AI suggestions escape model text, show stale status and give run controls only to operators", () => {
  const proposal: AiProposal = { proposal_id: "proposal", request_id: "request", request_description: "<script>SOS</script>",
    resource_kind: "offer", resource_id: "offer", resource_label: null, resource_type: 1, unit: "litres", coarse_area: null,
    quantity: 2, explanation: "<img src=x onerror=alert(1)>", freshness: "stale", created_at_ms: 1 };
  const html = renderToStaticMarkup(<ProposalCard proposal={proposal}/>);
  assert.match(html, /Outdated/); assert.match(html, /2 litres/); assert.doesNotMatch(html, /<script>|<img /);
  assert.match(html, /Stock is committed only after owner acceptance/);
  assert.doesNotMatch(renderToStaticMarkup(<FindHelp requestId="request" csrfToken="fixture" operator={false} onAccessLost={() => {}}/>), /<button/);
  assert.match(renderToStaticMarkup(<FindHelp requestId="request" csrfToken="fixture" operator onAccessLost={() => {}}/>), /Find help with AI/);
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

test("only operators can approve current proposals; unknown supply quantities cannot be approved",()=>{
  const proposal:AiProposal={proposal_id:"proposal",request_id:"request",request_description:"Need water",resource_kind:"offer",resource_id:"offer",
    resource_label:null,resource_type:1,unit:"litres",coarse_area:"Station",quantity:7,explanation:"Possible supply",freshness:"current",created_at_ms:1000};
  const viewer=renderToStaticMarkup(<ProposalCard proposal={proposal} onReview={()=>{}}/>);
  assert.doesNotMatch(viewer,/Approve invitation|Reject suggestion/);
  const operator=renderToStaticMarkup(<ProposalCard proposal={proposal} operator onReview={()=>{}}/>);
  assert.match(operator,/Approve invitation/);assert.match(operator,/Reject suggestion/);
  const unknown=renderToStaticMarkup(<ProposalCard proposal={{...proposal,quantity:null}} operator onReview={()=>{}}/>);
  assert.match(unknown,/<button[^>]*disabled=""[^>]*>Approve invitation/);assert.match(unknown,/known quantity/);
  assert.match(runLabel({run_id:"run",request_id:"request",status:"error",error_code:"MODEL_BUDGET_EXHAUSTED",created_at_ms:1}),/remains queued/);
});
