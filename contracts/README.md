# GHOST contracts v1

This directory is the provider-independent source of truth for the SOS phase.
Shared changes belong to the contract/integration owner, including when one
implementer performs all roles on `main`.

## Files

- `TRUST_V1.md` — implemented CP02 signing, authority, bounds and authentication rules.
- `proto/ghost_wire_v1.proto` — immutable packet and relay framing.
- `proto/ghost_events_v1.proto` — signed SOS/helper/offer/cloud/coordination/
  receipt payloads.
- `api/cloud-api-v1.yaml` — authenticated dashboard and gateway API boundary.
- `fixtures/cp01-fixtures.json` — deterministic examples shared by Android,
  server, and dashboard tests.
- `fixtures/cp02-signatures.json` — real cross-platform signatures and binary
  encodings using public test-only identities.

## Rules

1. Do not edit generated client/server code by hand. Regenerate it from these
   files when a later checkpoint adds code generation.
2. Preserve immutable origin bytes and signatures through relays and cloud
   synchronization. Only relay metadata is mutable.
3. Keep public mesh visibility separate from cloud-processing consent.
4. Treat all text, IDs, model output, and dashboard input as untrusted until
   the owning policy validates it.
5. A dashboard approval is valid only for the exact proposal ID and version;
   the server revalidates current request/offer state before publication.
6. A sync acknowledgement is not a phone recipient receipt, and a relay send
   is not cloud acceptance.

The Kotlin types under `app/src/main/java/org/ghost/domain/model` mirror the
semantic contract for Android callers. Android generates Java lite protobuf
classes during its build; server codecs load these same schemas. Security and
protocol implementations validate exact origin bytes before any future projection.
