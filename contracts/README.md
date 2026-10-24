# GHOST contracts v1

This directory is owned by the CP01 contract/integration role. It is the
provider-independent source of truth for the SOS phase.

## Files

- `proto/ghost_wire_v1.proto` — immutable packet and relay framing.
- `proto/ghost_events_v1.proto` — signed SOS/helper/offer/cloud/coordination/
  receipt payloads.
- `api/cloud-api-v1.yaml` — authenticated dashboard and gateway API boundary.
- `fixtures/cp01-fixtures.json` — deterministic examples shared by Android,
  server, and dashboard tests.

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
semantic contract for Android callers. They do not replace CP02 signature,
encoding, or validation code.
