# GHOST v2 — Codex starter kit

**Project:** GHOST — Offline-First Agentic Emergency Coordination  
**Team:** Claude's co-workers  
**Status:** locked implementation blueprint for a hackathon proof-of-capability. This ZIP contains planning material and Codex skills, **not a compiled app**.

**Local development setup:** this working folder now includes a minimal Android build scaffold and project-local toolchain scripts. See [Environment setup](docs/ENVIRONMENT_SETUP.md) for installation, build commands, and remaining handset checks. The scaffold does not implement the emergency coordination features.

## What you are building

One Kotlin/Jetpack Compose Android app that can run in three **roles**: standard peer (SOS/volunteer/relay), local AI coordinator (runs a downloaded on-device LLM), optional bridge (Meshtastic or internet). The principal demonstration has all three phones offline: Phone A makes an SOS, B relays it, C's *actual local model* selects validated coordination tools, B declines an offer, C replans using another known offer, and A receives the human-approved response via B.

## Start here

1. Verify three real Android devices, Google Play Services, Nearby Connections permissions and **one suitable coordinator phone**. Test local model inference and structured JSON generation before declaring the AI path viable.
2. Create an empty Kotlin/Compose Android project. Copy `AGENTS.md`, `ARCHITECTURE.md`, `docs/`, and `.agents/skills/` into the repository root.
3. Give Codex `AGENTS.md` and point it at `docs/BUILD_PHASES.md`. Implement **one acceptance gate at a time**; do not ask Codex to generate the whole app in one request.
4. Use Google Android skills via the official plugin (if supported in your Codex version), and call the local `$ghost-*` skills when reviewing each subsystem.
5. Pre-provision and verify the local `.litertlm` model on Phone C. Turn mobile data/Wi-Fi internet off on all demo phones, while keeping the local radios used by Nearby on.

## Files

- `ARCHITECTURE.md` — system, modules, data flow, hard decisions.
- `docs/PROTOCOL.md` — signed immutable packets, forwarding, security, and receipt contracts.
- `docs/AGENT_CONTRACT.md` — exactly what the local model can do, verified Kotlin tools, agent state, deterministic guardrails.
- `docs/UX_SPEC.md` — UI information architecture, design tokens and accessible status semantics.
- `docs/BUILD_PHASES.md` — integration-first plan and parallel Codex work orders.
- `docs/TEST_MATRIX.md` — objective tests for radio, agent, UI and end-to-end proofs.
- `AGENTS.md` — short repo-wide instructions Codex should apply to every task.
- `.agents/skills/.../SKILL.md` — four project-specific repeatable review workflows.

## Demo scope, not production promise

No certified emergency dispatch, untested E2E encryption, unverified volunteer/rescue claims, fake delivered badges, fake LoRa radio transmission, or fabricated live official bulletins.

## Verified documentation starting points

- Android offline-first: https://developer.android.com/topic/architecture/data-layer/offline-first
- Android LiteRT-LM Kotlin skill: https://developer.android.com/agents/skills/device-ai/litert/litert-lm/skill
- Model examples: https://developer.android.com/agents/skills/device-ai/litert/litert-lm/references/get-a-model
- Nearby Connections: https://developers.google.com/nearby/connections/android/get-started
- Android skills for coding agents: https://github.com/android/skills
- Codex skills authoring: https://learn.chatgpt.com/docs/build-skills
