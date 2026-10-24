# CP01 role B — Android core

Status: **role B implemented; A integration pending**, 24 October 2026.
This is role B host/simulation evidence, not consolidated CP01 completion.

## Published baseline and ownership

Implemented on `android` from A's `contract` commit
`abe7dd5f8b3e88dcace420cb3874ca29382f8293` (`Checkpoint 1A`).
`git ls-remote --heads origin` verified that published commit. With user approval,
`git merge --ff-only abe7dd5f8b3e88dcace420cb3874ca29382f8293` advanced `android`
from the old scaffold; it created no commit. The earlier missing-baseline blocker
is resolved.

All B source changes are in `app/src/main/java/org/ghost/simulation/`, with tests
in `app/src/test/java/org/ghost/simulation/`. Shared interfaces, models, fixtures,
build configuration, manifest, app composition and consolidated checkpoint status
are unchanged from A's baseline. No staging, new commits or pushes were performed.
See [A's baseline](../../CP01_BASELINE.md) and [role ownership](../../BUILD_PHASES.md).

## Components and behavior

| File | Responsibility |
| --- | --- |
| `FakeClock.kt` | Explicit forward-only fixture time, observable through StateFlow; rejects negative time and overflow. |
| `Cp01Fixtures.kt` | Typed copies of A's SOS/helper/offer/wire/receipt fixtures, checked against the shared JSON in tests. Binary values remain unsigned placeholders. |
| `FakePublications.kt` | Simulation-only pending records, deterministic name-based UUIDs and defensive list snapshots. |
| `FakeSosRepository.kt` | Empty initial Flow, ordered local publication queue and OPEN request projection; identical creates return the original queued result, conflicting creates fail. |
| `FakeHelperOfferRepository.kt` | Independent helper/offer queues, explicit preview associations and clock-driven offer expiry; no allocation or inferred matching. |
| `FakeCloudCoordinationRepository.kt` | Initially NOT_CONNECTED; only explicit fixture changes update Mission state, with a `Simulated:` message prefix. |
| `FakeTransport.kt` | Start/stop, explicit peer connection/disconnection, permission failure, FIFO send outcomes and explicit receive-ID events. Records unchanged packet values and injected time. |
| `FakeCloudClient.kt` | Offline by default; expected-request FIFO response scripts for upload/download/ack, failure recovery, batch retry cache and call history. |
| `Cp01Simulation.kt` | Fresh isolated component set and optional idempotent `seedLocalReports()`. Does not depend on the app composition package. |

Repositories have no transport or gateway references. Publishing never sends;
transport success never creates a receipt or changes a request; cloud responses
never clear local queues or invent coordination. Public/cloud consent is retained
in queued payloads. These fixtures do not implement production validation,
authorization, cloud policy, signatures or durable storage.

`simulation/` is the CP01 implementation boundary. Radio, mesh, protocol, security,
Room and real gateway implementation packages remain for their assigned later
checkpoints. No modules or dependencies were added.

## Wiring instructions for A

Create one simulation instance for the lifetime of the chosen app/test graph;
keep it outside recomposition and expose `simulation.label` in the fixture UI.
An A-owned factory can use the following:

```kotlin
import org.ghost.app.GhostAppDependencies
import org.ghost.app.GhostAppDependenciesFactory
import org.ghost.simulation.Cp01Simulation

val simulation = Cp01Simulation()
val factory = GhostAppDependenciesFactory {
    GhostAppDependencies(
        transport = simulation.transport,
        gatewayClient = simulation.cloudClient,
        sosRepository = simulation.sosRepository,
        helperOfferRepository = simulation.helperOfferRepository,
        cloudCoordinationRepository = simulation.cloudCoordinationRepository,
    )
}
```

In A's initialization coroutine, optionally call
`simulation.seedLocalReports()` before presenting a populated preview. Omit it
for empty screens. Repeat seeding preserves the original pending records and
timestamps. Create a new `Cp01Simulation()` to reset all fixture state; recreation
is intentionally not persistence. Components own no background jobs or timers.

Transport controls are suspend functions: call `start()`, then
`connect(PeerHandle(simulation.transport.id, "B"))`. Script failures with
`enqueueSendResult(SendResult(...))`; stopped/no-peer/permission-denied attempts
do not consume the next scripted send. Unscripted connected sends report only
`ACCEPTED_BY_TRANSPORT`. `receive(peer, packetId)` explicitly emits an observed ID.
Start event collectors before scripting. Events have no replay, peers have current
state, and a slow collector applies bounded-buffer backpressure. Run lifecycle
scripts sequentially for repeatable event order. Collectors can send replies;
event emission does not hold the transport state mutex.

Cloud controls are also suspend functions. Use `enqueueUploadResult(request,
result)`, `enqueueDownloadResult(cursor, limit, result)` and
`enqueueAcknowledgementResult(request, result)`, then set reachability to
`GatewayReachability.ONLINE`. Offline/authentication/unknown states fail without
consuming scripts. Unexpected or unscripted requests fail; failures can be
followed by explicitly scripted successes. Cancellation propagates.

Successful identical upload batches replay their original result. Reusing a
successful idempotency key with changed content fails. Download pages are static
fixture snapshots cached by `(cursor, limit)`; repeated downloads return the same
page, and acknowledgements do not advance cursors or prove phone receipt. Limits
follow A's 1..100 API bound. A new scenario is needed to replace an already cached
page. These client scripts are not origin-event deduplication or server policy.

For explicit Mission previews, call
`cloudCoordinationRepository.setState(CloudCoordinationState(...))`. There is no
automatic transition from upload to approval, receipt or allocation.

## Contract assumptions for A to review

- No clock boundary was supplied. `FakeClock` is a concrete simulation utility,
  injected into B's fakes; no shared interface was introduced.
- `DashboardSosSummary.receivedAtMs` is mandatory. For locally published fixture
  rows it means local receipt time, equal to `reportedAtMs`, **not cloud receipt**.
  The shared repository read type has no delivery/queue/simulation fields. A/D
  must retain the fixture label and avoid presenting that timestamp as cloud
  ingestion; a richer local read model is a future A-owned contract decision.
- `TransportEvent` carries no incoming bytes. `receive()` emits only the supplied
  ID; this does not implement packet decoding, signatures or repository ingestion.
- Request-based offer filtering is unspecified. `setOffersForRequest()` provides
  explicit preview links; unconfigured request IDs return no offers. A null filter
  returns all locally queued offers. These links do not assert a real match.
- Only create/publish commands exist. Same-ID/same-content publication is a
  retry; changed content with the same ID fails rather than silently acting as an
  update. Fake event UUIDs are derived from kind/owner/entity ID, not decoded from
  A's placeholder wire bytes. No signed event or upload pipeline is claimed.
- Offer-before-profile preserves the reported owner and quantity. The supplied
  interface has no helper read method, so no helper details or verified identity
  are fabricated. Clock expiry changes the visible offer to EXPIRED/stale while
  preserving the queued payload and original timestamp; it does not run a sender.

No contract changes are needed to compile this handoff. These limitations need
review when A integrates the UI and when later checkpoints add real behavior.

## Verification and remaining evidence

Executed with the repository environment:

```sh
source scripts/env.sh
./gradlew assembleDebug testDebugUnitTest --console=plain
```

Result: **BUILD SUCCESSFUL**. Final host suite: **34 tests, 0 failures, 0 errors,
0 skipped** (28 new simulation tests plus A's three contract and three existing
dependency checks). Tests cover shared fixture parity, deterministic clock/IDs,
queue/Flow updates, concurrent duplicate publication, conflicting creates,
consent retention, offer-before-profile and expiry, explicit offer links,
permission/peer lifecycle, live receive events, event-collector reply backpressure,
scripted cloud failures/retries/cursors/acks, cancellation and instance isolation.

Artifacts: `app/build/outputs/apk/debug/app-debug.apk`,
`app/build/reports/tests/testDebugUnitTest/index.html` and JUnit XML in
`app/build/test-results/testDebugUnitTest/`. Link/whitespace and B-only ownership
checks were also run. The APK still uses A's existing scaffold activity; A owns
factory/navigation integration after the user merges B/C/D.

No emulator, physical-phone, browser, real radio, Room-reopen, cloud-service or
model evidence was produced. Fake tests do not satisfy physical/durable/cloud
[TEST_MATRIX.md](../../TEST_MATRIX.md) gates. A's combined Android/server/UI
integration and consolidated CP01 status remain pending.
