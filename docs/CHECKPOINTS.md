# SOS implementation — 10 checkpoints

Build SOS sending first: offline phones → mesh → online gateway → cloud database/dashboard → one cloud coordinator → approved response back through the mesh. The database stores requests, helpers and supply/help offers. Ordinary chat, generic internet services, local LLM helpers and LoRa are deferred, not removed from the wider product.

**Current position:** CP01 role A contract baseline prepared in `docs/CP01_BASELINE.md`; waiting for the user to commit/publish it before B/C/D start. No implementation or device/cloud gate is complete. Preserve the existing Android scaffold/toolchain.

Each checkpoint has **four copyable prompts** in [BUILD_PHASES.md](BUILD_PHASES.md): A establishes contracts and owns final integration; B handles Android data/network; C handles cloud/database; D handles Android UI and web dashboard. Run A's preparation first. B/C/D then use the same published contract baseline and may work in parallel. A verifies the merged result before the checkpoint closes.

## Checkpoints and outcomes

| # | Work | Completion evidence | Team prompts |
| --- | --- | --- | --- |
| 1 | **Contracts and foundations.** Agree SOS/helper/offer/cloud and dashboard-read APIs, choose server/database/dashboard stack, establish Android interfaces/fakes, server scaffold and four-tab UI. | Android/server smoke checks, deterministic fixtures and matching contracts. Dashboard setup is planned; SOS sending remains first. | [CP01](BUILD_PHASES.md#cp01) |
| 2 | **Identity and trust.** Device signing, trusted cloud identity, event validation, gateway authentication, operator roles and consent types. | Shared signature fixtures, invalid/forged event rejection, role isolation and handset key-storage observations (T07/T08, A10). | [CP02](BUILD_PHASES.md#cp02) |
| 3 | **Durable SOS.** Compose/save an SOS locally, restore drafts and persist pending sends. Create cloud SOS/helper/offer/event tables and idempotent job storage; profile/offer entry follows at CP07. | Save-before-send, rollback, reopen, foreign-key/ownership and duplicate-ingestion tests; usable no-peer SOS form (T03/T05/T07, U01, D02). | [CP03](BUILD_PHASES.md#cp03) |
| 4 | **Direct phone link.** Real Nearby A↔B SOS exchange. In parallel, build bounded cloud request-read APIs and the first read-only dashboard against test records. | Two physical phones exchange IDs offline; denial/retry visible (T01/T10). Dashboard/API tests clearly label fixtures; no live incoming claim yet. | [CP04](BUILD_PHASES.md#cp04) |
| 5 | **Mesh relay and recovery.** A↔B↔C forwarding, dedup, expiry, retries, inventory, signed phone receipts and durable cloud return outbox. | Physical A/C isolation and relay; disconnect/restart and wrong-receipt checks (T02–T06/T09). | [CP05](BUILD_PHASES.md#cp05) |
| 6 | **Cloud gateway and incoming dashboard.** Upload signed SOS through online phones, persist in cloud, show actual incoming requests in the web dashboard and relay cloud ACKs back. | A/B offline, C online: same request ID in cloud/database/dashboard and ACK on A; duplicate/restart/outage recovery (G01–G04, D01/D03). No model required. | [CP06](BUILD_PHASES.md#cp06) |
| 7 | **Helpers, supplies and policy.** Owners register helper profiles/resources; dashboard lists who can help and current supplies. Add dashboard operator approval and phone owner accept/decline with cloud confirmation. | Owner/profile/offer persistence and quantity/availability checks; stale/unauthorized actions and conflicting allocation rejected (A03/A06/A09/A10, D02–D04). No fake AI claim. | [CP07](BUILD_PHASES.md#cp07) |
| 8 | **Single cloud agent.** Real provider, durable worker and read/draft tools query the shared request/helper/offer data. Web dashboard shows actual traces/drafts; phone Mission shows status. | Actual model selects tools and waits for dashboard operator approval; dashboard/agent data agree (A01/A02/A03/A07/A08, U04, D04). | [CP08](BUILD_PHASES.md#cp08) |
| 9 | **Decline/replan and failure UX.** Owner decline triggers a fresh cloud run; prevent stale/duplicate actions; cover cloud/model outages and phone/browser accessibility/recovery. | Real replan and fresh dashboard approval (A04/A05), restart/outage and phone/browser UX evidence (T10–T12, A08–A10, U01–U06, D03). | [CP09](BUILD_PHASES.md#cp09) |
| 10 | **Integrated three-phone/dashboard proof.** Package APK, server/dashboard runbook and evidence for SOS→cloud database/dashboard→agent→approval→decline/replan→phone receipt, plus outage/reconnect. | Three captured runs and outage/recovery (T13); every active T/A/G/U/D gate reported with evidence. | [CP10](BUILD_PHASES.md#cp10) |

## Working and handoff rules

- Work on the current checkpoint only. Within it, B/C/D may run in parallel after A's contract baseline. Do not run dependent checkpoints concurrently.
- Each friend uses a separate branch/worktree/clone. Shared contracts and integration files have one owner; no independently invented API/schema changes.
- The user/team owns staging, commits, pushes and merge commits. AI returns changes/tests and may help resolve conflicts; it does not run `git add`, `git commit` or `git push`.
- Intermediate user-pushed contract commits enable team work; they do not mark a checkpoint complete. After the user merges work, A runs integration checks and records evidence.
- At checkpoint handoff report changes, actual checks/results, missing device/cloud evidence and status, then stop. Resume the next checkpoint after push confirmation, or a request to continue plus fresh read-only upstream verification.
- Run relevant Android, server and dashboard checks for implementation changes using the documented commands. Doc-only work does not build. A green merge/build cannot replace physical or real-provider evidence.
- Missing hardware/service means blocked or evidence pending. Explain it before advancing; only the user can change the gate/order.

## Progress and evidence

Record real evidence links and verified user-pushed commits; do not infer acceptance from a commit. Statuses: not started, in progress, implemented/evidence pending, blocked, complete.

- 01 — In progress: role A baseline review pending user commit/publish; B/C/D not started.
- 02 — Not started.
- 03 — Not started.
- 04 — Not started.
- 05 — Not started.
- 06 — Not started.
- 07 — Not started.
- 08 — Not started.
- 09 — Not started.
- 10 — Not started.

References: [architecture](../ARCHITECTURE.md), [protocol](PROTOCOL.md), [agent contract](AGENT_CONTRACT.md), [UX](UX_SPEC.md), [test matrix](TEST_MATRIX.md). This revision supersedes the 15-checkpoint hybrid/local-coordinator plan; deferred gates are not marked passed.
