# Cloud coordination agent contract

The SOS phase has **one logical cloud coordinator**. Its model adapter, orchestration, typed tools, durable jobs and authoritative coordination state run on the server. Phones submit reports and human actions; gateways transport them. There is no on-device coordination or automatic local-model fallback.

The web dashboard and agent share server repositories for SOS requests, helper profiles, supplies/offers and proposals. The dashboard shows committed incoming requests and actual run history; authenticated operators approve there. It is a client of the same policy, not a separate coordination engine.

## Authority and input

- Build each job from the cloud's verified persisted snapshot. Offline unsynchronized reports are unknown, not absent resources.
- Deduplicate triggers by event/projection version. Use one serialized worker with exclusive ownership; restart recovers durable jobs without competing model loops or duplicate publications.
- Authenticate gateways separately from original event signers. Only authorized human operators approve proposals; device owners sign their own offers/acceptance/decline, and requesters close their SOS.
- Public sharing does not grant cloud permission. Enforce owner consent at ingestion and before model calls; suppress future work after an authorized revocation is received.
- Model output, SOS text and offer notes are untrusted. Do not include ordinary chat or unrelated personal data. Keep provider secrets server-side.

## Bounded tool loop

Verified cloud event → current snapshot → model decision → parse/validate → server tool → persisted observation → next step or draft → operator approval → revalidation → signed outbox event.

Decision shape: `{schema_version, action, arguments, reason}`. Allowed actions are the following tools plus `finish`. Reject invalid types, unknown actions, invented IDs, oversized values and arguments outside the snapshot. Reasons explain; they do not authorize.

| Tool | Model permission |
| --- | --- |
| `read_sos_board` | Read bounded uploaded reports |
| `find_offers` | Read fresh compatible uploaded offers linked to helper identities/availability |
| `check_offer_status` | Read owner-signed availability and cloud allocation state |
| `get_delivery_status` | Read cloud acknowledgements and actual phone receipts |
| `propose_match` | Persist a draft only |
| `request_clarification` | Persist a draft only |

The server's typed tools replace the previous phone-side Kotlin tool loop. Android Kotlin validates received events and user actions; it does not execute model-generated coordination decisions.

Maximum four model decisions per attempt, one invalid-output repair within that budget, bounded input/output sizes and total deadline. No generated shell, SQL, arbitrary HTTP URLs or function execution. No invented resources, medical triage or unverified rescue claims.

## Human actions and consistent resources

The authenticated approval endpoint validates operator role, exact proposal version, open request, owner consent and current offer state. Save approval and outgoing event atomically; retries return the same outcome. Model tools cannot call this endpoint or fabricate operator authority.

Only a signed owner acceptance can request commitment. Confirm it in a transaction against the current proposal/available quantity; two requests cannot consume the same remaining resource. Return a signed confirmation or rejection. A queued offline acceptance is not a confirmed allocation.

Decline/withdrawal invalidates the relevant proposal and schedules one new job on current state. A new proposal requires fresh operator approval. Old approvals and late model results cannot authorize a replacement. Closing a request invalidates outstanding work.

## Runs, failure and evidence

Persist jobs, attempts, selected tools/results, model/provider, snapshot version, draft version, operator approval and outgoing IDs. States include queued, running, waiting for operator, waiting for owner, replan pending, complete, no match, failed and timed out. Keep per-request run history, not one permanent model chat.

During gateway/cloud/model outages, show pending/unavailable. Keep manual local SOS functions usable. Do not claim local AI took over. Recover queued work without duplicate approvals, commitments or delivery claims.

Use fake model/cloud and deterministic clocks to test injection, malformed output, stale/withdrawn offers, unauthorized approval, revoked consent, repeated uploads, competing accepts, worker restart and delayed responses. Real cloud inference and tool execution must be separately recorded for A01–A10 in [TEST_MATRIX.md](TEST_MATRIX.md).

Local LLMs are deferred helpers for drafting, translation and cached summaries. They have no coordination authority; see [ARCHITECTURE.md](../ARCHITECTURE.md).
