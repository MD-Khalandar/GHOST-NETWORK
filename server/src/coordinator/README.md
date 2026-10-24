# Cloud coordinator integration

This module is intentionally unwired. It reads `sos_reports` and `resource_events`
from helper/supply commit `1aeb2ad`; it never copies publications or changes stock.

1. After the existing `initializeDatabase(database)`, await
   `initializeCoordinator(database)` from `./coordinator/store.js`. This creates
   only `coordinator_runs` and `coordinator_proposals` (source IDs/revisions,
   proposed quantity, explanation and review status). No allocation schema.
2. Construct `new CloudCoordinator(new CoordinatorStore(database),
   new OpenAIProposalModel({ apiKey: process.env.OPENAI_API_KEY,
   model: process.env.GHOST_COORDINATOR_MODEL }))` using the coordinator,
   store and openai-model modules. Both settings are server-only and required;
   missing settings produce `unavailable`. Do not log config, provider responses
   or report content. The model is explicitly chosen by the operator.
3. Call `await coordinator.run(requestId)` from an authenticated operator action
   or a bounded server worker after upload commits. A run handles one SOS and at
   most 50 eligible helpers plus 50 offers, preferring the reported area.
   `completed` means inference/storage finished, including zero matches; it says
   nothing about help. Runs preserve `running`, `completed`, `unavailable` or
   `error` states; a process interruption may leave `running`. An operator can
   rerun; the source-version pair deduplicates proposals.
4. A future viewer/operator review endpoint can use `store.listProposals(limit)`.
   Explanations are untrusted model text: escape them, label them as suggestions,
   and show quantity units/unknown stock from the referenced source. A gateway
   must not access review data. There are no approval/delivery handlers here.

Latest helper selection uses `(created_at_ms DESC, event_id DESC)` before expiry
and availability checks, as existing ingestion/dashboard do. Offers use their
signed envelope expiry and `available_until_ms`. Known stock bounds each proposed
quantity; unknown stock remains null. Pending proposals do not reserve resources,
so multiple requests may have competing suggestions.

The `store.checkProposal(id, nowMs)` freshness check compares both source event IDs
and revisions plus availability, expiry and stock. It is advisory: future approval
must recheck sources and competing allocations inside its own transaction, then
obtain the owner's signed acceptance separately. Neither approval nor acceptance
exists in this slice. No cloud packets or assistance promises are sent.

Supply edits/withdrawals and SOS state/revocation updates are not ingested by this
base. When ingestion adds them, adapt `readResources`/`readRequest` in `store.ts`
to the canonical current projection, including withdrawals/closed requests and
consent revocation. Do this before enabling coordination on updated records;
never filter out withdrawals before selecting the latest source version.

Model integration uses the [Responses function-calling API](https://developers.openai.com/api/docs/guides/function-calling)
with one forced strict typed suggestion tool, no arbitrary dispatch, no stored
provider response, a 30-second timeout and bounded output. Server validation and
a fresh database snapshot precede atomic proposal persistence. Provider failures
return sanitized codes; there is no fallback that fabricates model success.

Checks: `npm --prefix server test` and `npm --prefix server run build`.
Tests use signed uploads, embedded PostgreSQL and a local HTTP provider fixture;
live provider behavior and external PostgreSQL deployment still need verification.
