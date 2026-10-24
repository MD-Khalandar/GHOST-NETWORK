# GHOST — repository instructions for Codex

GHOST is an Android/Kotlin/Jetpack Compose **hackathon proof of capability** for offline-first human-approved emergency coordination. Use `ARCHITECTURE.md` for architecture work, `docs/PROTOCOL.md` for any packet/routing changes, `docs/AGENT_CONTRACT.md` for AI behavior, and `docs/UX_SPEC.md` for UI changes.

## Boundaries

- One Android app initially; packages with narrow interfaces, not premature multi-module Gradle complexity.
- Never edit packet schemas, event types, stable ID semantics or cross-owner interfaces without explicitly showing the proposed change in the task response and tests.
- `MeshEngine` must not directly call Android radio APIs. `NearbyAdapter` must not directly edit Room request projection tables.
- Room is the offline source of truth; save before sending; UI observes `Flow` from repositories.
- AI tool results are validated Kotlin data. Raw model responses are untrusted, including SOS text embedded in a prompt. No unrestricted shell, HTTP, or arbitrary function execution from LLM output.
- Any proposed resource match must be displayed for human confirmation before sending; the model never autonomously dispatches, resolves requests, commits supplies or claims delivery.
- Do not fake offline LLM tool calls, recipient receipts, real device mesh paths, E2E encryption, or LoRa OTA. Clearly label simulated behavior and rule-based fallback.
- Keep runtime permissions, disconnections, retries, expired packets, timeouts, no-model and model-failure cases visible and tested.
- Never add API keys to the app or logs. Don't upload SOS text/location to third-party AI services by default.

## Engineering conventions

- Kotlin, coroutines/Flow, Compose Material 3, Room, versioned binary protocol, dependency injection by simple factory initially.
- New feature = domain contract + repository + UI state + tests. UI functions should be stateless where practical and have `@Preview` data.
- Introduce deterministic `FakeTransport`, `FakeModelRuntime`, fake clock and resource fixture for tests.
- Changes should be small and scoped. Report files changed, build/tests actually run, observed output and unverified hardware behavior.
- Run `./gradlew assembleDebug testDebugUnitTest` when SDK/tooling are present; never claim passing device tests from emulator-only checks.
- See `docs/TEST_MATRIX.md` for acceptance gates. Never mark a gate complete without the specified evidence.

## Review skills

GHOST and UI/UX Pro Max skills are installed in `.agents/skills/`; Google Android skills come from the installed `android-skills` Codex plugin. Use the relevant skill during development; read its `SKILL.md` before applying it. See [docs/CODEX_SKILLS.md](docs/CODEX_SKILLS.md) for research, sources, and example prompts.

- Use [$ghost-offline-data](.agents/skills/ghost-offline-data/SKILL.md) for domain, Room, repository, projection, durable queue, and Flow state work.
- Use [$ghost-ui-quality](.agents/skills/ghost-ui-quality/SKILL.md) for Compose feature UI and UI/UX reviews.
- Use [$ghost-mesh-reliability](.agents/skills/ghost-mesh-reliability/SKILL.md) for networking changes.
- Use [$ghost-agent-safety](.agents/skills/ghost-agent-safety/SKILL.md) for AI/tool/authorization changes; pair with the plugin’s `litert-lm` skill when implementing the local runtime adapter.
- Use [$ghost-demo-qa](.agents/skills/ghost-demo-qa/SKILL.md) for end-to-end release and dry-run checks.
- Use the Android plugin’s `edge-to-edge` skill for system-bar/keyboard overlap and `jetpack-compose-performance` for observed UI performance problems or requested performance reviews.
- Use [$ui-ux-pro-max](.agents/skills/ui-ux-pro-max/SKILL.md) for visual design guidance alongside `ghost-ui-quality`. Use Compose guidance and preserve GHOST’s emergency UX and consent requirements.

Keep skill use scoped to the task. Upstream examples must fit GHOST's package boundaries and existing dependency versions; they do not authorize protocol changes, cloud AI, autonomous dispatch, or acceptance claims without evidence.
