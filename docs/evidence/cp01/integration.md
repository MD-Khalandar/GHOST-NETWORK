# CP01 integration — contracts and foundations

Date: 24 October 2026. Status: **complete for CP01's host/simulation scope**.
The user explicitly requested D implementation, completion of CP01 and merging
A/B/C/D into `main`, including a renewed merge instruction after the repository's
default user-only merge rule was explained. Work was prepared and tested on
`cp01-integration` before merging into `main`. No later checkpoint is started.
The user's final scope clarification leaves frontend design review to the user;
no further visual design work is part of this integration verification.

## Integrated inputs

| Role | Input | Result |
| --- | --- | --- |
| A | Published `contract`, `abe7dd5` | Typed Kotlin, proto/API contracts, fixtures, stack and composition seam |
| B | Published `android`, `c666ba4` | Deterministic in-memory clock, transport/cloud clients and repositories |
| C | Published `cloud`, `0bfcc73` | TypeScript/Fastify service, health/config checks, fake model provider |
| D | Local `cp01-ui`, `c63d9b2` | Stateless screens, Material 3 themes, previews and state/contrast tests |

Read-only `git ls-remote --heads origin` verified A/B/C heads before integration.
C branched from the older scaffold; its changes only added `server/` and merged
without replacing A's contracts. B/C were merged into the D-based integration
branch without conflicts. Original feature branch history is preserved.

## Integrated behavior

The launcher uses A's `GhostAppDependenciesFactory` to inject B's `Cp01Simulation`
into D's `FoundationViewModel`. The graph survives activity recreation and seeds
one SOS, helper and offer exactly once per graph. Process death resets these
memory-only fixtures. Reads use lifecycle-aware state collection; navigation
does not publish, start discovery, upload, approve or run a model.

Four destinations are wired: Home, Requests, Mission, Network. Home makes Send
SOS prominent and opens an explicit non-sending preview with a close action.
Request timestamps are labelled as reported/local information, never cloud
receipt. Mission keeps cloud reachability, approval, phone receipt and allocation
distinct. Gateway sharing stays off. Every screen labels the simulated data.

Navigation and preview-dialog state restore after recreation. Material 3 light
and dark themes retain the existing palette, with a corrected high-contrast
amber foreground. A 2-by-2 tab layout at >=150% font scale keeps long labels
readable. The scroll viewport is bounded between app bars so focused controls
cannot scroll beneath opaque bars. This deliberately uses Scaffold padding on
the viewport rather than allowing content to scroll behind those bars. Long
dialog text scrolls while its close action stays reachable.

## Integration fixes and contract checks

- Moved API tag declarations out of the top-level `security` list into `tags`.
  The previous shape incorrectly treated `name` as a security scheme. No endpoint,
  payload, ID or boundary signature was changed.
- Added `scripts/check-cp01-contracts.rb`: Ruby standard-library parse, known
  security schemes/tags, unique operation IDs, internal reference resolution and
  full shared/server JSON equality. This is a structural/parity check, not a
  complete OpenAPI conformance validator.
- Added a server regression comparing its entire fixture copy and provider input
  to A's shared fixture, preventing silent cross-branch drift.
- Added test-only Robolectric 4.17 and Compose UI testing artifacts using the
  existing Compose BOM. Existing Android SDK, AGP, Kotlin and dependency pins are
  unchanged. Debug includes the Compose test activity; release does not.
- Fixed viewport occlusion, large-text nav wrapping and test harness issues
  discovered by host-rendered interaction checks. Removed unused setup strings.

## Executed checks

Environment: existing project JDK 21/Android SDK; Node v24.16.0, npm 11.13.0.
Initial sandbox attempts blocked Gradle/tsx sockets and npm DNS; approved retries
ran successfully outside the sandbox. These were environment failures, not passes.

| Command/check | Observed result |
| --- | --- |
| `source scripts/env.sh` then `./gradlew assembleDebug testDebugUnitTest lintDebug --console=plain` | PASS; 45 host tests, zero failures/errors/skips; lint zero errors |
| `npm --prefix server ci --cache .local/npm-cache --no-audit --no-fund` | PASS; installed 56 locked packages |
| `npm --prefix server test` | PASS; 9 tests including config, missing-config startup, fake provider, health and shared-fixture parity |
| `npm --prefix server run build` | PASS; TypeScript compilation |
| Fake-config `npm --prefix server run smoke` | PASS; health 200, database `not_checked`, model provider `configured` |
| Compiled server bound to temporary `127.0.0.1` port | PASS; actual HTTP health matches contract; unimplemented sync-upload route returns 404; server closed afterward |
| `ruby scripts/check-cp01-contracts.rb` | PASS; 11 operations, 106 resolved internal references, security/tags and shared fixture parity |
| `adb devices -l` | No devices attached |

The 45 Android checks comprise six original dependency/contract tests, 28 B
simulation tests, five D state/contrast tests, five rendered navigation/semantics
tests and one actual-launcher/recreation smoke test. Rendered checks cover four
destinations, non-sending SOS preview, 48dp controls, retry/empty state, saved-state
restoration, 200% text/dark mode and landscape scrolling. Tests use Robolectric
API 35; this is host evidence, not emulator/physical phone evidence.

Lint's remaining warnings concern retained SDK/dependency versions and existing
manifest backup/icon configuration. Version upgrades are outside CP01. There are
no suppressed failures or new UI lint warnings.

## Artifacts and running

- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Android tests: `app/build/reports/tests/testDebugUnitTest/index.html` and
  `app/build/test-results/testDebugUnitTest/`.
- Lint: `app/build/reports/lint-results-debug.html`.
- Reproducible host captures: `app/build/reports/cp01-ui/*-0.png`.
- Frontend design acceptance remains with the user. Host captures are local test
  artifacts, not a claim of design approval or physical-device acceptance.

Build/install the APK using the existing [environment guide](../../ENVIRONMENT_SETUP.md).
Open the app to navigate fixture screens; Send SOS does not send anything.
For the server, follow [server/README.md](../../../server/README.md). Smoke command:

```sh
GHOST_ENV=test \
GHOST_DATABASE_URL=postgresql://localhost:5432/ghost \
GHOST_MODEL_PROVIDER=fake \
GHOST_MODEL=cp01-fake \
npm --prefix server run smoke
```

## Acceptance limits

CP01 requires foundation contracts, deterministic fixtures, server/Android smoke
checks and four UI shells. Those are complete. Dashboard implementation begins
at CP04; there is no dashboard package or browser result to claim here.

No physical TalkBack, handset system-bar behavior, radio exchange, signing,
Room/database reopening, real cloud/provider, operator approval or receipt tests
were performed. All corresponding [TEST_MATRIX.md](../../TEST_MATRIX.md) gates
remain open. The CP01 host checks do not pass those later gates. PostgreSQL,
Docker/HTTPS deployment, live SOS sending and coordination are still unimplemented.

CP02 is not started. Its next role is A's identity/signing contract preparation,
after the team's checkpoint publication/advance process.
