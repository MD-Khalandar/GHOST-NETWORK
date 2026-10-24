# SOS event and synchronization contract

Planned contract only; no wire/API implementation exists. Checkpoint 1 freezes shared schemas/interfaces and fixtures; checkpoint 2 implements signatures/validation. The CP01 source of truth is [`contracts/`](../contracts/README.md), including the proto field numbers, API paths, and deterministic examples. Ordinary chat, generic information requests and local inference protocols are deferred.

## Immutable packets

`NodeId` is the full SHA-256 of the identity public key in the agreed encoding, independent of Nearby's temporary handle. The cloud also has a trusted signing identity, pinned by clients. Preserve exact origin bytes/signature through relays and cloud upload.

```text
WirePacketV1 {
  immutable_envelope_bytes, signature,
  relay { remaining_hops, hop_count, previous_node_id }
}
ImmutableEnvelopeV1 {
  version, packet_id: UUID, event_id: UUID,
  sender_node_id, sender_signing_public_key,
  destination: BROADCAST | NODE:<NodeId>,
  kind, created_at_ms, expires_at_ms,
  priority: ROUTINE | IMPORTANT | SOS,
  payload_format: PROTOBUF, payload_bytes
}
```

A signature proves identity/integrity, not actor authorization or confidentiality. Freeze protobuf field numbers, canonical key encoding, update ordering, payload/inventory/queue limits, TTL defaults and test vectors before parallel feature work.

## Active event types

| Kind | Payload / authority |
| --- | --- |
| `SOS_CREATE` | `request_id, category, description, people_count?, coarse_area?, landmark?, cloud_processing_allowed`; requester-signed public mesh report |
| `SOS_UPDATE` | `request_id, state, note?, cloud_processing_allowed?`; requester-signed update/closure |
| `HELPER_PROFILE_PUBLISH` | `display_name?, coarse_area?, help_types, availability, cloud_processing_allowed`; profile identity is the envelope's signer NodeId |
| `HELPER_PROFILE_UPDATE` | Owner-signed changes to the same profile fields; field presence/update ordering follows the shared contract |
| `OFFER_PUBLISH` | `offer_id, resource_type, quantity?, unit?, coarse_area?, available_until?, cloud_processing_allowed`; owner-signed report |
| `OFFER_UPDATE` | `offer_id, state, quantity?, unit?, coarse_area?, available_until?, note?, cloud_processing_allowed?`; owner only; supplied fields update that version |
| `CLOUD_ACK` | `origin_event_id, status, cloud_revision, received_at_ms, error_code?`; cloud-signed durable acceptance/rejection, never an assistance claim |
| `MATCH_PROPOSED` | `proposal_id, version, request_id, offer_id, explanation, approval_id`; cloud-signed after authenticated operator approval |
| `MATCH_DECLINED` | `proposal_id, version, offer_owner_node_id, reason?`; referenced owner only |
| `MATCH_ACCEPTED` | `proposal_id, version, offer_owner_node_id, note?`; owner requests acceptance; not final allocation |
| `MATCH_CONFIRMED` | `proposal_id, version, request_id, offer_id, allocation_id`; cloud-signed after transactional availability check |
| `DELIVERY_RECEIPT` | `packet_id, recipient_node_id, receipt_type, observed_at_ms`; actual recipient after persistence or explicit reading |
| `PEER_HELLO`, `INVENTORY` | Neighbor identity/version/gateway capability and bounded missing-packet synchronization; not forwarded feature events |

SOS/helper profiles/offers are public broadcasts after consent. Offers link to their signer/owner's helper record; receiving an offer before the profile creates an identity-only placeholder. Decline/acceptance can propagate as public coordination updates and upload through eligible gateways. Cloud acknowledgements/results target the affected requester/owner identities. When the same decision must reach both, create separate addressed packets linked to one proposal/version; duplicate notification is not a second allocation.

Only the configured cloud identity may publish authoritative cloud events. A handset cannot self-select as coordinator. Cloud permission defaults off and is independent of public mesh visibility. Gateways send only authorized reports; minimal consent-revocation control updates can reach the cloud without forwarding new report content.

## Cloud synchronization boundary

- Authenticated HTTPS uploads carry batches of unchanged origin packets and phone receipts. A connected originating phone uses the same API directly. Server verifies signer/key binding, authority, consent, version/size/expiry and idempotency before persistence.
- Cloud acceptance means event/projection and any job trigger are committed together. Duplicate upload via multiple gateways returns the same durable result; it cannot start another logical job.
- The cloud signs results, keeps a durable return outbox and exposes bounded cursor-based downloads scoped to authorized gateway/request associations. Untrusted requested node IDs alone cannot grant access to someone else's results.
- A gateway persists returned packets/outbox before advancing its cursor, then uses normal mesh forwarding. A gateway download acknowledgement does not replace the destination phone's signed receipt.
- Operator approvals use a separate authenticated role-checked API and exact proposal version. Provider prompts, model tool calls and credentials never traverse this public mesh protocol.
- The web dashboard uses bounded authenticated SOS/detail/helper/offer/run read APIs and the same approval API. Reads reflect committed server records, expose freshness and enforce operator/viewer scope; dashboard code cannot directly mutate owner profiles or database tables.
- Freeze endpoint paths, authentication, error types, event-to-job keys and cursor/ack semantics in shared API fixtures at checkpoint 1. No raw `AI_INFERENCE` proxy is needed for this phase.

## Forwarding and honest status

Persist before sending. Authenticate direct pairing; validate bounds/version/signature/expiry/hops on receive; deduplicate event application and forwarding. Exclude the previous hop, preserve origin bytes and decrement the relay budget. Demo maximum: four hops. Stop at addressed destinations.

Use bounded inventory/retry/backoff/queues, persist dedup through restart, and never reset expiry or hop budgets. Missing permissions/radios and network loss are explicit states.

Keep these separate: `SAVED_LOCALLY`, `QUEUED`, `SENT_TO_NEIGHBOR`, `SEEN_BY_RELAY`, `ACCEPTED_BY_CLOUD`, `AWAITING_OPERATOR`, `PROPOSAL_SENT`, `RECEIVED_BY_PHONE`, `ALLOCATION_CONFIRMED`. Track network receipts and domain state separately; observations may arrive out of order. Broadcasts never claim delivery to everyone. Receipts are not recursively acknowledged.

## Change and test requirements

This revision replaces the planned phone-coordinator/hybrid-inference contract with cloud coordination. Remove requester-selected coordinator and raw gateway-inference events from active scope; add cloud acknowledgement/confirmation, trusted cloud signer and approval references. Keep proposal versions, owner authority and origin identity/signature semantics. No implemented data is migrated. CP01 freezes the semantic contract in `contracts/proto/ghost_events_v1.proto` and `contracts/proto/ghost_wire_v1.proto`; CP02 owns generated code, canonical bytes, signatures, and validation.

Dashboard/database extension: add owner-signed helper profile publish/update events, explicit supply quantity/unit/availability updates and authenticated read models for requests, helpers and supplies. Test profile ownership/consent, quantity/unit compatibility, offer-before-profile arrival, duplicate registration, dashboard authorization/pagination and agreement between dashboard records and agent tool results (D01–D04).

Required fixtures/tests: cross-client/server encoding/signatures; forged cloud/gateway identity; wrong recipient; unauthorized approval/owner mutation; stale consent/proposal; replay via two gateways; atomic acceptance/job creation; over-allocation; out-of-order results; restart/cursor recovery; expiry and signed final phone receipt. See T07–T08, A03/A09/A10 and G01–G04 in [TEST_MATRIX.md](TEST_MATRIX.md).
