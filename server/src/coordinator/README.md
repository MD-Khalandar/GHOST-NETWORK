# Cloud AI coordinator

The existing server starts this worker after database initialization. Set
`OPENAI_API_KEY` and `GHOST_COORDINATOR_MODEL` on the server to enable it; both
are required. No model/provider settings go into the APK or browser bundle.

Committed uploads wake a durable request queue. Startup and a 15-second scan
recover missed work and requeue active SOS when uploaded availability changes.
One leased worker runs at a time; it heartbeats and checks lease ownership before
saving results. Graceful shutdown finishes the active bounded call. A hard crash
can delay recovery up to 90 seconds; its interrupted run is marked failed when
the queued request resumes. Transient failures retry at most three times per input
revision with backoff. Operators can retry from an SOS after a short cooldown.

Each run reads one SOS and at most 50 helpers plus 50 current offers, preferring
the reported area. Supply reads use `resource_offer_latest`, so signed edits and
withdrawals invalidate older proposals. Suggestions are validated for IDs,
quantities, expiry and exact source versions again before atomic persistence.
Unknown stock stays unknown. Suggestions do not reserve stock or confirm help.

The [Responses function-calling API](https://developers.openai.com/api/docs/guides/function-calling)
receives untrusted records and one forced strict suggestion tool, with a 30-second
timeout and bounded output. No arbitrary function dispatch, generated SQL or shell
is executed. Missing model configuration and provider errors remain explicit;
there is no fabricated fallback success.

Dashboard: **AI suggestions** shows the latest 30 suggestions and 20 runs, including
stale source labels. **Find help with AI** on an SOS requests an operator-only
rerun. Viewers can read; gateways cannot access coordination routes.

Approval, owner acceptance, conflict-free allocation and signed assistance delivery
are still separate next steps. Future approval must recheck current sources and
allocations inside its transaction. Local provider/HTTP/database tests do not prove
live model or deployed PostgreSQL behavior.
