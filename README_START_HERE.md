# GHOST — start here

GHOST's wider vision is mesh communication, saved-contact messaging, SOS/help and in-app internet access. **Build SOS first with one cloud coordination agent.** Offline phones send signed reports through mesh peers to an opted-in online gateway; approved cloud responses return through the mesh.

Local LLM helpers, ordinary chat, generic information services and LoRa are deferred. When no gateway has internet, SOS still stores/relays locally but cloud coordination waits.

**Current state:** CP01 foundations are complete: Android Home/Requests/Mission/Network previews, deterministic local fixtures and a runnable server health/config scaffold. The app labels its data simulated; Send SOS opens a preview and does not transmit. Shared database, real cloud coordination and dashboard implementation are later checkpoints. See [integration checks and run instructions](docs/evidence/cp01/integration.md).

## Read / work

1. [AGENTS.md](AGENTS.md): working rules.
2. [ARCHITECTURE.md](ARCHITECTURE.md): cloud coordinator, phone/gateway boundaries and later local-helper ideas.
3. [Checkpoints](docs/CHECKPOINTS.md): milestone status. Implement small changes within a milestone; a change does not need its own checkpoint or report.
4. Only the relevant [protocol](docs/PROTOCOL.md), [agent](docs/AGENT_CONTRACT.md) or [UI](docs/UX_SPEC.md) contract for the change, with applicable [acceptance](docs/TEST_MATRIX.md) cases.

For parallel checkpoint work, use the assigned role in [BUILD_PHASES.md](docs/BUILD_PHASES.md).
The [CP01 baseline](docs/CP01_BASELINE.md) is a historical handoff, not a second
current status document. The [hackathon implementation](ARCHITECTURE.md#hackathon-implementation)
keeps the SOS functionality in one app, one server process and one cloud database.

Use [README.md](README.md) for Android setup on Windows/macOS/Linux. [Environment setup](docs/ENVIRONMENT_SETUP.md) records historical host checks; it does not prove networking or cloud behavior.

## Demo

A is the offline requester, B the offline relay/volunteer, C the online gateway/volunteer. The operator uses the web dashboard on a computer or C's browser. A/C must communicate through B. The server runs the only coordinator.

Prove SOS upload into the database/incoming dashboard, helper/supply visibility, real cloud tools, dashboard approval, decline/replan and signed requester receipt. Then remove C's internet, show pending state and recover. This is not an all-offline AI demonstration.
