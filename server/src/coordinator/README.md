# Cloud AI coordinator

The existing server starts this worker after database initialization. Set
`GHOST_COORDINATOR_MODEL` and the provider's key on the server to enable it.
`GHOST_AI_PROVIDER=openai` (default) uses `OPENAI_API_KEY`; `gemini` uses
`GEMINI_API_KEY`. No keys go into the APK or browser bundle. Gemini uses Google's
[Chat Completions compatibility API](https://ai.google.dev/gemini-api/docs/openai).

Committed uploads wake a durable request queue. Startup and a 15-second scan
recover missed work and compare the signed source versions in each SOS's actual
candidate set. Unrelated uploads and delayed originals do not rerun unchanged
inputs; expiry, withdrawals and capability changes do. The latest resource view
is materialized once per discovery scan. Queue generations preserve changes found
during inference; bounded batches continue draining without an extra polling delay.
One leased worker runs at a time; it heartbeats and checks lease ownership before
saving results. Graceful shutdown finishes the active bounded call. A hard crash
can delay recovery up to 90 seconds; its interrupted run is marked failed when
the queued request resumes. Transient failures retry at most three times per input
revision with backoff. Operators can retry from an SOS after a short cooldown.

Each run reads one SOS and at most 50 helpers plus 50 current offers, preferring
the normalized reported area (case/whitespace ignored). Category-compatible types
are filtered before the cap, and the requester's own resources are excluded.
Unspecified categories include all types; transport/general help remain candidates.
Other or missing areas remain eligible and imply neither distance nor access.
Supply reads use the latest signed resource projection, so signed edits and
withdrawals invalidate older proposals. Suggestions are validated for IDs,
quantities, expiry and exact source versions again before atomic persistence.
Unknown stock stays unknown. Suggestions do not reserve stock or confirm help.

The [Responses function-calling API](https://developers.openai.com/api/docs/guides/function-calling)
receives untrusted records and one forced strict suggestion tool, with a 30-second
timeout and bounded output. No arbitrary function dispatch, generated SQL or shell
is executed. Missing model configuration and provider errors remain explicit;
there is no fabricated fallback success.

Dashboard: **AI suggestions** has stable 30-item pages, SOS-ID filtering and 20 recent
runs. Freshness uses one database snapshot for a page; request/resource expiry is
also checked between polls. Run outcomes distinguish an empty candidate set from
no model suggestions and saved suggestions. Queue totals and retry times are explicit.
Polling refreshes the selected page. **Find help with AI** on an SOS requests an operator-only
rerun. Viewers can read; gateways cannot access coordination routes.

Approval, owner acceptance, conflict-free allocation and signed assistance delivery
are still separate next steps. Future approval must recheck current sources and
allocations inside its transaction. Local provider/HTTP/database tests do not prove
live model or deployed PostgreSQL behavior.
