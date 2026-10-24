# Codex build phases and ownership

For this checkout, follow the user's [15 sequential checkpoints](CHECKPOINTS.md): finish one checkpoint, stop for the user's own commit and push, then resume after confirmation. The sprint timings and workstream notes below are planning context; they do not authorize starting another checkpoint or parallel agent work.

## Four member-owned workstreams

- **Networking owner:** `transport/api`, `transport/nearby`, `mesh`. Deliver real A–B link, then A–B–C relay and reconnect handling. Use `$ghost-mesh-reliability`.
- **AI/Coordination owner:** `ai/*`, `domain/usecase` coordination tools. Deliver verified actual offline model inference + validated tool selection + decline/replan; use `$ghost-agent-safety`.
- **UI/Product owner:** `design/`, `feature/*`. Deliver screens against fake repository/agent `Flow` first; use `$ghost-ui-quality`.
- **Security/Integration owner:** `protocol`, `security`, `data/local`, `simulation`, device builds, fixture and event schema. Responsible for locking protocol and running the demo proof; use `$ghost-demo-qa`.

One integrator controls shared contracts; use short branches/PRs, merge at least every 1–2 hours, and install onto hardware continually. No two coding agents independently edit `protocol/` or shared `.proto` files.

## Phased delivery (integration gates, 24-hour sprint)

| Gate | Target | Device evidence | Cut-line |
|---|---|---|---|
| H0–H2 | Create Compose/Room skeleton, model feasibility + SDK/permission checks | APK starts on 3 phones; Phone C produces local validated decision from actual model while offline | If local model fails, swap coordinator hardware/model; do not silently use cloud |
| H2–H5 | Two-phone direct Nearby send/receive, signed event schema + Room persistence | A→B on two real devices without internet | Drop optional work until reliable |
| H5–H9 | True A→B→C relay, dedup, queues, signed destination receipt | A/C cannot connect directly; relay logs prove path | No “working mesh” claim without test |
| H9–H13 | SOS + offer event board and full compose interaction | A creates SOS, B offer, C reads projections | Freeze wire protocol |
| H13–H18 | Local model agent decision, Kotlin tools, approval and decline/replan | Trace of real model choosing tools and updating proposal | Narrow tools if model unreliable |
| H18–H21 | UX polish, failure handling, authority tests, reboots | Tests, screen-reader/contrast smoke, offline queues | Freeze new optional features |
| H21–H24 | 3× repeated demo, video backup, screenshots, pitch | Fully offline proof: A SOS; B decline; C replan; A receipt | No LoRa/Wi-Fi Direct scope creep |

**Note:** H0–H2 local model feasibility and Nearby permission tests must run in parallel. Runtime and model may be the dominant blocker.

## First Codex request — coordinator/integrator

```text
Read AGENTS.md, ARCHITECTURE.md, docs/PROTOCOL.md and docs/BUILD_PHASES.md.
Create a new Kotlin/Jetpack Compose Android app skeleton with Room, package boundaries, version catalog and buildable Gradle setup. Add interfaces and fake implementations for GhostTransport, MeshEngine and LocalModelRuntime. Create previews and a home screen shell. Do not implement or claim Bluetooth, AI or SOS delivery. Run assembleDebug and testDebugUnitTest. Show changed files, observed build output, known device tests not yet run and next tasks. Do not change protocol contracts without approval.
```

## Networking Codex request

```text
Implement ONLY transport/nearby, mesh and required boundary wiring against the locked docs/PROTOCOL.md. Use Nearby Connections P2P_CLUSTER; do not assume it performs multi-hop. Add deterministic fake transport tests for A→B→C, inventory exchange, duplicate packets, expiry and reconnect. Preserve signed origin bytes and separate mutable relay metadata. Build APK; report exact device permission and physical-test steps. Never claim real multihop until three physical phones prove it.
```

## Agent Codex request

```text
Implement ai/runtime, ai/orchestrator, ai/tools according to docs/AGENT_CONTRACT.md. Integrate LiteRT-LM with a pre-provisioned .litertlm model on a dedicated background owner. A model text response MUST be parsed to finite AgentDecision JSON; Kotlin validates all tool args, enforces human approval and records actual tool calls. Add fake-model cases for successful proposal, decline→replan, stale offer, injection and malformed output. Report actual hardware model inference separately from fake tests. No cloud calls.
```

## UI Codex request

```text
Implement screens described in docs/UX_SPEC.md using Material 3 Compose, a cohesive color and spacing system, lifecycle-aware StateFlow and previews based on fake repositories. Include full empty/error/stale/queued/relay-received/delivered states, user-consent surfaces, a high-visibility SOS button and a beautiful inspectable Mission Control tool trace. Never show a fake delivery success or label a rule fallback as a local LLM. Run Compose previews, UI tests when available and small-screen/font-scale checks.
```

## Security/integration Codex request

```text
Lock protocol v1, implement typed domain events, Room event projections, signature verification, identity mapping, replay/size/TTL validation and fake transport test fixtures. Use a maintained signing library and a tested storage strategy appropriate for demo handsets. Add tests for forged updates, malformed packets, dedup across restarts, owner-only offer transitions and end-recipient receipts. Integrate small PRs from all owners and publish a verified T01–T13/A01–A08 test report.
```

## Scope once the mandatory demo works

Optional, **in this order:** (1) foreground-relay reliability/battery mode, (2) gateway verified short bulletins, (3) Meshtastic adapter with real radios and legal region settings, (4) explicit Wi-Fi Direct adapter. Do not swap priority: LoRa or flashy animations cannot replace a working local agent replan.
