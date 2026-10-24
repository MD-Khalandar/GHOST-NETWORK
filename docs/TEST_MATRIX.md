# SOS acceptance matrix

Specification only: no product gate has passed. The current scope is SOS with one cloud coordinator. Former local-inference/hybrid-backend criteria are superseded; saved-contact messaging remains deferred below. Gate IDs now refer to this revision, not evidence from an older plan.

Record PASS, FAIL, NOT RUN or BLOCKED with real artifacts. Host/fake/emulator tests cannot replace required phones, a deployed test service or actual cloud inference.

## Transport and persistence

| ID | Acceptance | Evidence |
| --- | --- | --- |
| T01 | Direct signed SOS exchange with internet off/local radios on | Two physical phones and matching event IDs |
| T02 | A/C cannot connect directly; B relays SOS and return events | Physical isolation, B trace and destination IDs |
| T03 | Ten duplicates apply once with bounded forwarding | Repository/engine tests |
| T04 | Disconnected work queues and delivers on reconnect | Physical queue/send trace |
| T05 | Outbox/dedup survive process restart without renewing expiry | Database-reopen tests and physical B restart |
| T06 | Only correct signed recipient receipt proves phone delivery | Wrong-signer/relay-ACK tests and actual phone receipt |
| T07 | Actor rules reject forged SOS/offer/consent/owner actions | Negative authorization tests |
| T08 | Malformed/oversized/expired/unsupported/bad-signature/key/hop packets rejected | Shared protocol fixtures and validators |
| T09 | Reconnect sync is bounded and missing-ID-only | Inventory tests and physical trace |
| T10 | Permission/radio failures expose recovery | Physical denial/retry observations |
| T11 | Network diagnostics show observed evidence only | Device screenshots and correlated relay IDs |
| T12 | Android kill/relaunch/background limits remain truthful | Physical lifecycle observations |
| T13 | Three full SOS/cloud runs plus internet-loss/recovery succeed | Captured runs with A/B offline, C online; separate C outage/reconnect |

## Cloud agent and policy

| ID | Acceptance | Evidence |
| --- | --- | --- |
| A01 | Actual cloud model generates a validated decision from uploaded SOS/offers | Real provider/model, job ID and sanitized decision trace |
| A02 | Model selects a server tool that executes against persisted state | Decision/validation/tool-result trace |
| A03 | Draft cannot publish without authenticated approval of its current version | Negative server tests and operator UI |
| A04 | B's signed owner decline reaches cloud for the exact proposal/version | Mesh/upload event and cloud state |
| A05 | Cloud reruns after decline and proposes a different existing offer with fresh approval | Distinct real model/tool traces and approval records |
| A06 | Stale/revoked/incompatible/insufficient offers cannot be used | Server policy/transaction tests |
| A07 | SOS/model injection cannot call arbitrary tools, URLs, SQL or shell | Adversarial model/tool tests |
| A08 | Model/worker timeout, crash, malformed output and restart remain bounded/truthful | Fake-model tests and real service failure/recovery observations |
| A09 | Duplicate gateways/jobs, competing accepts and worker restart cannot duplicate decisions or exceed available resources | Idempotency, exclusive-worker and transactional concurrency tests |
| A10 | Gateway identity cannot grant operator/owner authority; absent/revoked cloud permission blocks relevant future processing | Authentication/consent tests, trusted cloud-signature checks and UI controls |

## Gateway and cloud sync

| ID | Acceptance | Evidence |
| --- | --- | --- |
| G01 | Offline A's unchanged signed event travels through B/C to durable cloud storage | Original bytes/IDs, server commit and signed CLOUD_ACK returning to A |
| G02 | Cloud-signed addressed result returns through C/B; final phone receipt returns to server | Correlated packet/outbox/cursor/receipt records; gateway download is not final delivery |
| G03 | Replay, wrong-scope download, invalid signature, denied consent, oversize and expiry are enforced | Android/server sync and crash/cursor tests |
| G04 | No internet/cloud yields pending coordination while local SOS/relay still work; reconnect recovers without duplicates | Physical C internet toggle and real server recovery |

## UX

| ID | Acceptance | Evidence |
| --- | --- | --- |
| U01 | Explicit public/location and separate cloud consent; relay visibility disclosed | UI tests and phone consent flow |
| U02 | 200% text, TalkBack, tap targets, small screen and keyboard/system bars usable | Physical accessibility/layout observations |
| U03 | Local/relay/cloud ACK/operator/proposal/phone receipt/allocation states remain distinct | State tests and actual screen transitions |
| U04 | Web dashboard shows actual cloud model/tools, freshness and authenticated approval; phone Mission reflects the same result | Real trace, browser and phone UI |
| U05 | No AI auto-dispatch, invented authority or unauthorized resource/request action | Server policy tests and actor-specific controls |
| U06 | Relevant empty/loading/error/stale/no-peer/no-cloud states work and drafts survive | UI/previews and physical failure observations |

## Cloud dashboard and database

| ID | Acceptance | Evidence |
| --- | --- | --- |
| D01 | A's SOS appears once in the authenticated incoming board only after committed cloud ingestion; filters/detail show the same request/source/received time | Real A→B→C upload, database/API record and browser observation; repeated upload/reload remains one request |
| D02 | SOS, helper profiles and linked supplies persist through database restart; owner/consent rules hold and offer-before-profile creates only a minimal placeholder | Migration/reopen/relationship tests, profile ownership/duplicate/consent fixtures and helper/supply directory |
| D03 | Dashboard authentication/viewer/operator scopes, bounded reads, stale/error refresh and keyboard navigation work; approval requires server authorization | API/browser checks, unauthorized and failed-refresh cases; no direct frontend database/provider access |
| D04 | Dashboard and agent use the same request/helper/offer state; quantities, units, freshness and allocation outcomes agree, and stale drafts cannot be approved | Shared repository/transaction tests, browser/API/tool traces and exact-version approval rejection |

## Deferred product gates — outside checkpoints 1–10

These are not passed and do not block the SOS phase.

| ID | Later feature |
| --- | --- |
| M01 | Saved-contact identity verification |
| M02 | Offline contact conversation and reply across A/B/C |
| M03 | Chat draft/history recovery and recipient isolation |

Local LLM helpers, generic in-app information services and LoRa need their own later requirements/evidence. Keeping an existing local-model dependency is not local-inference acceptance.

## Three-phone script

Preconfigure cloud/database/dashboard, gateway authentication and a human operator account. Open the dashboard in a browser. A/B have internet off; C has internet and gateway sharing enabled. A/C cannot connect directly. B/C publish owner-signed test helper profiles and offers with cloud consent.

A sends SOS → B relays → C uploads → same request appears in database/dashboard and cloud ACK returns → model selects a tool and drafts B's offer → operator approves in dashboard → B declines → model proposes C's alternate offer → operator approves again → owner accepts and cloud confirms availability → A receives the response and sends a signed receipt. Capture phone/browser IDs and run/approval versions; verify helper/supply state. Repeat three times.

Then disconnect C's internet, create another SOS, show local persistence/relay and pending coordination. Reconnect; prove one accepted event/job outcome despite retries. Exercise model unavailable/unauthorized approval separately.

No scenario claims all-offline AI, E2E encryption, physical rescue or LoRa operation. Record observed latency, retries, validation failures and successful runs, not estimates.
