---
name: ghost-ui-quality
description: Build or review GHOST Compose SOS, cloud Mission Control and gateway screens for accessibility, consent and honest status.
---

# GHOST UI quality

Read `AGENTS.md`, `docs/UX_SPEC.md`, and the relevant UI in `ARCHITECTURE.md`. For delivery displays read `docs/PROTOCOL.md`; for Mission Control read `docs/AGENT_CONTRACT.md`. Inspect the current screen and its state producer before proposing UI changes.

## Check the complete interaction

Follow screen entry through user action, consent, persistence and result. Keep radio APIs, database writes and models outside composables. The SOS phase uses Home / Requests / Mission / Network; Mission displays cloud coordination, never an on-phone agent.

- Keep the Home SOS action prominent. Sending requires a preflight view of exact content, audience, and location granularity, with explicit public-sharing consent. Default to coarse location.
- Keep local SOS usable without internet/cloud. Ordinary chat is deferred. Preserve drafts and disclose relay visibility until E2E encryption is verified.
- Separate public mesh consent from owner cloud permission. Gateway sharing is opt-in; cloud unavailability differs from mesh disconnection. Show data source/age and actual cloud provenance.
- Distinguish queued, neighbor send, relay receipt, signed recipient delivery, and optional read receipt with text and icons. A transport callback cannot produce a recipient-delivered badge.
- Show no peers, denied/revoked permissions, disconnected/retrying, expired/failed packets, stale data, empty results, and loading/error states where applicable. Give a meaningful recovery action.
- Phone Mission shows actual cloud tools/approval status; operator approval lives in the web dashboard. Mark fixtures simulated; no local-AI fallback. Cloud ACK, operator approval, phone receipt and allocation stay distinct; offline owner acceptance remains pending.
- An approval card shows the proposal version, offer provenance, freshness, uncertainty, and distinct approve/reject actions. Sending follows an explicit human action; re-rendering or entering the screen must not send.
- Disable duplicate submissions while processing, preserve typed forms, and expose recoverable failures without replacing the user's input.

## Make the screen usable under stress

Check 48dp minimum touch targets, TalkBack labels and traversal, logical focus, 200% font scale, small-screen layout, light/dark contrast, and reduced motion. Do not rely on color alone or clip critical SOS content. Keep the form and confirmation action usable with the keyboard open and system bars visible.

Provide fixture-backed `@Preview` states for loaded, empty, loading/error, and disconnected/stale variants. Fakes support development; they are not evidence of real delivery or inference. Collect repository state through lifecycle-aware ViewModel flows and use stable lazy-list keys.

For system-bar or keyboard overlap, read the installed `edge-to-edge/SKILL.md`. For observed performance problems, use `jetpack-compose-performance/SKILL.md` and its relevant references, checking the project's Compose version before adopting newer APIs. Do not expand a screen task into an application-wide optimization audit.

## Validate and report

For UI implementation use previews/behavior checks for consent, duplicate actions, restored drafts and failure recovery, plus repository build/unit checks when tooling is present. Record actual accessibility/device checks; previews do not prove TalkBack or real receipts. Documentation-only changes do not require an app build.

For a review, report actionable findings with severity, file/line, trigger, user impact, and a focused fix. If evidence is unavailable, state what remains unverified. Map relevant checks to U01–U06 in `docs/TEST_MATRIX.md` without marking them complete without evidence.

Reference: [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility).
