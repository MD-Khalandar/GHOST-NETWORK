---
name: ghost-ui-quality
description: Build or review GHOST Compose screens for accessible emergency UX, consent, honest offline delivery states, and Mission Control provenance. Use for feature UI and UX reviews.
---

# GHOST UI quality

Read `AGENTS.md`, `docs/UX_SPEC.md`, and the relevant UI in `ARCHITECTURE.md`. For delivery displays read `docs/PROTOCOL.md`; for Mission Control read `docs/AGENT_CONTRACT.md`. Inspect the current screen and its state producer before proposing UI changes.

## Check the complete interaction

Follow the task from screen entry through user action, confirmation, local persistence, and eventual result. Render explicit UI state and callbacks; keep radio APIs, database writes, and model execution outside composables. Use existing Material 3 tokens and the Home / Requests / Mission / Network navigation contract.

- Keep the Home SOS action prominent. Sending requires a preflight view of exact content, audience, and location granularity, with explicit public-sharing consent. Default to coarse location.
- Distinguish queued, neighbor send, relay receipt, signed recipient delivery, and optional read receipt with text and icons. A transport callback cannot produce a recipient-delivered badge.
- Show no peers, denied/revoked permissions, disconnected/retrying, expired/failed packets, stale data, empty results, and loading/error states where applicable. Give a meaningful recovery action.
- Display actual model readiness and actual validated tool observations. Mark fixtures as simulated and fallback as Rule-Based Assistance Only. Ordinary peers must not imply that they host an active coordinator model.
- An approval card shows the proposal version, offer provenance, freshness, uncertainty, and distinct approve/reject actions. Sending follows an explicit human action; re-rendering or entering the screen must not send.
- Disable duplicate submissions while processing, preserve typed forms, and expose recoverable failures without replacing the user's input.

## Make the screen usable under stress

Check 48dp minimum touch targets, TalkBack labels and traversal, logical focus, 200% font scale, small-screen layout, light/dark contrast, and reduced motion. Do not rely on color alone or clip critical SOS content. Keep the form and confirmation action usable with the keyboard open and system bars visible.

Provide fixture-backed `@Preview` states for loaded, empty, loading/error, and disconnected/stale variants. Fakes support development; they are not evidence of real delivery or inference. Collect repository state through lifecycle-aware ViewModel flows and use stable lazy-list keys.

For system-bar or keyboard overlap, read the installed `edge-to-edge/SKILL.md`. For observed performance problems, use `jetpack-compose-performance/SKILL.md` and its relevant references, checking the project's Compose version before adopting newer APIs. Do not expand a screen task into an application-wide optimization audit.

## Validate and report

Use previews and available Compose behavior checks to verify the changed interaction, especially consent, duplicate actions, state restoration, and failure recovery. Run the repository build/unit command when tooling is present. Record which accessibility/device checks were actually performed; a preview or screenshot alone does not prove TalkBack behavior or a real receipt.

For a review, report actionable findings with severity, file/line, trigger, user impact, and a focused fix. If evidence is unavailable, state what remains unverified. Map relevant checks to U01–U06 in `docs/TEST_MATRIX.md` without marking them complete without evidence.

Reference: [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility).
