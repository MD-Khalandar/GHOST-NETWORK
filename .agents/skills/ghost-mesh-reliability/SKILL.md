---
name: ghost-mesh-reliability
description: Implement or review GHOST Nearby SOS relay, cloud gateway sync, retries, deduplication and signed phone/cloud receipts.
---

# GHOST mesh reliability

Read `AGENTS.md`, `ARCHITECTURE.md`, `docs/PROTOCOL.md`, and relevant T/G cases in `docs/TEST_MATRIX.md`. Inspect transport/queue implementation. Owner A freezes shared contracts at checkpoint 1; route changes through that owner with fixtures.

## Preserve boundaries and packet meaning

- Nearby `P2P_CLUSTER` connects neighbors within radio range. GHOST's `MeshEngine` must implement store-and-forward; the SDK does not establish the demonstrated A→B→C path by itself.
- Keep Android/Nearby APIs in the adapter, routing in the engine, and verified persistence/projections behind repositories. The adapter must not edit request projections.
- Keep `NodeId` separate from ephemeral endpoint handles. Preserve exactly the origin immutable bytes and signature; only the separately encoded relay metadata may change.
- Validate bounds, version, signer identity, signature, replay/duplicates, expiry, and hop budget before projection or forwarding. Enforce owner authorization independently of a valid signature.
- Persist before sending. Keep the queue and deduplication state through process restart, with finite retention, size caps, backoff, and fairness. Do not reset hop budget when reconnecting or changing transport.
- Exchange bounded inventories and send missing valid packets. Exclude the previous hop as required by the protocol; prevent repeated callbacks or overlapping reconnect work from creating duplicate effects.
- A successful Nearby send establishes at most neighbor transmission. Require the protocol's signed end-recipient receipt before displaying delivered-to-recipient; relay acknowledgment and delivery proof are different observations.
- Preserve original signed SOS bytes through cloud upload and verify trusted cloud signatures on returned packets. Stop forwarding at addressed destinations; gateways cannot impersonate the coordinator.
- Distinguish neighbor/relay, durable cloud ACK and phone receipt. Gateway download ACK is not final delivery. Persist results before cursor advancement; test duplicates across multiple gateways and cursor/outbox recovery. Internet loss must not stop local SOS transport.

For packet/event/ID/shared-interface changes, show the concrete proposal and applicable fixture/compatibility tests. For docs-only changes, specify required tests without claiming a frozen or implemented format.

## Handle Android lifecycle and permissions

Check current [Nearby Android setup](https://developers.google.com/nearby/connections/android/get-started) against the actual target SDK and phones before selecting permission declarations and runtime requests. Surface denial, permanent denial, revocation, unavailable radio/Play services, disconnect, retry, and service termination. Re-check permission at use time and handle `SecurityException`; cached grant state is insufficient.

Stop discovery/listeners appropriately and make foreground-relay behavior explicit. Do not imply an Android process or foreground service is unstoppable. Keep packet contents and precise location out of diagnostic logs.

## Prove the changed failure mode

Use deterministic `FakeTransport`, fake clock, and fixtures to cover the relevant cases: A/C without a direct link, B offline then reconnecting, replay 10 times, expiry during a retry, bad signature/version/oversize/hops, bounded inventory, and unsigned or wrong-recipient receipt. Test durable queue/dedup recovery with storage reopening where applicable.

For application changes run the repository build/unit command. Describe simulations and physical evidence separately. Real relay needs phones with A/C unable to connect directly and correlated IDs on B/C. Do not infer global topology or mark device gates complete from fake/emulator results. Docs-only work does not require a build.

For reviews, report reproducible findings with file/line, failed invariant, impact, fix, and the test that demonstrates it.

Reference: [Nearby connection strategies](https://developers.google.com/nearby/connections/strategies).
