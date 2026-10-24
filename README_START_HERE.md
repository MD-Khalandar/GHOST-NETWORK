# GHOST — start here

GHOST's wider vision is mesh communication, saved-contact messaging, SOS/help and in-app internet access. **Build SOS first with one cloud coordination agent.** Offline phones send signed reports through mesh peers to an opted-in online gateway; approved cloud responses return through the mesh.

Local LLM helpers, ordinary chat, generic information services and LoRa are deferred. When no gateway has internet, SOS still stores/relays locally but cloud coordination waits.

**Current state:** Android Kotlin/Compose scaffold and setup scripts; no product checkpoint implemented. Preserve the setup. Cloud service, shared SOS/helper/supply database and web dashboard are planned, not provisioned. Build SOS sending first; the incoming dashboard follows cloud ingestion.

## Read / work

1. [AGENTS.md](AGENTS.md): working rules.
2. [ARCHITECTURE.md](ARCHITECTURE.md): cloud coordinator, phone/gateway boundaries and later local-helper ideas.
3. [10 checkpoints](docs/CHECKPOINTS.md): explanation, sequence and progress.
4. [40 team prompts](docs/BUILD_PHASES.md): contract owner first, then Android, cloud/database and Android UI/web dashboard work in parallel.
5. Relevant [protocol](docs/PROTOCOL.md), [agent](docs/AGENT_CONTRACT.md), [UI](docs/UX_SPEC.md) and [acceptance](docs/TEST_MATRIX.md) contracts.

Use [README.md](README.md) for Android setup on Windows/macOS/Linux. [Environment setup](docs/ENVIRONMENT_SETUP.md) records historical host checks; it does not prove networking or cloud behavior.

## Demo

A is the offline requester, B the offline relay/volunteer, C the online gateway/volunteer. The operator uses the web dashboard on a computer or C's browser. A/C must communicate through B. The server runs the only coordinator.

Prove SOS upload into the database/incoming dashboard, helper/supply visibility, real cloud tools, dashboard approval, decline/replan and signed requester receipt. Then remove C's internet, show pending state and recover. This is not an all-offline AI demonstration.
