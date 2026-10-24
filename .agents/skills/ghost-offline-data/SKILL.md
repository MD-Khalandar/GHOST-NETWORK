---
name: ghost-offline-data
description: Implement or review GHOST Kotlin domain models, Room repositories, event projections, persistent queues, and Flow UI state. Use for offline data features and restart consistency.
---

# GHOST offline data development

Work from the GHOST repository root. Read `AGENTS.md`, `ARCHITECTURE.md`, and the current checkpoint in `docs/CHECKPOINTS.md`. Read `docs/PROTOCOL.md` when working with signed events or the queue. Inspect implementation before assuming planned classes exist.

## Trace the feature through persistence

Identify the user action, typed domain input, repository write, Room transaction, observable state, and any later transport attempt. Keep the initial single `:app` module and factory injection. Reuse the version catalog and existing AGP/Kotlin setup; a data feature does not call for new modules, Hilt, or a dependency upgrade.

- Expose typed domain data from repositories; keep DAOs, transport SDKs, and model SDKs out of composables.
- Treat verified immutable events as history and projections as derived views. Apply signer/owner authorization before a remote event changes a projection.
- Persist outgoing signed bytes and the necessary queue state before attempting a send. Group related local writes in a Room transaction so a failure cannot leave a visible successful action without its durable queue entry.
- Recover pending work and deduplication state from storage after restart. Use stable domain IDs for idempotency, independently of transient Nearby endpoint handles.
- Keep receive callbacks behind validation and repository boundaries. A radio adapter must not update request projections itself.
- Preserve user forms across navigation and recreation where required; do not confuse saved form input with a published event.
- Track local persistence, cloud acknowledgement, proposal version, phone receipt and allocation confirmation separately. Verify the trusted cloud signer; gateway delivery alone cannot confirm assistance.
- Room is local truth; the cloud owns shared coordination state. Store owner consent, pending acceptance and cloud provenance without inventing a local coordinator or confirmed reservation. Ordinary contact/chat persistence is deferred in the SOS phase.
- Owner-signed helper profiles link to supply/help offers by stable identity. Test profile ownership/consent and offer-before-profile handling; keep unknown profile details unknown. The dashboard/agent share server records rather than writing independent inventories.

Changes to packet schemas, event types, stable IDs, or shared interfaces require an explicit proposed change and applicable tests. Documentation-only work specifies future tests rather than claiming compatibility is implemented.

## Expose honest observable state

Use Room `Flow` for local reads, repository transformations, and ViewModel `StateFlow` for UI state. Collect UI flows with `collectAsStateWithLifecycle`. Separate durable work from screen collection so leaving a screen does not discard a queued SOS or restart a send.

Represent loading, empty, stale, pending, failure, and retry states as applicable. A successful local write means queued, not delivered. Show projection freshness instead of assuming disconnected peers share a current global view.

Inject time and dispatchers where timing/concurrency matters. Use structured cancellation; do not swallow `CancellationException`. Keep blocking work off the main thread, retries bounded, and expiry checks deterministic.

## Verify the changed behavior

Choose tests for the actual risk: offline publication, transaction rollback, duplicate event application, unauthorized updates, projection freshness, or queue recovery after reopening storage. Use deterministic clocks and fixtures; use Room tests for SQL/transaction behavior rather than treating an in-memory repository fake as proof of database persistence.

For application changes run `source scripts/env.sh` and `./gradlew assembleDebug testDebugUnitTest` when tooling is present. Report observed results and unverified migration/device behavior. No acceptance gate is complete merely because fakes pass; docs-only work does not require a build.

For UI work use `ghost-ui-quality`; networking changes use `ghost-mesh-reliability`; authorization and agent tools use `ghost-agent-safety`. Read those skill files only when the task actually crosses those boundaries.

Reference: [Android offline-first data architecture](https://developer.android.com/topic/architecture/data-layer/offline-first). The local event and protocol contracts take precedence over generic cloud synchronization examples.
