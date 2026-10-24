# Working on GHOST

Read [PROJECT.md](PROJECT.md) for the product, current state and next work.

- Work directly on `main`. Implement authorized work independently in small useful steps; no checkpoint or team-role process.
- Keep one Android app, one server and a small dashboard. Preserve the Android toolchain; add dependencies only when used.
- Keep signing, consent, ownership checks and honest delivery states. Provider credentials and cloud signing keys stay server-side; phone keys use Android Keystore. Never log secrets.
- Run affected tests and a build once per meaningful change. Keep tests for real behavior; skip fake-only coverage, screenshot harvesting and evidence reports.
- Update PROJECT.md when the product or implementation state changes. Keep documentation short.
- Commit/push when requested. Never force-push or discard unrelated work without permission.
