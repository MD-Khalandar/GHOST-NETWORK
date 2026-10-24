---
name: ghost-agent-safety
description: Implement or review GHOST cloud coordination, typed server tools, owner consent, human approval, idempotency and decline/replan.
---

# GHOST agent safety

Read `AGENTS.md`, `ARCHITECTURE.md`, `docs/AGENT_CONTRACT.md` and A/G gates in `docs/TEST_MATRIX.md`. Read the protocol for event/receipt changes. Respect the current team prompt's file ownership.

## Follow the authority path

Trace signed report → cloud validation/persistence → durable job → current snapshot → untrusted model decision → typed server tool → draft → authenticated operator approval → revalidation → cloud-signed outbox → gateway/mesh → recipient receipt.

- Coordination/model/tool execution belongs on the server. Phones and gateways cannot start independent coordinating agents, approve as an operator through a local toggle, or fabricate cloud results.
- Reject unknown actions, invalid types/bounds and IDs outside the snapshot. Report text and model output are untrusted; never execute generated shell, SQL, unrestricted URLs or function names.
- Enforce owner/cloud consent, request/offer freshness, quantities and proposal version in server policy. Unknown or unsynchronized facts remain uncertain.
- Read/draft tools cannot publish. Check authenticated operator role and exact version at approval; resource owners accept/decline and requesters close SOS.
- Commit allocation only on authorized owner acceptance in a transaction. Test competing accepts; a queued handset action is not cloud confirmation.
- Decline/withdrawal/closure invalidates stale work. Replanning creates a fresh trace/version and requires new approval.
- Deduplicate across gateway uploads, job triggers, approval retries and outgoing events. Verify exclusive worker ownership and durable restart behavior; do not promise exactly-once provider calls.
- Enforce bounded steps/retries/deadlines. Late output cannot revive an expired/closed proposal. Keep provider/signing secrets server-side and traces sanitized.

## Connectivity and helpers

No reachable gateway/cloud means coordination pending, not phone-local model fallback. The mesh must still store/relay reports. Separate cloud ingestion acknowledgement, proposal publication, phone receipt and resource confirmation.

Local LLM drafting/translation/summarization is deferred and has no allocation or approval authority. Do not integrate LiteRT-LM for the current SOS checkpoints merely because a dependency/model exists.

## Verify

Use fake model/cloud, clock and resource fixtures for injection, malformed output, stale/revoked consent/offers, unauthorized operator/owner actions, duplicate gateways, conflicting accepts, worker restart, timeout and late response. Test rejected actions leave consequential state/outbox unchanged.

Run applicable server tests and Android checks only for touched implementation. Separate fake tests from actual cloud model/tool evidence. Docs-only changes specify tests without building. Route shared contract changes through owner A with fixtures and integration notes.
