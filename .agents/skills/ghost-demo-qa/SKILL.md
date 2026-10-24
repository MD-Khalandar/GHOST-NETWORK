---
name: ghost-demo-qa
description: Run GHOST integration, release-readiness, and offline three-phone demo checks with evidence against T01–T13, A01–A08, and U01–U06. Use for end-to-end QA and demo dry runs.
---

# GHOST demo QA

Read `AGENTS.md`, `docs/TEST_MATRIX.md`, and `docs/BUILD_PHASES.md`. Consult `ARCHITECTURE.md`, `docs/PROTOCOL.md`, `docs/AGENT_CONTRACT.md`, and `docs/UX_SPEC.md` for the gates being tested. Inspect the current app and existing evidence before assuming any feature is implemented.

## Establish what can actually be checked

Run `scripts/doctor.sh` when environment diagnosis is needed. Use `source scripts/env.sh` before Gradle/ADB commands so Java, Android SDK, and caches use the repository setup. Run `./gradlew assembleDebug testDebugUnitTest` when tooling is present. Record exact commands, exit status, test counts/failures, and APK/report locations; a successful build is not a device test.

List available devices with their roles and distinguish physical phones from emulators. When multiple devices are connected, target device operations with `adb -s <serial>`. Follow existing device authorization; do not perform destructive resets or erase app data as a routine check. Missing devices/model/radios leave the corresponding gates NOT RUN, with a concrete next step.

Keep observed PASS, FAIL, NOT RUN, and BLOCKED separate for each relevant matrix ID. Never fill evidence gaps with fixtures, screenshots of simulated traces, or expected results. Preserve the existing test matrix as a specification; store new evidence in a task-specific report only when a report is requested or needed for the QA task.

## Exercise the mandatory demo in gate order

1. Confirm the build launches, required permissions are handled, and Phone C actually loads its local model. Record handset/model identity and model hash/version. Pre-provision the model before disconnecting internet.
2. With internet/mobile data disabled and required local Bluetooth/Wi-Fi radios enabled, demonstrate A→B direct signed-event transport, then A→B→C with A/C unable to connect directly. Correlate packet/event IDs and relay logs.
3. Exercise replay/dedup, queue recovery, B disconnect/reconnect, expiry/invalid packets, owner authorization, and honest receipt transitions. Include process restart where the matrix requires it.
4. Publish genuine test offers from B and C, send A's SOS, observe actual model output selecting a tool, and inspect Kotlin execution and the draft proposal. Confirm that no offer is sent before human approval.
5. Approve the initial proposal, have B's owner decline, and capture the signed decline and a distinct actual model run proposing C's existing alternate offer. Human approval precedes the new publication. Show A's signed recipient receipt returning through B.
6. Check permission denial, no peers, no model/model failure, stale offers, and visible queue/expiry states. Check consent, 200% font scale, TalkBack, small-screen/dark mode, and real Mission Control provenance.
7. Repeat the fully offline flow three times before calling T13 complete. Capture the run evidence rather than inferring reliability from one success.

The five-minute script and exact required evidence are in `docs/TEST_MATRIX.md`. Prioritize failures in mandatory transport/model/approval paths over optional LoRa, gateway, visual polish, or new features.

## Report readiness honestly

Report build/unit results, physical checks, matrix IDs with evidence, failed gates with reproduction steps, and the next blocking integration issue. Distinguish emulator checks, fake transport/model tests, and physical proof. Keep private SOS text/location and raw personal identifiers out of shared logs/screenshots; use clearly identified test fixtures.

No E2E encryption, recipient delivery, automatic multihop, offline LLM tool execution, or LoRa over-the-air claim is established without the corresponding implementation and evidence. Use the relevant GHOST review skill to diagnose failures; avoid changing shared contracts or adding optional integrations during a dry run unless the task includes that change.
