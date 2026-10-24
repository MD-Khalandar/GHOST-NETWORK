# CP01 role D — Android screens

Implemented on `cp01-ui` from A's published `abe7dd5` baseline, 24 October 2026.
The user requested D implementation and subsequent integration into `main`.

## Scope and entry points

- `design/GhostTheme.kt`: existing navy/teal/SOS-red/amber palette mapped to
  Material 3 light/dark tokens; normal text contrast checked numerically.
- `design/GhostComponents.kt`: scrollable bounded-width screen, status, loading,
  error/retry and simulated-data components.
- Stateless `HomeScreen`, `RequestsScreen`, `MissionScreen`, `NetworkScreen`
  under their `feature/` packages, each accepting `FoundationState` and callbacks.
- Home keeps Send SOS prominent; its callback can open `SosPreviewDialog`.
  Sending is explicitly unavailable in CP01 and never creates a report.
- `feature/foundation/FoundationViewModel.kt` combines A's five read boundaries;
  collection and retry do not publish, start transport, upload or run a model.
- `FoundationPreviews.kt` supplies D-owned loaded/empty/loading/error/stale,
  disconnected/connected and awaiting-operator fixtures, including small-screen,
  landscape and dark/200%-text preview configurations.

No shared contracts or B fakes were edited. Dashboard implementation starts at
CP04 under [BUILD_PHASES.md](../../BUILD_PHASES.md); no website was added in CP01.

## Integration instructions

A creates `FoundationViewModel` with the SOS, helper/offer and coordination
repositories plus transport/gateway boundaries from `GhostAppDependencies`.
Collect `state` with `collectAsStateWithLifecycle`, route A's `AppRoute` enum to
the four screen entry points, and pass `viewModel::retry` to recovery callbacks.
Wrap the host with `GhostTheme` and provide system-bar-safe content insets.
Keep selected route and preview-dialog state through recreation. Show the
simulation notice; never interpret local request receipt times as cloud receipt.

## Checks

`source scripts/env.sh` then `./gradlew assembleDebug testDebugUnitTest --console=plain`:
**PASS**, 11 tests (five new UI-state/contrast checks plus six baseline checks).
The first run exposed one amber foreground contrast shortfall and test dispatcher
cleanup ordering; both were corrected before the passing rerun.

The five new tests cover read-only collection, independent peer/cloud/Mission
state, retry after read failure, distinct status labels and >=4.5:1 light/dark
normal text color pairs. Compose previews compile; interactive rendering and
navigation checks are recorded separately in the integrated CP01 report.

Source checks: 48dp minimum interactive targets, heading semantics, text labels
for status, stable lazy-list keys, no clipped/ellipsized report text, and no
automatic sends from composition. No attached Android devices were reported by
`adb devices -l`. Physical TalkBack, system bars, device text scaling and radio/cloud
gates are not claimed. See [TEST_MATRIX.md](../../TEST_MATRIX.md).
