# GHOST — repository instructions

GHOST is an Android mesh communication app. **Current phase: SOS sending, one cloud coordinator, shared request/helper/supply database and web dashboard.** Offline phones relay signed reports through an online gateway; coordination/tools run on the server. Ordinary messaging, generic internet services, local LLM helpers and LoRa are later work. See [ARCHITECTURE.md](ARCHITECTURE.md).

## Checkpoints and team work

- Follow the current one of [10 checkpoints](docs/CHECKPOINTS.md); preserve the existing Android scaffold/toolchain.
- Each checkpoint has four [team prompts](docs/BUILD_PHASES.md): A contracts/integration first; B Android, C cloud/database and D Android UI/web dashboard may then work in parallel from the same user-published baseline. A verifies the user-merged result. SOS sending stays the first working slice.
- Follow prompt file ownership. Only A changes shared schemas/interfaces, app composition/build files and consolidated checkpoint status. Do not independently invent a conflicting API.
- Separate branch/worktree/clone per implementer. Work only on the current checkpoint; no later checkpoint without user direction.
- The user/team stages, commits, pushes and performs merge commits. Never run `git add`, `git commit` or `git push` for checkpoint work. AI may review/fix merge conflicts within authorized scope.
- Intermediate contract commits enable collaboration, not checkpoint completion. At final handoff report changes, checks/results, missing hardware/cloud evidence and status, then stop.
- Advance after the user confirms the completed checkpoint was pushed, or asks to continue and a fresh read-only upstream check verifies it. A local commit/stale remote reference is insufficient. A push does not satisfy missing acceptance evidence.

## Boundaries

- One Android app with narrow Kotlin packages/simple factory injection, a separate cloud service and `dashboard/` web frontend. Keep Android versions; choose server/database/dashboard stack and provider once at checkpoint 1.
- Read [protocol](docs/PROTOCOL.md), [agent contract](docs/AGENT_CONTRACT.md) and [UX](docs/UX_SPEC.md) for relevant work. Show contract/ID/schema changes explicitly with tests; docs-only changes specify future tests without claiming execution.
- Room is phone-local source of truth; cloud database owns accepted shared coordination state. Save before transmit and expose repository Flow. Cloud only knows uploaded reports.
- Dashboard and agent use the same server repositories for SOS, helper identities and supply/help offers. The dashboard calls authenticated APIs; no direct database/provider access. Web operators approve proposals; phone owners accept/decline their own offers.
- `MeshEngine` owns forwarding; radio APIs stay in `NearbyAdapter`. Adapters cannot directly edit projections. Gateways preserve origin signatures and verify cloud-signed returns.
- One logical durable cloud coordinator; no phone-local coordinating model or automatic local fallback. Validate model decisions through bounded typed server tools.
- Treat reported/model text as untrusted. No arbitrary generated shell, SQL, URLs or functions.
- Authenticated human approval precedes outward proposals; owners accept/decline resources and requesters close SOS. Cloud transactions prevent conflicting allocations. A queued acceptance is not confirmation.
- Separate public sharing/cloud consent; provider credentials and cloud signing secrets stay server-side. No private data or secrets in diagnostic logs.
- Distinguish local queue, neighbor/relay, cloud acceptance, human approval, phone receipt and resource confirmation. Label fakes honestly; do not claim E2E encryption, radio paths or successful inference without evidence.
- Keep no-peer/no-internet/cloud/model failure, permissions, retries, expiry and restart behavior visible.

## Checks and skills

For Android changes run `./gradlew assembleDebug testDebugUnitTest` when tooling is present; server/dashboard changes use their documented commands. Keep host/fake/emulator, physical-phone/browser and real-cloud evidence separate against [TEST_MATRIX.md](docs/TEST_MATRIX.md). Docs-only work checks consistency/links and does not build.

Read the relevant skill: [offline data](.agents/skills/ghost-offline-data/SKILL.md), [mesh reliability](.agents/skills/ghost-mesh-reliability/SKILL.md), [agent safety](.agents/skills/ghost-agent-safety/SKILL.md), [UI quality](.agents/skills/ghost-ui-quality/SKILL.md), [demo QA](.agents/skills/ghost-demo-qa/SKILL.md). Use [UI/UX Pro Max](.agents/skills/ui-ux-pro-max/SKILL.md) for visual design. Android edge-to-edge/performance skills apply to their specific issues; LiteRT-LM integration is deferred. [Skill installation context](docs/CODEX_SKILLS.md) does not override current product scope.
