# CP01 role A baseline

Status: **reviewable contract baseline; not a completed checkpoint**.

Historical A preparation record. The subsequent A/B/C/D integration and CP01
completion evidence are in [the integration report](evidence/cp01/integration.md).
Integration corrected the API document's top-level `tags` placement; endpoint,
schema and Kotlin boundary semantics are unchanged.

This document records the decisions and owned files that B, C, and D must use
after the user commits this baseline. The user owns staging, commit, push, and
merge. A must not mark CP01 complete until the user-merged B/C/D changes have
been integrated and the combined smoke checks have been run.

## Stack decisions

These choices are frozen for the SOS phase unless the contract/integration
owner publishes an explicit replacement and compatibility fixtures.

| Area | Decision | Boundary rule |
| --- | --- | --- |
| Cloud service | Node.js + TypeScript + Fastify | The server exposes JSON/HTTPS APIs only; provider and database clients stay behind server interfaces. |
| Cloud database | PostgreSQL 16, relational, migration-controlled | The server owns accepted shared state; phones continue to use Room locally. |
| Dashboard | React + TypeScript + Vite | Browser code calls authenticated server APIs only; it never connects to PostgreSQL or a model provider. |
| Model provider | OpenAI Responses API through a server-side `ModelProvider` adapter | The model ID is deployment configuration (`GHOST_MODEL`), not part of Android or wire contracts. No provider call is made in CP01. |
| Deployment target | A single Linux x86_64 host running Docker Compose with HTTPS termination, the server, PostgreSQL, and the dashboard | Local development uses the same Compose topology. No vendor-specific SDK or cloud secret is required by the contract. |
| Server test command | `npm --prefix server test` | C must provide this script in `server/package.json`; it must run deterministic unit/fixture tests without network access. |
| Server smoke command | `npm --prefix server run smoke` | C must provide a health/config smoke check; missing configuration must fail clearly. |
| Dashboard test command | `npm --prefix dashboard test` | D must provide this script; fixture/browser checks must not be presented as live cloud evidence. |

The deployment target is intentionally container-based rather than tied to a
particular hosted vendor. The OpenAI provider is the only external runtime
dependency selected for the coordinator, and its credentials remain in the
server environment.

## Contract versions and encoding

- Contract family: `ghost.contracts.v1`.
- Stable public IDs are strings at the Kotlin/API boundary. `NodeId` is the
  lowercase hex SHA-256 of the public signing key; event, packet, request,
  helper, offer, proposal, approval, and allocation IDs are canonical UUID
  strings.
- The immutable origin envelope and event payloads are defined in
  `contracts/proto/*.proto`. CP01 freezes field names and field numbers; CP02
  owns generated-code/toolchain wiring, canonical signing bytes, and runtime
  validation.
- JSON fixtures use base64url-without-padding for binary values and
  milliseconds since Unix epoch for timestamps.
- Relays may change only `RelayMetadataV1`; the immutable envelope bytes and
  signature remain byte-for-byte unchanged.
- Public mesh consent and cloud-processing consent are independent. A report
  may be visible to peers without being uploaded to the cloud.

## Frozen event families

| Family | Signed payload | Authority |
| --- | --- | --- |
| SOS | `SOS_CREATE`, `SOS_UPDATE` | Requester NodeId; cloud may acknowledge but does not impersonate the requester. |
| Helper | `HELPER_PROFILE_PUBLISH`, `HELPER_PROFILE_UPDATE` | Helper owner NodeId. Profile data is self-reported, not a rescue credential. |
| Offer | `OFFER_PUBLISH`, `OFFER_UPDATE` | Offer owner NodeId; profile may arrive later, creating an identity-only placeholder. |
| Cloud | `CLOUD_ACK`, `MATCH_PROPOSED`, `MATCH_CONFIRMED` | Configured cloud signing identity only. |
| Coordination response | `MATCH_DECLINED`, `MATCH_ACCEPTED` | Referenced offer owner NodeId. Acceptance is not allocation until cloud confirmation. |
| Receipt | `DELIVERY_RECEIPT` | The recipient that persisted or explicitly read the packet. |

The complete payload definitions and API examples are in `contracts/` and are
covered by `ContractFixtureTest`.

## Frozen dashboard and gateway APIs

The normative API description is `contracts/api/cloud-api-v1.yaml`.

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/health` | none | Process/config health only; no database secrets or model details. |
| GET | `/v1/dashboard/sos` | viewer/operator | Bounded, cursor-based incoming SOS list after committed ingestion. |
| GET | `/v1/dashboard/sos/{requestId}` | viewer/operator | Request detail, related offers, proposals, receipts, and freshness. |
| GET | `/v1/dashboard/helpers` | viewer/operator | Bounded helper directory. |
| GET | `/v1/dashboard/offers` | viewer/operator | Bounded offer directory with quantity, unit, availability, and age. |
| GET | `/v1/dashboard/runs` | viewer/operator | Actual persisted coordinator run/tool trace, sanitized. |
| POST | `/v1/dashboard/proposals/{proposalId}/approve` | operator | Approve the exact proposal version after server revalidation. |
| POST | `/v1/dashboard/proposals/{proposalId}/reject` | operator | Reject the exact proposal version. |
| POST | `/v1/sync/upload` | gateway | Upload unchanged signed origin packets and phone receipts. |
| GET | `/v1/sync/download` | gateway | Download bounded, gateway-scoped cloud-signed packets by cursor. |
| POST | `/v1/sync/download-ack` | gateway | Acknowledge packets after durable local persistence, never as final phone delivery. |

Authentication is bearer-token based in the transport contract. The concrete
identity provider is a server deployment concern. Viewer tokens cannot approve;
operator approval is version-specific and idempotent.

## Exact ownership paths

These paths are the CP01 source-of-truth locations:

| Owner | Paths |
| --- | --- |
| A — contract/integration | `contracts/**`; `app/src/main/java/org/ghost/domain/model/**`; `app/src/main/java/org/ghost/transport/api/**`; `app/src/main/java/org/ghost/gateway/api/**`; `app/src/main/java/org/ghost/domain/repository/**`; `app/src/main/java/org/ghost/app/**`; root/Android build and manifest files; shared docs; cross-boundary tests. |
| B — Android core | `app/src/main/java/org/ghost/security/**`; `protocol/**`; `data/local/**`; `domain/usecase/**`; `mesh/**`; `transport/nearby/**`; gateway implementations; `simulation/**`; matching tests. |
| C — cloud/database | `server/**` except generated/shared contract artifacts; migrations; repositories; APIs; sync; model adapter; server tests. |
| D — Android UI/dashboard | `app/src/main/java/org/ghost/feature/**`; `design/**`; feature ViewModels/tests; `dashboard/**`; dashboard dependencies/tests. |

Generated files are regenerated from the A-owned contract files. B/C/D must
report required shared-file changes to A rather than editing shared contracts
or app composition independently.

## Android wiring hooks

The baseline exposes, but does not prematurely implement, these integration
points:

1. `GhostTransport` is the only transport-facing interface. `NearbyAdapter`
   must implement it; `MeshEngine` must depend on it rather than on Google
   Nearby classes.
2. `GatewayClient` owns authenticated upload/download/ack cursors. It accepts
   `WirePacketV1` and receipt batches, not model prompts or database handles.
3. `SosRepository`, `HelperOfferRepository`, and
   `CloudCoordinationRepository` expose typed `Flow` state and suspend
   commands. Room and transport implementations belong to B.
4. `GhostAppDependencies` is the factory seam for the activity/composition.
   A wires the real/fake graph only after the user merges the implementation
   branches.
5. `AppRoute` is the stable four-destination navigation contract:
   `HOME`, `REQUESTS`, `MISSION`, and `NETWORK`. D supplies stateless screen
   entry points; A owns the final navigation host.

## CP01 acceptance and handoff

This baseline is ready for review when:

- the proto/API contracts and fixtures are readable without server credentials;
- Android boundary types compile and their fixture tests pass;
- no Android version or existing toolchain pin changes;
- the user commits/publishes this baseline before B/C/D branch from it.

At this handoff, `server/` and `dashboard/` implementations do not exist yet;
their creation belongs to C and D from this baseline. Therefore the server and
dashboard commands below are defined contract commands, not claimed passing
results. CP01 also makes no real provider call.

Not claimed by this baseline:

- real signatures, protobuf generation, Room, Nearby, cloud persistence,
  provider inference, dashboard rendering, or device/cloud evidence;
- CP01 completion;
- any T/A/G/U/D acceptance gate.

After user-merged B/C/D changes, A will wire the final factories/navigation,
run `./gradlew assembleDebug testDebugUnitTest`, run
`npm --prefix server test` and `npm --prefix server run smoke` when C's scaffold
exists, record actual results, and stop for the next checkpoint decision.
