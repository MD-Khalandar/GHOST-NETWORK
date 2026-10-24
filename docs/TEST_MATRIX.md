# Objective acceptance matrix and demo evidence

These tests are specifications, not claims of completion. Actual hardware tests must be captured in logs/screenshots/videos.

## Networking and persistence

| Test | Acceptance | Evidence |
|---|---|---|
| T01 | A→B sends signed SOS bytes with mobile data/internet off, local radios on | Two handset log IDs + screenshot |
| T02 | A and C cannot connect directly; B carries A→C event | Relay log on B and destination event ID on C |
| T03 | Duplicate packet injected 10×; store/process once | Room count=1 + forwarded count bounded |
| T04 | B disconnects before C is reachable; queues, reconnects, delivers | Queue→send transition log |
| T05 | Force-process-restart B; queued packets+dedup survive | Post-restart Room/relay trace |
| T06 | Nearby send success is only SENT_TO_NEIGHBOR, not recipient delivery | Test state before and after signed C receipt |
| T07 | Unauthorized sender cannot resolve SOS or mutate offer owner record | Forged event rejected |
| T08 | Expired, oversized, invalid signature/version/hops packets rejected | Validator test suite |
| T09 | Reconnect inventory exchanges missing IDs only (within caps) | Sent bytes/IDs log |
| T10 | App denies permissions gracefully and explains retry action | On-device permission-denied UX |
| T11 | Relay logs do not imply unobserved global network topology | Network diagnostics UX |
| T12 | Foreground background mode does not claim unstoppable operation | Kill/relaunch observation |
| T13 | Offline A→B→C flow repeated successfully 3 times | Captured demo videos/logs |

## Agentic AI

| Test | Acceptance | Evidence |
|---|---|---|
| A01 | Actual pre-provisioned model generates validated tool decision offline on Phone C | device ID, model hash, inference log, no internet |
| A02 | Model selects `find_offers`, Kotlin executes and logs returned offer IDs | run ID + tool trace |
| A03 | Agent creates draft match, cannot directly send without approval | negative unit test + UI approval screen |
| A04 | B declines an offered proposal; coordinator receives signed decline | decline event + versioned proposal |
| A05 | On decline, local agent reruns and proposes another existing offer | two distinct run/tool traces |
| A06 | Stale/revoked offer excluded by Kotlin validator regardless of model response | validator unit tests |
| A07 | Injection in an SOS cannot induce arbitrary tool/URL/SQL/shell action | adversarial fixture |
| A08 | No model, bad output or timeout shows fallback/unknown, never fake tool success | failure UI, logs |

## UX and safety

| Test | Acceptance |
|---|---|
| U01 | SOS shares no precise location unless user explicitly opts in |
| U02 | 200% font scale and screen reader usable; min tap targets |
| U03 | Queue vs relay vs final recipient receipt text+icon are distinct |
| U04 | Mission Control shows **actual** tool execution, provenance and consent |
| U05 | No AI-only auto-dispatch, medical instructions or unverified authority badges |
| U06 | Every screen has empty, error, loading, stale states where applicable |

## Five-minute judge demo script

0:00 — Show all three phones with mobile data/internet disabled; verify Phone C's model status is **LOCAL MODEL READY**.  
0:30 — Phone B publishes a water offer; Phone C publishes an alternate local volunteer offer. Both records synchronize and retain owner signatures.  
1:00 — Phone A sends SOS. Show Phone B's real relay ID and that A/C cannot discover one another directly.  
1:40 — Phone C Mission Control shows **model output → validated `find_offers()` → proposed match**.  
2:20 — Coordinator confirms proposal; Phone B owner declines.  
3:00 — Show new local model run reading fresh state and proposing the alternate Phone C offer.  
3:40 — Human confirms; send A a signed assistance offer through B; show accurate receipt state.  
4:20 — Briefly demonstrate no-model/failure UI and a replay-rejected packet; close with limitations and realistic impact.

## Metrics worth recording (not inventing)

- Time from arrival of SOS on coordinator to match proposal (seconds)
- Local model decision latency (seconds) and JSON parse success rate (%)
- Duplicate packet suppression under repeated injection
- Successful relay runs / attempts
- Request status/receipt correctness across device restart
- Number of model-selected tool actions and number rejected by deterministic policy

Never imply the application has been tested without real results. This file is a test plan.
