# SOS team prompts

Use these four prompts per checkpoint on separate branches/computers. They implement the [10-checkpoint plan](CHECKPOINTS.md). **A goes first; B/C/D then run in parallel; A finishes integration after the user merges their branches.** With three people, one can take A plus one implementation role sequentially.

## Shared instructions for every prompt

Read `AGENTS.md`, the named checkpoint and relevant architecture/protocol/agent/UX contracts. Inspect the current implementation; planned classes are not evidence they exist. Use the relevant GHOST skill. Work only on the assigned checkpoint/owned files. Preserve existing Android versions.

| Role | Owned files |
| --- | --- |
| A — Contract/integration | `contracts/**`, Android `domain/model/**`, `transport/api/**`, repository/gateway boundary interfaces, root/Android build configuration, manifest, `app/**` composition/navigation, shared docs and integration tests |
| B — Android core | Android `security/**`, `protocol/**` implementations, `data/local/**`, `domain/usecase/**`, `mesh/**`, `transport/nearby/**`, `gateway/**` implementations, `simulation/**` and matching tests |
| C — Cloud/database | `server/**` except generated/shared contracts; includes database migrations, request/helper/supply repositories, APIs, model adapter and tests |
| D — Android UI/dashboard | Android `feature/**`, `design/**`, feature ViewModels/tests, plus `dashboard/**` frontend/dependencies/tests; use agreed APIs/interfaces |

A records exact boundary-interface paths at each checkpoint. Generated files are regenerated from A's schema, never hand-edited by another role. B/C/D report required shared-file changes to A instead of editing those files. UI tests may use D-owned fixtures; do not alter B's repository fake or C's server internals.

Android package paths above are relative to `app/src/main/java/org/ghost/`; `app/**` in A's row means that composition package, not the whole Gradle module. Each role owns corresponding test packages. Server paths are mapped to the chosen framework at CP01.

Use branches such as `cp01-contract`, `cp01-android`, `cp01-cloud`, `cp01-ui` from the same agreed baseline. The team user commits/pushes A's preparation before B/C/D start. User merges implementation branches into an integration branch; A reviews conflicts against the shared contract and runs combined checks. No auto-commit, force-push or silent schema compromise.

Each prompt ends with changed files, actual commands/results, contract assumptions, missing evidence and merge notes. Run relevant tests; Android command is `./gradlew assembleDebug testDebugUnitTest` when tooling exists. Server/dashboard commands are selected in CP01 and recorded when their scaffolds exist. Missing tools/devices/services are reported, never passed. B/C/D do not independently mark the checkpoint complete. Their evidence may use `docs/evidence/cpNN/<role>.md`; A owns the consolidated checkpoint status. Prioritize the SOS sending slice; dashboard work proceeds alongside it and cannot gate local SOS sending.

## CP01

**Contracts and foundations.** A publishes the interface/API baseline before the other three start.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP01 role A.
Agree and record backend/database/dashboard stack, model provider and deployment target. Freeze signed SOS/helper/offer/cloud/receipt schemas, dashboard-read/sync/approval APIs, typed Android boundaries and fake fixtures in your owned files.
Define exact ownership paths, server test command and app wiring hooks; preserve the Android toolchain. Publish a reviewable baseline for the user to commit before B/C/D start.
After their user-merged changes, wire factories/navigation, run Android/server smoke checks, record real results and stop.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP01 role B from A's agreed baseline.
Implement deterministic FakeTransport, FakeCloudClient, clock and SOS/offer repository fixtures behind the supplied interfaces; establish your Android package boundaries.
Test repeatable queue/state behavior. Do not add real radios, Room, cloud calls or local inference yet. Return factory wiring instructions for A.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP01 role C from A's agreed baseline.
Create the separate server scaffold in the selected stack, configuration validation, health endpoint, fake model/provider and deterministic test fixtures.
Document exact local run/test commands and required environment-variable names without secrets. Test startup/config failure; no real coordination or deployment claim.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP01 role D from A's agreed baseline.
Build stateless Home, Requests, Mission and Network screen shells with existing Material 3 tokens and fixture previews. Show SOS as the primary action and label simulated state.
Use supplied repository/UI boundaries; expose screen entry points for A's navigation wiring. Check previews/accessibility semantics; no radio/model calls.
```

## CP02

**Identity and trust.** Implement the shared signing and authorization contract.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP02 role A.
Publish canonical key/signature bytes, protobuf/API fixtures, cloud trust identity configuration, consent defaults, operator/gateway roles and rejection codes before B/C/D work.
After merging, run cross-Android/server fixture checks and inspect handset key-storage evidence. Report missing hardware; do not treat host crypto tests as handset proof.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP02 role B.
Implement device identity/key storage, sign/verify and packet encoding/validation using A's fixtures. Keep NodeId separate from Nearby handles and reject forged cloud identities.
Test invalid signatures, expiry/size/version, signer/key mismatch and Android key persistence. Record actual handset compatibility where available.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP02 role C.
Implement origin signature/actor validation, cloud service signing, gateway authentication and authenticated operator role checks using A's contract.
Keep credentials/signing secrets server-side. Test forged origin/gateway identity, role escalation and matching cross-platform signature fixtures.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP02 role D.
Build public-sharing/cloud-consent controls, identity/verification diagnostics and authenticated-versus-unauthorized operator UI states against agreed interfaces.
Test consent defaults and ensure a local role toggle cannot enable approval. Display verification failures honestly without logging sensitive content.
```

## CP03

**Durable SOS.** An SOS is useful locally before any peer or cloud is reachable.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP03 role A.
Freeze publish/update/query repository contracts, transaction outcomes and event-to-job idempotency keys before B/C/D begin; coordinate Android/server migration ownership.
After merging, wire the local SOS slice and verify atomic save-before-send, rollback, reopened storage and no-peer UI behavior. Record evidence without claiming radio delivery.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP03 role B.
Implement Room entities/DAOs/repositories, authorized event projections, durable outbox/dedup, SOS publish/update use cases and Flow state.
Save signed SOS plus projection/outbox atomically. Test no-peer publication, duplicates, rollback and reopened database recovery; do not replace Room tests with in-memory fakes.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP03 role C.
Implement durable SOS/helper/supply-offer/event/job tables, owner references and migrations. Accept validated fixtures atomically with origin-event dedup and one logical job trigger.
Test ownership/consent, offer-before-profile placeholders, duplicate records, rollback and reopen. Profile/offer submission UI follows at CP07; keep the model worker disabled.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP03 role D.
Implement SOS category/description/optional people/location form, content/audience preview, explicit send, queued detail and locally known requests list.
Connect agreed repository Flow; retain form input on failure/recreation and prevent duplicate submits. Test validation/consent/draft restoration and no-peer state.
```

## CP04

**Direct Nearby delivery.** Prove two-phone exchange and prepare server read/sync endpoints.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP04 role A.
Freeze permission/connection states, bounded dashboard request/detail and cloud sync API fixtures. Own required Android manifest/build/composition changes after checking target SDK/handsets.
After merging, run direct A/B, server API and dashboard checks. Capture T01/T10 separately from fixture-based dashboard previews.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP04 role B.
Implement NearbyAdapter behind GhostTransport: discovery, authenticated pairing, send/receive, permission/radio failure and disconnect handling. Route received bytes through validation/repository persistence.
Test callback/error cases; exchange signed SOS between two physical phones offline when available. Neighbor send success must not mark recipient delivery.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP04 role C.
Implement bounded SOS list/detail/filter queries for the dashboard and sync API handlers over CP03 storage using agreed authentication/error contracts.
Test pagination, authorization scope, stale/invalid cursors and bounded batch input. No real agent/model work yet.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP04 role D.
Build Android peer/pairing/permission/direct-SOS progress states first. Then scaffold dashboard/ with authenticated incoming-SOS list/detail, polling, pagination and last-refresh/error states against C's API/test fixtures.
Test Android permission recovery and dashboard empty/loading/stale/unauthorized behavior. Label fixture records; SOS sending must remain independent of the dashboard.
```

## CP05

**Relay and recovery.** Prove real A↔B↔C movement and preserve queued work.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP05 role A.
Publish inventory, retry, expiry and receipt state fixtures plus cloud return-outbox acknowledgement semantics before parallel work.
After merging, exercise isolated A/C relay, loop/replay bounds, signed receipts and physical B restart. Consolidate T02–T06/T09 evidence; distinguish fakes from actual radio paths.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP05 role B.
Implement MeshEngine store-and-forward, previous-hop exclusion, hop/TTL caps, bounded inventory/retry, durable dedup and signed recipient receipts.
Test loops, repeated packets, wrong receipts and recovery; prove A↔B↔C on real devices where available. Keep Nearby APIs inside the adapter.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP05 role C.
Implement durable signed cloud return-outbox records, bounded downloads, scoped cursors and acknowledgement recovery using test events.
Test crashes around cursor advancement and repeated downloads. Keep gateway download acknowledgement separate from final phone receipt.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP05 role D.
Implement SOS delivery timeline, observed relay details, queued/expired/failed/retrying states and receipt labels.
Render out-of-order observations and stale data correctly; test restoration and ensure a relay acknowledgement cannot display recipient delivery.
```

## CP06

**Gateway to the cloud.** Finish the actual phone→cloud→phone path before adding AI.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP06 role A.
Freeze GatewayClient batch/cursor/ack and cloud reachability contracts, endpoint configuration and cloud-key wiring. Use the same contract for a connected originating phone.
After merging, verify A's SOS appears as the same committed request in cloud storage and the dashboard, and its ACK returns through B/C. Capture G01–G04 and D01/D03 evidence; no LLM-success claim.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP06 role B.
Implement opt-in gateway/direct sync: upload unchanged authorized origin events, persist downloaded cloud-signed packets before advancing cursors, relay them and return phone receipts.
Test denied consent, duplicate gateways, internet loss, bad cloud signatures and restart. Keep provider secrets and model execution out of Android.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP06 role C.
Connect authenticated ingestion, atomic event/job persistence and signed CLOUD_ACK to the real sync API and return outbox. Add request/gateway association checks and receipt ingestion.
Test duplicate uploads from two gateways, wrong-scope downloads, expired events and interrupted retries. Run against the configured test service without inventing deployment evidence.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP06 role D.
Build Android gateway/status/retry views and connect the dashboard to actual committed SOS list/detail APIs. Show source ID, received time, cloud state and last refresh.
Test phone pending versus dashboard received, duplicate uploads showing one request, unauthorized reads and polling failure. Preserve local SOS sending when the dashboard/server is unavailable.
```

## CP07

**Offers and human coordination policy.** Make the action path correct before the model uses it.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP07 role A.
Freeze helper profile/offer read-write contracts, dashboard directory/approval APIs, proposal versions and owner accept/decline/cloud confirmation; update shared fixtures first.
After merging, exercise a clearly labelled manually created test proposal, approval, owner acceptance and transactional confirmation. Validate A03/A06/A09/A10 without claiming AI execution.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP07 role B.
Implement signed owner helper profile and supply/help offer publish/update/withdraw, Room projections, phone accept/decline and cloud confirmation handling. Operator approval belongs to the dashboard.
Test ownership, stale versions, duplicate actions and offline acceptance pending cloud confirmation. Do not reserve resources on a draft or local click.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP07 role C.
Implement owner-authorized helper registration/profile updates and linked supply/help repositories/read APIs. Use the same repositories for dashboard directories and fresh matching queries. Add manual test drafts, version-specific operator approval and owner-acceptance transactions.
Test revoked/insufficient offers, unauthorized approval, competing accepts and idempotent confirmation; at most available quantity can be allocated. Keep model integration out of this checkpoint.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP07 role D.
Build Android helper/offer forms and owner accept/decline views. Add dashboard helper/supply directory, request details and authenticated approve/reject controls via server APIs; no direct database writes.
Test owner/operator separation, unknown/stale supply quantities, exact proposal versions, duplicate taps and pending versus confirmed allocation. Label manual test drafts accurately.
```

## CP08

**One cloud agent.** Model reasoning now uses the already-tested policy and action path.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP08 role A.
Freeze finite model decision/tool schemas, sanitized run-trace API and single-worker ownership/idempotency invariants before B/C/D work.
After merging, verify real provider output→validated server tool→draft→operator approval. Capture A01/A02/A03/A07/A08; separate provider evidence from fake-model tests.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP08 role B.
Implement cloud run/proposal summary synchronization and repository Flow for the read-only phone Mission view. Keep operator approval in the web dashboard.
Test authorization failure, stale trace/result, reconnect and repeated approval response. Do not put a model runtime or coordination loop on the phone.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP08 role C.
Implement the real model adapter, one durable worker, bounded parser and typed tools over the same SOS/helper/offer repositories used by the dashboard. Expose sanitized run/trace read APIs under AGENT_CONTRACT.md.
Persist sanitized actual tool observations; enforce consent, limits and human approval. Test injection, malformed output, no offers, provider timeout, duplicate triggers and exclusive worker ownership; record real inference separately.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP08 role D.
Build web dashboard agent activity showing actual run/provider/tools/results, draft version and operator approval. Add read-only status to Android Mission using B's repository boundary.
Test loading/no-run/model-failure/stale/unauthorized views and agreement with server records. Approval shows exact current content/version and never executes from screen rendering.
```

## CP09

**Replan and failures.** Finish owner decline, fresh proposals and honest outage behavior.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP09 role A.
Publish decline/replan and outage/restart scenario fixtures using existing contracts; authorize any necessary contract correction before parallel edits.
After merging, capture real cloud replan after B declines, fresh operator approval and A receipt. Run regression/accessibility checks; report A04/A05 and remaining failure gates honestly.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP09 role B.
Complete restart-safe decline/accept/receipt delivery and stale-proposal invalidation across mesh/cloud disconnection. Keep queued work through process death and suppress expired work.
Test late/out-of-order/duplicate results, consent revocation and reconnect. A local acceptance or stale approval cannot become confirmed without the server's current decision.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP09 role C.
Implement decline/withdrawal-triggered fresh jobs, alternative-offer replanning and old-proposal invalidation. Recover worker/provider failures within bounded budgets.
Test repeated declines, closed requests, stale approval, late model completion, competing allocations and server restart. Capture an actual new model run selecting a different valid offer.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP09 role D.
Finish proposal history and cloud failure UX in dashboard and Android. Preserve drafts; make confirmation, freshness and server/dash connectivity explicit.
Check dashboard keyboard/accessibility/refresh recovery and Android small screens, 200% text, TalkBack, dark mode, keyboard/system bars and lifecycle. Report actual observations separately from previews.
```

## CP10

**Integrated proof and handoff.** No new feature scope; fix only evidenced release blockers.

**A — Contract/integration**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP10 role A.
Prepare the integrated branch baseline and evidence index. After user merges B/C/D fixes/reports, run Android/server regressions and verify every active TEST_MATRIX gate.
Package APK locations, exact server/database/dashboard run/deploy configuration names, demo steps and limitations. Verify D01–D04 as well as existing gates; stop for the user's commit/push.
```

**B — Android core**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP10 role B.
Run three-phone mesh/gateway/receipt and physical restart checks on the integrated baseline. Confirm A/C isolation, original packet IDs, duplicate suppression and queued outage recovery.
Record evidence in docs/evidence/cp10/android.md; fix only reproducible defects in your owned files and rerun affected checks. Do not invent hardware results.
```

**C — Cloud**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP10 role C.
Run actual cloud/provider coordination, approval/owner-authority, worker-restart and duplicate-gateway/resource-conflict checks against the integrated baseline.
Record redacted run/job/tool/approval evidence in docs/evidence/cp10/cloud.md and server runbook details. Fix only evidenced server blockers; no fake provider or exactly-once claims.
```

**D — UI**
```text
Follow AGENTS.md and docs/BUILD_PHASES.md; perform CP10 role D.
Run the full SOS→incoming web dashboard→agent→dashboard approval→decline/replan→phone receipt script three times, plus outage/reconnect. Verify request/helper/supply records agree with cloud data.
Record phone/browser/accessibility evidence in docs/evidence/cp10/ui.md. Run the dashboard's documented checks; fix only reproduced UI blockers and leave unsupported gates open.
```
