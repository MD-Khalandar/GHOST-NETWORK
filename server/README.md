# GHOST server

Requires Node.js 22+. From the repository root:

```sh
npm --prefix server ci
npm --prefix server run dev
```

Local default: `http://127.0.0.1:3000`; `GET /v1/health` returns `{"status":"ok"}`.
To check changes, run `npm --prefix server test` and `npm --prefix server run build`.

`GHOST_HOST`, `GHOST_PORT` and `GHOST_ENV` configure the process. Production also
requires `GHOST_DATABASE_URL`, `GHOST_AUTH_PRINCIPALS` (JSON records with `id`, `role`, `token_sha256`;
gateways add `gateway_node_id`) and `GHOST_CLOUD_SIGNING_KEY_PEM` (private P-256 PEM,
with literal `\n` escapes supported). Use random bearer tokens and HTTPS. Never
use the public keys/private keys from test fixtures as deployment credentials.

Gateway credentials allow sync; viewers read dashboard data; operators can reach
approval handlers. `GET /v1/auth/session` returns the authenticated role. Empty
credentials deny protected requests. Event ownership is checked separately.

SOS upload storage also needs `GHOST_DATABASE_URL` and the cloud signing key in
development. Use a PostgreSQL database owned by this service; startup creates its
tables. Without storage, valid uploads return retryable 503. Health stays public.
To load a local environment file after building, run from the repository root:

```sh
node --env-file=server/.env server/dist/index.js
```

`POST /v1/sync/upload` accepts a gateway bearer token and
`{"idempotency_key":"unique-batch-id","packets":[...]}` (1–50 signed SOS_CREATE,
HELPER_PROFILE_PUBLISH, OFFER_PUBLISH or OFFER_UPDATE packets). Both public sharing
and cloud consent must be signed on. The gateway
may relay another phone's SOS. Any invalid or conflicting item rolls back the
batch; other event kinds are not accepted in this slice. Helper/supply payloads
must name the signing phone as owner. Resource events and their signed receipts
persist in PostgreSQL; duplicate offer IDs cannot be republished with new content.

Success returns `results` (event ID, ACCEPTED/DUPLICATE, cloud revision) and
`packets` containing a stored cloud-signed acknowledgement addressed to each
origin phone. The acknowledgement proves server storage, not assistance.
Repeating the same key and ordered signed content returns the same response;
changing content with that key returns 409. Idempotency keys are scoped to the
gateway principal and retained for seven days. Re-uploading under a new key still
deduplicates by packet/event/request IDs and reuses the original acknowledgement.

Server build and tests use the commands above. Storage tests use embedded
PostgreSQL (PGlite), including a file-backed restart; an external PostgreSQL
deployment still needs verification. Android gateway uploads and local signed
receipt storage and acknowledgement return through the Android mesh are implemented.
The built React dashboard is served at `/dashboard/` (build it with
`npm --prefix dashboard ci` and `npm --prefix dashboard run build` from the root).
Its viewer/operator token is entered in the browser and kept only in tab memory.
Add a distinct `viewer` or `operator` record to `GHOST_AUTH_PRINCIPALS` using the
SHA-256 digest of a separate random token; a gateway token cannot read reports.

`GET /v1/dashboard/sos?limit=30&before=<cloud_revision>` returns
`{items, next_before}` ordered by newest cloud revision. `limit` is 1–100;
omit `before` for the first page. `GET /v1/dashboard/sos/:requestId` reads one
report (404 if absent). Both require viewer/operator bearer authentication and
return `Cache-Control: no-store`. Items contain request ID, category (the proto
enum), description, optional people/area/landmark, reported/expiry/received times
in milliseconds, and cloud revision. Signed packets and credentials are excluded.
Expired reports remain visible; server receipt never confirms assistance.

`GET /v1/dashboard/helpers` and `/v1/dashboard/offers` use the same viewer/operator
authentication, no-store policy and `limit`/`before` pagination. Helpers return the
latest publication per owner (ordered by signed timestamp then event ID, so a
delayed old report cannot undo newer availability). Responses include the public
resource fields, owner ID, timestamps and cloud revision; no signed packet bytes.
Expiry remains visible and never confirms allocation. Supplies have unique offer
IDs and remain separate offers. OFFER_UPDATE is a complete owner-signed snapshot:
state (AVAILABLE/WITHDRAWN), optional quantity, unit, area and unchanged availability
deadline/consent. It includes the original publication's exact envelope/signature
as ownership proof; updates can arrive before the original. Their timestamp must
be newer than the original, and expiry cannot extend it. The API projects the
latest timestamp/event ID per offer across kinds 5/6, including withdrawals.

Coordinator integration: query the `resource_offer_latest` view rather than raw
kind-5 publications. It returns the latest signed event per offer; `data.state = 2`
means withdrawn (legacy publications without state mean available). Also check
expiry/available_until_ms and quantities before suggesting or approving matches.

## AI coordinator

Set `GHOST_AI_PROVIDER` to `openai` (default) or `gemini`, plus the matching
server-only `OPENAI_API_KEY` or `GEMINI_API_KEY` and `GHOST_COORDINATOR_MODEL`.
Gemini keys come from Google AI Studio; the model ID must be available to that
key's project. Both adapters use the same validation, queue and failure handling.
The server initializes coordinator runs/proposals, a durable job queue and one
worker lease. Uploads wake the worker; a periodic scan recovers requests and checks
new availability. Calls are serialized, provider calls time out after 30 seconds,
and transient failures retry up to three times per input revision. Restart resumes
queued work after any previous lease expires (up to 90 seconds).

Viewer/operator routes: `GET /v1/dashboard/proposals` returns the latest 30
suggestions with source freshness and readable source fields;
`GET /v1/dashboard/runs` returns configuration availability, pending jobs and the
latest 20 runs. Operator-only `POST /v1/dashboard/sos/:requestId/coordinate`
queues an active SOS and returns 202; repeated completed runs have a 30-second
cooldown (429). Missing model settings return `MODEL_UNAVAILABLE` (503) for reruns.
No record text or provider errors are logged. Model names may be shown; API keys
remain server-side. See [coordinator notes](src/coordinator/README.md).

Approval, owner decisions and allocation remain unimplemented; approval and
download handlers still return 501. AI proposals do not reserve resources.
See [PROJECT.md](../PROJECT.md).
