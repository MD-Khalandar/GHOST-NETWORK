# CP02 — identity and trust

24 October 2026. Status: **implemented / physical evidence pending**.
All roles were implemented sequentially on `main` at the user's request.

## Implemented

- Generated Android Java lite protobuf classes from the shared schemas. Fixed
  colliding event enum symbols with prefixes; field numbers and numeric values
  are unchanged. Existing Android dependency/toolchain pins remain unchanged;
  protobuf build plugin 0.10.0 adds generation for the existing protobuf runtime.
- P-256 identities, SHA-256 NodeIds, domain-separated ECDSA signatures, exact-byte
  forwarding representation, cloud public-key pinning and bounded validation of
  all 12 application event payloads. Unknown ownership fails closed.
- Persistent Android Keystore alias, no private-key export, explicit unavailable
  state/retry and public identity diagnostics. Hardware backing is not claimed.
- Independent consent controls, initially off, with relay-visibility disclosure.
  These controls affect a non-sending preview; durable forms arrive with local SOS.
- Server P-256 signing primitives, safe configuration validation, hashed bearer
  credentials, session introspection and gateway/viewer/operator role isolation.
- Authenticated upload trust checks; valid persistent operations return 501.
  No database/approval/ingestion success is fabricated. Unsigned receipt payloads
  are rejected; the legacy receipt list is empty in the shared upload example.
- [Normative trust contract](../../../contracts/TRUST_V1.md), shared test-only
  identities/signatures and matching rejection codes on both platforms.

## Verification scope

Android command: `./gradlew assembleDebug testDebugUnitTest`. Tests exercise the
12 Node-generated signatures, canonical payload/envelope/wire bytes, host signing,
tampering/key mismatch, expiry/version/size/hops, cloud pin, owner/version/receipt
authority, consent/revocation, navigation and separate consent UI states.

Server commands: `npm --prefix server test`, `npm --prefix server run build`,
fake-config smoke, then `npm --prefix server run verify:android` after Android
tests. The last command verifies the actual JCA host-generated signature artifact.
Server tests cover all protected roles/routes, forged credentials/origin, revoked
consent, unsigned receipts and the same shared protocol fixtures.

These are host checks. They cannot establish Android Keystore persistence,
hardware security, Nearby radio delivery, actual cloud inference or browser UX.
Executed on Windows using the temporary API 36 SDK/build tools and the existing
JDK 25; the normal JDK 21 setup and repository version pins are unchanged.

| Check | Result |
| --- | --- |
| Android `assembleDebug testDebugUnitTest` | PASS: APK built, 53 host tests, zero failures/errors/skips |
| Server `test` | PASS: 19 tests, zero failures/skips |
| Server `run build` | PASS: TypeScript compilation |
| `verify:android` logic via compiled `verify-android.js` | PASS: actual host-JCA signature accepted by Node |
| Fake-config compiled smoke | PASS: health 200; database `not_checked`, provider configuration only |
| Local links, dependency-lock consistency and `git diff --check` | PASS |

The relevant T07/T08/A10 host checks do not complete the full acceptance gates.
No physical TEST_MATRIX gate is marked complete by this file. APK:
`app/build/outputs/apk/debug/app-debug.apk`; Android test results:
`app/build/test-results/testDebugUnitTest/`. Temporary tools/caches stay ignored.

## Handset checks still required

1. Build/install the debug APK on a physical phone using the normal environment
   guide. Open Network and record the public NodeId and key availability state.
2. Relaunch, force-stop/reopen and reboot. Confirm the same NodeId loads without
   changing app data. Do not log/export a private key or call this hardware-backed
   merely because the key loads.
3. Record phone model/Android version and observed key-loading failures. A failed
   existing identity must show unavailable, not silently generate a replacement.
4. Verify the SOS preview starts with both consents off and lets them change
   independently. Close/reopen starts a new non-sending preview. Check TalkBack,
   200% text and system-bar/keyboard behavior on the actual handset.
5. With an independently configured server public key, build with
   `-PghostCloudPublicKey=<base64url-SPKI>`. Network should show the pinned public
   fingerprint. Empty/invalid configuration must remain untrusted. Do not use the
   public fixture private keys as deployment credentials.

Missing: physical key persistence/compatibility observations (CP02 completion),
real transport/gateway receipts, database persistence, operator browser sessions,
deployment and real model/tool execution. Durable local SOS is the next feature
step after this trust foundation; CP03 is not implemented here.
