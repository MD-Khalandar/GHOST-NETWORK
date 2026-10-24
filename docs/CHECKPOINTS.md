# GHOST implementation checkpoints

These 15 checkpoints divide the remaining core proof of capability into sequential, reviewable changes. The existing Gradle/toolchain setup, dependency checks, and minimal launcher are the baseline. Their recorded verification is in [ENVIRONMENT_SETUP.md](ENVIRONMENT_SETUP.md); it does not establish working mesh networking or local inference.

**Current position:** planning handoff; checkpoint 1 has not started. No implementation checkpoint below is marked complete.

## Commit and push between checkpoints

1. Implement only the current checkpoint, using the relevant repository skills and architecture, protocol, agent, and UX contracts.
2. Run its relevant checks. For application code, run `./gradlew assembleDebug testDebugUnitTest` with the configured environment when tooling is available. Record hardware checks separately from host tests and simulations.
3. Update the checkpoint's status and evidence references in this document. Report changed files, actual validation results, limitations, and any missing acceptance evidence.
4. **Stop. The user stages, makes their own commit, and pushes it. Codex does not commit or push.**
5. Resume only after the user confirms the commit was pushed, or asks to continue and a fresh read-only upstream check confirms the checkpoint commit was pushed. Repeat after every checkpoint, including the last.

If required hardware or another prerequisite is unavailable, report the checkpoint as blocked or partially implemented rather than passed. A pushed implementation does not satisfy an unobserved acceptance gate. Pause at the handoff and let the user explicitly choose any scope or ordering change.

This planning change also ends with a handoff before checkpoint 1. The numbered checkpoints cover implementation, not this planning commit. Optional LoRa, internet gateway, and extra transport work remain outside these 15 steps.

## The 15 checkpoints

| # | Checkpoint | Deliverable | Exit checks and evidence |
| --- | --- | --- | --- |
| 1 | App foundation and deterministic fakes | Establish the package boundaries, simple dependency factory, core interfaces and required typed IDs from `ARCHITECTURE.md`; add `FakeTransport`, `FakeModelRuntime`, fake clock, resource fixtures, theme, four-destination navigation, and previews. Clearly identify simulated state. | Build and host tests pass; navigation/previews can be inspected; fake behavior is deterministic. No real transport or inference claim. |
| 2 | Early phone and model feasibility | Add an isolated Nearby permission/pairing diagnostic and the real LiteRT-LM adapter behind the runtime interface. Load a hash-verified, pre-provisioned model on a dedicated background owner; expose missing/loading/ready/failed states and produce a minimally validated structured decision. | Install/start on A, B, and C; record Nearby permission/pairing observations and actual offline model inference on C, including handset, model hash, backend, latency, and validated output (A01). Diagnostic traffic is not a delivered SOS or a production tool execution. If incompatible, resolve hardware/model choice or agree an honestly labelled reduced scope before proceeding. |
| 3 | Signed events and protocol validation | Implement the documented typed domain events, stable identity separate from Nearby handles, maintained signing/Keystore strategy, protobuf generation and fixture vectors, immutable signed envelope, and separate relay metadata. Show any necessary contract adjustment explicitly with tests. | Round-trip fixtures; signatures survive relay metadata changes; malformed, oversized, expired, invalid-version/signature/hop packets are rejected (T08). Record actual handset signing/key-storage compatibility. |
| 4 | Room source of truth | Implement entities, DAOs, repositories, transactions, authorized event projections, durable outbox, dedup storage, and `Flow` state. Prepare persistence for receipts, proposals, and agent traces as documented. | Repository/Room tests prove save-before-send, owner-only updates, rollback, projection correctness, and pending-state recovery after database reopen (T07 and persistence prerequisites for T03/T05). Host coverage is separate from force-process-restart evidence. |
| 5 | SOS creation and request details | Deliver the local vertical slice: validated form, optional coarse location, explicit public-sharing consent, content preview, signed event, transactional persistence, queued packet, Home summary, and request details from repository state. | Tests verify consent, validation, duplicate-submit protection, form restoration, and a persisted queued SOS with no peer. Check location disclosure (U01); do not claim delivery. |
| 6 | Resource offers and requests board | Add owner-signed offer creation/update/withdrawal, repository-backed board filters, request/offer freshness and provenance, and empty/loading/error/stale states. | Tests cover ownership, revoked/expired offers, unavailable quantities, filtering, and restart consistency. Users cannot edit another owner's resources or treat a draft match as a reservation. |
| 7 | Real direct Nearby delivery | Connect `NearbyAdapter` through `GhostTransport` to the validated receive/persist path. Implement authenticated pairing, runtime permissions, disconnection handling, and real signed A-to-B SOS exchange. | Two physical phones exchange matching signed event IDs with internet off and local radios on; save logs/screenshots (T01). Permission denial and retry are visible (T10). Nearby send success remains a neighbor-level state. |
| 8 | Real store-and-forward relay | Implement `MeshEngine` forwarding with preserved origin bytes, hop/TTL enforcement, bounded queues, deduplication, and previous-hop exclusion. Wire B to carry A's SOS to C. | Deterministic fake tests cover loops, duplicate injection, and forwarding bounds (T03). On three physical phones, prove A/C cannot connect directly and capture B relay logs plus C's matching event ID (T02). |
| 9 | Reconnect, retry, and restart recovery | Add bounded inventory exchange, missing-packet synchronization, backoff, queue fairness/caps, expiry cleanup, and persistent dedup/retry recovery. | Test disconnect/reconnect and inventory caps; capture physical queue-to-send transitions, force-process-restart recovery on B, and missing-ID-only synchronization (T04, T05, T09). Expired packets must stay expired after restart. |
| 10 | Signed receipts and Network diagnostics | Implement end-recipient receipt generation/validation, repository delivery projections, distinct queued/neighbor/relay/recipient/read states, and Network peer/queue/relay views with redacted observed provenance. | Tests show that Nearby success or a relay acknowledgement cannot mark delivery; capture an actual signed recipient receipt (T06). Inspect truthful diagnostics and text/icon distinctions (T11, U03); no inferred global topology. |
| 11 | Validated agent decisions and safe tools | Implement finite `AgentDecision` parsing, argument bounds, tool allowlist, authorization, fresh-snapshot matching, typed results with provenance, and read/draft-only tools. Separate rule-based assistance from model execution. | Fake-model/adversarial tests reject unknown actions, malicious IDs, injection, stale/revoked offers, arbitrary execution, and consequential actions without human authority (A03, A06, A07; U05). |
| 12 | Bounded local agent and Mission Control | Connect the proven runtime to a persisted single-owner orchestrator with triggers, idempotency, maximum four decisions, bounded retry, timeout/cancellation, and restart-safe drafts. Show actual observations, selected tools, results, provenance, and waiting-for-human state in Mission Control. | Fake-runtime tests cover state transitions, duplicate triggers, malformed output, no model, crashes, and timeout (A08). On C offline, capture actual model-selected `find_offers`, Kotlin execution, and a draft that waits for approval (A02, A03, U04). |
| 13 | Human approval, decline, and replan | Add approval/rejection, revalidation at send time, signed outgoing coordination, owner acceptance/decline, proposal versioning, and decline-triggered reruns using current offers. Preserve B's relay role after B declines. | Tests prove no outbound offer before approval, correct signer authority, stale-approval rejection, and idempotent replanning. Capture B's signed decline, C's new actual local-model run choosing a different valid offer, human confirmation, and A's receipt (A04, A05). |
| 14 | Failure handling and accessible integrated UX | Review the complete flow for no peer, permission denial, model failure, expired packets, missing receipts, disconnects, lifecycle/background limits, and restart recovery. Fix small-screen layouts, 200% text, screen reader focus/labels, tap targets, dark mode, system bars, and keyboard overlap. | Run relevant regression checks and physical UX observations (T10-T12, A08, U01-U06). Document kill/relaunch behavior and remaining limitations; never imply unstoppable background relaying. |
| 15 | Offline demo evidence and release handoff | Freeze core scope; execute the five-minute script with genuine A/B/C roles, internet disabled, a real local model, true relay, decline/replan, human approval, and signed recipient receipt. Package APK, evidence index, screenshots/video backup, limitations, and updated README/runbook. | Complete three successful captured runs (T13); report pass/fail/blocked with evidence for every T01-T13, A01-A08, and U01-U06 gate in `TEST_MATRIX.md`. Record observed metrics, not estimates. Stop for the user's final commit and push. |

## Progress and evidence

All checkpoints are **not started**. Replace the corresponding line with `in progress`, `implemented; evidence pending`, `blocked`, or `complete`, and link actual evidence at each handoff. Record the user's pushed commit when confirmed at the next resumption; do not guess a hash before the user commits.

- 01 — Not started.
- 02 — Not started.
- 03 — Not started.
- 04 — Not started.
- 05 — Not started.
- 06 — Not started.
- 07 — Not started.
- 08 — Not started.
- 09 — Not started.
- 10 — Not started.
- 11 — Not started.
- 12 — Not started.
- 13 — Not started.
- 14 — Not started.
- 15 — Not started.

## Contract and acceptance references

- [Architecture](../ARCHITECTURE.md): package boundaries, interfaces, device roles, and feasibility constraints.
- [Protocol](PROTOCOL.md): signed packets, events, routing, and receipt semantics.
- [Agent contract](AGENT_CONTRACT.md): validated local decisions, authorization, tool policy, and decline/replan.
- [UX specification](UX_SPEC.md): consent, honest states, accessibility, and screen behavior.
- [Acceptance matrix](TEST_MATRIX.md): required evidence; this roadmap does not change or complete those gates.
- [Original sprint phases](BUILD_PHASES.md): ownership and timing context; the user's sequential checkpoint workflow governs execution.

This roadmap does not change packet schemas, event types, stable ID semantics, or cross-owner interfaces. Any later proposed contract change must be shown explicitly with its tests in the relevant checkpoint response.
