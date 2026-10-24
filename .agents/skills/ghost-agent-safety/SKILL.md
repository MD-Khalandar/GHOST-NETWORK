---
name: ghost-agent-safety
description: Implement or review GHOST local-model orchestration, Kotlin tools, structured decisions, authorization, human approval, and decline/replan. Use for AI behavior or tool-policy changes.
---

# GHOST agent safety

Read `AGENTS.md`, `docs/AGENT_CONTRACT.md`, `ARCHITECTURE.md`, and A01–A08 in `docs/TEST_MATRIX.md`. Inspect the actual model adapter, policy, tool registry, and human publish path. Read `docs/PROTOCOL.md` for any event/receipt changes.

## Trace authority through the whole action

Trace verified incoming event → Room snapshot → model text → typed decision → deterministic validation → allowlisted Kotlin tool → persisted observation/proposal → human confirmation → signed publication. A model response is neither a tool invocation nor authorization.

- Treat SOS, offer notes, quoted instructions, model explanations, and model JSON as untrusted data. Bound prompt size, output size, argument strings, result counts, run duration, and tool steps.
- Parse into the finite decision schema. Reject unknown actions/fields as required by the schema, invalid types/enums, out-of-bounds values, and entity IDs absent from the known snapshot. Never execute generated shell, SQL, arbitrary URLs, or function names.
- Enforce freshness, ownership/signatures, resource compatibility, remaining quantity where supplied, request status, and proposal idempotency in Kotlin. Unknown location/quantity remains uncertain; do not invent resources, routes, triage, or authority.
- Model tools may read or create local drafts only. Human actions authorize outward offers, request resolution, or resource commitment according to the contract's actor rules. Approval must be enforced in the use case/policy, not just a UI button.
- Revalidate the latest offer/request and proposal version at approval/publication. A decline or revocation invalidates the pending proposal; an earlier approval must not authorize a replacement offer automatically.
- Persist runs and observations, debounce duplicate triggers by the documented keys, and recover pending state after restart without duplicate sends. Use a single coordinator queue, maximum four model decisions per run, and at most one invalid-output repair retry within those bounds.
- Record actual tool arguments/results and provenance in sanitized traces. Separate model-selection, validation, execution, and publication outcomes so partial failures do not appear successful.

## Keep inference local and bounded

For engine integration, read the installed `litert-lm/SKILL.md`. Keep the runtime behind `LocalModelRuntime` in `ai/runtime`; its chat ViewModel example must not replace GHOST's repository/orchestrator boundaries. Preserve the existing version catalog rather than copying a sample dependency version.

Use pre-provisioned model files outside the APK, verify integrity, and own initialization, inference, cancellation, and close on the dedicated background owner. A coroutine timeout around a blocking native call alone does not prove native cancellation; test the adapter's actual lifecycle.

The installed LiteRT-LM skill covers text inference, not native tool calling. Implement GHOST's structured-text/Kotlin validation loop explicitly. Missing model, invalid output, runtime failure, timeout, and cancellation must produce truthful states. Label rules fallback separately; never silently switch to a cloud service or upload SOS/location.

## Verify the authority boundary

Use `FakeModelRuntime`, fake clock, and real resource fixtures to test valid proposals, malicious SOS instructions, malformed/oversized output, invented IDs, stale/revoked offers, missing approval, decline/replan, duplicate triggers, timeout, cancellation, and restart. Test that rejected actions leave outward transport and consequential state untouched.

Run the repository build/unit command. Report fake-model results separately from offline Phone C inference, which requires model hash/device identity and actual output→validated tool trace. Do not mark A01/A02/A05 complete using a fake, and do not alter shared tool/event contracts without an explicit proposal and tests.
