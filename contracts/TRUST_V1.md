# GHOST v1 identity and trust

CP02 implements this contract on Android and Node. Persistence, radio pairing,
cloud ingestion and resource transactions follow in later steps.

## Signing and bytes

- One P-256 (`secp256r1` / `prime256v1`) identity per installation. Android stores
  its private key under `ghost.identity.p256.v1` in Android Keystore; no export or
  automatic replacement of an unusable existing key. Hardware backing is device
  dependent and needs physical observation. Reinstall/data clearing changes identity.
- Public keys are canonical 91-byte DER SubjectPublicKeyInfo. NodeId is the full
  lowercase hex SHA-256 of those bytes; the protobuf representation is the raw
  32-byte digest. It is independent of radio endpoint handles and API roles.
- Sign UTF-8 `GHOST-WIRE-V1` followed by a NUL byte and the exact immutable
  protobuf envelope bytes, using SHA-256 with ECDSA. Signatures are ASN.1 DER,
  8–72 bytes. ECDSA signatures are randomized; compare verification, not signature
  equality. This provides integrity, not confidentiality.
- Origins encode fields in protobuf field order and elide proto3 scalar defaults;
  preserve `optional` presence, including explicit false/zero. Verify the exact
  received envelope bytes. Relays change only relay metadata, never signed bytes.
- Binary values in JSON are canonical base64url without padding. Application IDs
  are lowercase UUIDs (versions 1–5, RFC variant); node IDs are 64 lowercase hex digits.

The existing top-level event enum symbols collided (`WATER`, `AVAILABLE`, etc.).
CP02 prefixes event-payload enum names to make protoc generation valid. All field
numbers and numeric enum values are preserved; Kotlin/API semantic names stay the same.

## Bounds and authority

| Item | Limit |
| --- | --- |
| Binary packet / immutable envelope / payload | 32,768 / 24,576 / 16,384 bytes |
| Relay budget | `remaining_hops + hop_count = 4`; each 0–4; previous NodeId required after the first hop |
| Time | Positive Unix milliseconds within JavaScript safe integers; lifetime at most 24 hours; future creation skew at most 5 minutes; reject at expiry |
| Text | SOS description 2,000; explanation 1,000; notes/reasons 500; area/landmark 120; display name 80; unit 40 UTF-16 code units |
| People / quantity / proposal version | 1–1,000 / 0–1,000,000 / 1–2,147,483,647 |
| Upload | 1–50 packets; 1–128-character idempotency key; 2,400,000-byte JSON body cap |

All 12 application event types have payload validation. `PEER_HELLO` and
`INVENTORY` remain transport work; the application validator rejects them.
Mutable hop metadata limits honest forwarding; it is not cryptographic proof
against a malicious relay resetting its metadata.

Verify the public-key/NodeId binding and signature before actor checks. Creation
requires public consent; helper/offer payload owners must equal the signer.
Updates require verified existing ownership. Accept/decline requires the current
proposal/version's offer owner; receipt validation requires the original addressed
recipient, never merely the gateway. Unknown context fails closed. Authority lookup
providers must use verified state, and aggregate current request/offer cloud consent
for proposals/receipts. They must not trust owner IDs or consent supplied by a gateway.

Only the pinned cloud identity can issue addressed ACK/proposal/confirmation events.
Cloud-origin events cannot be uploaded as handset reports. Configure the Android
public pin using `-PghostCloudPublicKey=<base64url-SPKI>`; the default is unconfigured
and rejects cloud results. No fixture key is pinned automatically. Revocation/key
rotation of a deployment pin needs a separately authorized configuration update.

Public sharing and cloud consent default off independently. Uploads require cloud
permission. Minimal signed owner revocations may upload without new note/profile/
supply content. Those control packets require canonical known-field envelope and
payload encodings, so unknown protobuf fields cannot hide new report content.
Normal updates with no new consent use current verified consent.
The legacy upload `receipts` field must be empty: send receipts as signed
`DELIVERY_RECEIPT` packets in `packets`. Bare receipt payloads cannot prove delivery.

Stable packet rejection codes are the `rejection_codes` in
[`cp02-signatures.json`](fixtures/cp02-signatures.json), mirrored and tested on both
platforms. Rejections expose a code, not private report text or key material.

## API roles

`GHOST_AUTH_PRINCIPALS` contains distinct `{id, role, token_sha256}` records;
gateway records additionally require `gateway_node_id`. Generate random bearer
tokens with at least 32 random bytes and store only their SHA-256 digests in server
configuration. Supply tokens only over HTTPS; revoke by removing/changing the
configured record and restarting. These are separate from event-signing keys.

| Role | Allowed API scope |
| --- | --- |
| gateway | Sync upload/download/download-ack; its assigned gateway identity conveys no owner/operator authority |
| viewer | Dashboard reads |
| operator | Dashboard reads and exact-version proposal decisions |

`GET /v1/auth/session` returns only the authenticated principal's ID, role and
optional gateway NodeId. Missing/invalid credentials return 401; wrong roles return
403. A submitted role field/local phone switch cannot change authority.
Production configuration requires principals and a valid server-side P-256 key in
`GHOST_CLOUD_SIGNING_KEY_PEM`. Keep signing keys/provider credentials off phones,
frontend bundles and logs. Use `SigningIdentity` / `signCloudPacket` server-side;
there is no public signing endpoint.

In CP02, authorized persistent operations return 501 `NOT_IMPLEMENTED`; upload
first validates the signed reports. This cannot be treated as cloud acceptance,
an approval, an allocation, or a phone receipt. HTTP transactions and deduplication
must be implemented before those endpoints can acknowledge success.

[`cp02-signatures.json`](fixtures/cp02-signatures.json) contains **public test-only
private keys**, 12 signed events and binary encodings. Never use those keys for a
deployed phone/server. CP01 fixtures remain semantic/fake examples, not valid signatures.
