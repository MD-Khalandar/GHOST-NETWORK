# GHOST wire and domain protocol v1 — freeze before parallel coding

## Invariants

- Transport independent: every transport accepts raw encoded wire bytes; no transport-specific message IDs.
- **Signed immutable envelope** uses bytes that are created once and transmitted unchanged through relays. Sign exactly the immutable bytes, not JSON or a re-serialized parsed representation.
- Mutable hop metadata is transmitted separately; must not alter the signed envelope.
- `NodeId := SHA-256(public_signing_key)` or a consistent truncated/hash encoded representation, documented across peers.
- `PeerHandle` is a temporary transport session identifier, never used as stable user identity.
- Keep one global dedup cache across all transports; persist it at least as long as message TTL.
- Empty/malformed/oversize/expired/unrecognized version/bad signature/loop exhausted packets must be rejected before forwarding.

## Binary framing contract (conceptual)

```text
WirePacketV1 {
  immutable_envelope_bytes: bytes // created once at origin
  signature: bytes              // signed over immutable_envelope_bytes
  relay: { remaining_hops, hop_count, previous_node_id }
}

ImmutableEnvelopeV1 {
  version: 1
  packet_id: random UUID
  event_id: UUID
  sender_node_id: NodeId
  sender_signing_public_key: bytes
  destination: BROADCAST | NODE:<NodeId>
  kind: SOS_CREATE | SOS_UPDATE | OFFER_PUBLISH | OFFER_UPDATE |
        MATCH_PROPOSED | MATCH_DECLINED | MATCH_ACCEPTED |
        DELIVERY_RECEIPT | PEER_HELLO | INVENTORY
  created_at_ms: integer
  expires_at_ms: integer
  priority: ROUTINE | IMPORTANT | SOS
  payload_format: PROTOBUF
  payload_bytes: bytes
}
```

**Encoding choice:** Protocol Buffers for the first implementation, with agreed `.proto` files, stable field numbers and fixture test vectors. The mutable relay section must remain outside the signed bytes. Encode the origin immutable section once. Unchanged bytes remove the need for relying on accidental canonical serializer behavior at relay nodes.

## Core domain events

```text
SOS_CREATE {request_id, category, people_count?, description, coarse_area?, landmark?, contact_node_id}
SOS_UPDATE {request_id, state, note?} // only requester or authorized coordinator per policy
OFFER_PUBLISH {offer_id, resource_type, quantity?, coarse_area?, available_until?}
OFFER_UPDATE {offer_id, state, note?} // only offer owner
MATCH_PROPOSED {proposal_id, request_id, offer_id, coordinator_node_id, explanation}
MATCH_DECLINED {proposal_id, offer_owner_node_id, reason?}
MATCH_ACCEPTED {proposal_id, offer_owner_node_id, note?}
DELIVERY_RECEIPT {packet_id, recipient_node_id, receipt_type, observed_at_ms}
```

Fields containing private location are optional, consent-based and coarse by default. All event types require valid signer identity. Relay nodes may read public SOS but never mutate origin content.

## Projection rules

- SOS requester can update or close their request; an explicitly authorized coordinator can mark *acknowledged* but must not unilaterally claim physical resolution.
- Resource owner alone can publish, reduce availability, accept or decline. AI proposals do not reserve inventory until owner confirms.
- Offer collisions: do not treat one proposal as an irrevocable resource allocation; show stale/unconfirmed.
- Resource matching is advisory, with provenance and last-updated age visible to users.

## Sender-to-recipient proof

`QUEUED` → `SENT_TO_NEIGHBOR` → `SEEN_BY_RELAY` → `DELIVERED_TO_RECIPIENT` → optional `READ_BY_RECIPIENT`. EXPIRED/FAILED are separate terminal/problem states. The transition to DELIVERED requires a signed end-recipient receipt, not a Nearby send success or a LoRa hop ACK.

## Networking v1

1. Persist outgoing immutable packet + relay budget before attempting transport.
2. On authenticated peer connection exchange `PEER_HELLO`: protocol version, capability flags, stable key fingerprint.
3. Exchange bounded message inventories (IDs); request missing packets; batch sends.
4. On receive: validate limits, version, signature, replay/duplicate, TTL, hop budget; Room insert transaction.
5. Notify local projections; if relay eligible, decrement hop budget and enqueue for other peers excluding previous hop.
6. Backoff and queue with size caps and fairness; do not starve all non-SOS traffic.
7. On process restart recover queued packets and seen IDs; on reconnection resynchronize.

Default max hops: 4 for demo (tunable, TTL always enforced). Never reset hops across transports. No stable global route table promised.

## Cryptography and trust

Use a maintained signature library, with private keys protected using a tested Android Keystore-backed scheme supported by demo phones. Don't make unsupported assumptions about Ed25519 generation directly inside every Keystore version. Bluetooth/Nearby authenticated pairing and signed application messages are different security layers. Do not label application E2E encrypted unless independently built and tested.
