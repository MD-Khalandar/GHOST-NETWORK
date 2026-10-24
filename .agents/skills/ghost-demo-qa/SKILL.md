---
name: ghost-demo-qa
description: Run GHOST SOS three-phone and cloud-coordinator QA with real relay, approval, replan, outage recovery and recorded acceptance evidence.
---

# GHOST demo QA

Read `AGENTS.md`, current checkpoint/team ownership, `docs/TEST_MATRIX.md` and relevant architecture/protocol/agent/UX contracts. Inspect implementation/evidence before assuming features exist.

For application QA use the configured Android environment and build/unit command; run the server command documented at CP01. Docs-only work does not build. List physical phones separately from emulators and target ADB by serial. Do not erase device data as a routine check.

Record PASS/FAIL/NOT RUN/BLOCKED per active T/A/G/U/D gate. Missing phones/browser, cloud/database/dashboard, provider access or operator account leaves evidence open. M gates/local LLM helpers are deferred.

## Three-phone proof

1. A/B have internet off, C has internet/gateway sharing. Open an authenticated web dashboard; confirm cloud trust/auth and A/C inability to connect directly.
2. Send A's consented SOS through B/C; correlate unchanged origin IDs/bytes, durable cloud ACK and return relay.
3. Publish test helper profiles/offers from B/C. Verify the incoming SOS and helper/supply records in database/dashboard. Capture actual cloud tools, saved draft and dashboard operator approval of its exact version.
4. B declines; capture signed decline upload, distinct real cloud replan selecting another offer and fresh operator approval. Owner acceptance requires cloud allocation confirmation.
5. Return the response to A and its actual signed phone receipt to cloud. Repeat the full scenario three times.
6. Disconnect C's internet: local SOS persists/relays while coordination is pending. Reconnect and verify dedup, cursor/outbox recovery and truthful status.
7. Exercise phone/server restart, duplicate gateways, stale approval, conflicting acceptance, denied consent, invalid packet/receipt and provider failure.
8. Check phone consent/accessibility/failure UX and dashboard keyboard, refresh/stale, role authorization and data consistency (D01–D04). Distinguish phone/browser evidence.

Use the exact matrix/script; do not describe this as all-offline AI. Gateways downloading a result do not prove requester receipt. No E2E privacy, rescue or LoRa claim without separate evidence.

Report actual Android/server checks, cloud/tool traces, physical gates, failed reproductions and missing prerequisites. Keep private data/secrets out of artifacts. Use assigned `docs/evidence/cpNN/<role>.md` reports; integration owner A consolidates status after user merges.
