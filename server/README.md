# GHOST server

Requires Node.js 22+. From the repository root:

```sh
npm --prefix server ci
npm --prefix server run dev
```

Local default: `http://127.0.0.1:3000`; `GET /v1/health` returns `{"status":"ok"}`.
To check changes, run `npm --prefix server test` and `npm --prefix server run build`.

`GHOST_HOST`, `GHOST_PORT` and `GHOST_ENV` configure the process. Production also
requires `GHOST_DATABASE_URL`, `GHOST_DASHBOARD_ORIGIN` (the browser-facing HTTPS
origin, without `/dashboard/`) and `GHOST_CLOUD_SIGNING_KEY_PEM` (private P-256 PEM,
with literal `\n` escapes supported). Never
use the public keys/private keys from test fixtures as deployment credentials.

Gateway credentials allow sync; viewer accounts read dashboard data; operator
accounts can reach approval handlers. `GET /v1/auth/session` returns the
authenticated role. Missing credentials deny protected requests. Event ownership
is checked separately. Optional `GHOST_AUTH_PRINCIPALS` preserves legacy API bearer
access: JSON records with `id`, `role`, `token_sha256`; gateways add `gateway_node_id`.
Ordinary phones and browser accounts do not need these static tokens.

SOS upload storage also needs `GHOST_DATABASE_URL` and the cloud signing key in
development. Use a PostgreSQL database owned by this service; startup creates its
tables. Without storage, valid uploads return retryable 503. Health stays public.
To load a local environment file after building, run from the repository root:

```sh
node --env-file=server/.env server/dist/index.js
```

Phone enrollment is automatic once a configured app joins GHOST over cellular or Wi-Fi.
`POST /v1/gateway/challenge` accepts a P-256 public key and returns a server-signed,
60-second challenge. `POST /v1/gateway/enroll` verifies the device's signature over
the exact challenge bytes, consumes it once, and returns a server-signed 24-hour
gateway credential. No preissued token is required for ordinary phones. Pending
challenges are bounded and reused until consumed or expired, so public-key requests
cannot invalidate a pending handshake. Enrollment permits 600 requests per IP and
2,000 globally per minute per server process; each phone normally uses two requests.
These are traffic limits, not a phone-count limit. Database-backed device
credentials store only token hashes. Expired sessions must enroll again; renewal
replaces the previous session. A device row with `revoked = TRUE` blocks both its
session and re-enrollment. Key possession does not establish a person's identity.
These devices have gateway permissions only; dashboard/operator roles still require
separate administrator-issued credentials. Existing static gateway tokens continue
to work for compatibility. Use HTTPS outside debug LAN testing.

`POST /v1/sync/upload` accepts a gateway bearer token and
`{"idempotency_key":"unique-batch-id","packets":[...]}` (1–50 signed SOS_CREATE,
HELPER_PROFILE_PUBLISH, OFFER_PUBLISH, OFFER_UPDATE, terminal SOS_UPDATE or ASSISTANCE_DECISION packets). Both public sharing
and cloud consent must be signed on. The gateway
may relay another phone's SOS. Any invalid or conflicting item rolls back the
batch; other event kinds are not accepted in this slice. Helper/supply payloads
must name the signing phone as owner. Resource events and their signed receipts
persist in PostgreSQL; duplicate offer IDs cannot be republished with new content.
SOS cancellation/resolution uses states 5/6 and must embed the original signed
SOS envelope/signature. Consent and expiry come from that proof. A closure can
arrive first; late originals cannot reopen it. Dashboard items include `state`
(1 open, 5 cancelled, 6 requester-reported resolved). Closed requests are excluded
from AI scheduling and source validation, and their existing proposals are stale.

Success returns `results` (event ID, ACCEPTED/DUPLICATE, cloud revision) and
`packets` containing a stored cloud-signed acknowledgement addressed to each
origin phone. The acknowledgement proves server storage, not assistance.
Repeating the same key and ordered signed content returns the same response;
changing content with that key returns 409. Idempotency keys are scoped to the
gateway principal and retained for seven days. Re-uploading under a new key still
deduplicates by packet/event/request IDs and reuses the original acknowledgement.

Uploads have separate database-backed, packet-weighted minute budgets: 600 per
device/principal, 6,000 per source IP and 12,000 globally. Credential renewal and
server restarts do not reset the current window. Attempts, including retries and
invalid packets, count before signature validation. A throttle returns retryable
429 `UPLOAD_BUSY` with `Retry-After`, without storing a partial batch; phones retain
their queue. Set `GHOST_UPLOAD_PACKETS_PER_DEVICE_MINUTE`,
`GHOST_UPLOAD_PACKETS_PER_IP_MINUTE` and `GHOST_UPLOAD_PACKETS_GLOBAL_MINUTE` to tune
these defaults. Behind a reverse proxy, the source IP is the immediate peer unless
trusted proxy handling is configured; do not trust arbitrary forwarded headers.

Server build and tests use the commands above. Storage tests use embedded
PostgreSQL (PGlite), including a file-backed restart; an external PostgreSQL
deployment still needs verification. Android gateway uploads and local signed
receipt storage and acknowledgement return through the Android mesh are implemented.
The built React dashboard is served at `/dashboard/` (build it with
`npm --prefix dashboard ci` and `npm --prefix dashboard run build` from the root).
Sign in with a username and password. With `server/.env` configured, create an
operator account from an interactive terminal at the repository root:

```sh
npm --prefix server run account -- --username admin --role operator
```

The command prompts for a hidden password and confirmation (15–256 characters).
Usernames use 3–64 letters, numbers, dots, underscores or hyphens, starting with a
letter; they are case-insensitive. Use `--role viewer` for read-only access.
Running the command again resets the password/role and revokes all prior sessions.
Disable an account with `npm --prefix server run account -- --username admin --disable`.
For compiled deployments, use `node --env-file=server/.env server/dist/account-cli.js`
with the same arguments. There is no public registration endpoint.

Passwords are salted and hashed with scrypt. Persistent HttpOnly, SameSite=Strict
cookies restore the browser session after refresh or restart, for at most seven
days and with a 24-hour idle timeout. Sign out revokes the server session; account
and session state survives server restarts. Production uses Secure cookies and
requires `GHOST_DASHBOARD_ORIGIN=https://<your-host>`; plain HTTP cookies are for
local development. Set the origin to the browser URL, including any nondefault
port. Browser mutations require same-origin headers and a session-bound CSRF token.
Login attempts have persistent rate limits. Browser sessions cannot authorize phone
sync, and gateway tokens cannot read reports.

`GET /v1/dashboard/sos?limit=30&before=<cloud_revision>` returns
`{items, next_before}` ordered by newest cloud revision. `limit` is 1–100;
omit `before` for the first page. `GET /v1/dashboard/sos/:requestId` reads one
report (404 if absent). Both require a viewer/operator session or API bearer token and
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
new availability and assistance outcomes. One request is leased at a time; people
and supply specialists can make at most two parallel provider calls, each timing out after 30 seconds,
and transient failures retry up to three times per input revision. Restart resumes
queued work after any previous lease expires (up to 90 seconds).

`GHOST_AI_MAX_CALLS_PER_HOUR` defaults to 120 provider calls per UTC hour, shared
across server instances and persisted across restarts. Failed calls count; runs
with no candidate resources do not. At the limit, requests remain queued until
the next hour without consuming retry attempts; uploads and mesh sharing continue.
This bounds call volume, not currency spending. All four budget settings accept
positive integers up to 1,000,000. Set provider-side spending controls separately.

Viewer/operator routes: `GET /v1/dashboard/proposals` returns suggestions with
source freshness from one snapshot and readable source fields. It accepts `limit`
(1–100, default 30), `before` (the response's `next_before` cursor) and optional
`request_id`. Pages sort by timestamp and proposal ID; follow `next_before` until null.
`GET /v1/dashboard/runs` returns configuration availability, hourly budget usage/reset, pending jobs and the
latest 20 runs with candidate/proposal counts, plus an accurate `queued_total`.
Automatic reruns follow changes to each request's bounded relevant candidate set,
including expiry; unrelated uploads do not spend another model call.
Operator-only `POST /v1/dashboard/sos/:requestId/coordinate`
queues an active SOS and returns 202; repeated completed runs have a 30-second
cooldown (429). Missing model settings return `MODEL_UNAVAILABLE` (503) for reruns.
No record text or provider errors are logged. Model names may be shown; API keys
remain server-side. See [coordinator notes](src/coordinator/README.md).

Operator-only `POST /v1/dashboard/proposals/:proposalId/approve` or `/reject`
reviews a suggestion (no request body). Approval rechecks sources in a transaction
and writes an invitation; unknown supply quantities require a new known-stock
publication before approval. Repeated reviews do not create duplicate invitations.
`GET /v1/dashboard/assistance` lists the latest 100 arrangements for viewers/operators.
States: 1 invited, 2 committed, 3 declined/rejected, 4 cancelled, 5 expired,
6 requester-reported SOS resolved, 7 requester-confirmed assistance received.
A commitment does not prove physical delivery. State 7 records a requester's report,
not independent verification; it completes only this arrangement and leaves the SOS open.

Invitations are automatic by default. `GET /v1/dashboard/coordination-settings`
returns `{manual_review, updated_at_ms}` to viewers/operators. Operator-only `POST`
to the same route accepts only `{manual_review: true|false}`; browser mutations also
require CSRF protection. The shared mode persists in PostgreSQL and is checked under
the invitation transaction lock, including when an AI call finishes after a toggle.
Switching to automatic processes eligible pending suggestions; startup/periodic
scans recover missed work even when the model is unavailable. No provider call is
needed for approval. Existing manual review endpoints remain usable.
Source/availability checks precede invitations; unknown stock waits for a known
publication and a fresh suggestion. Existing arrangements and previously reviewed
source pairs are not automatically invited again. Helper acceptance and atomic
reservations are still required. Existing invitations survive mode changes.

Gateway-only `GET /v1/sync/download?cursor=0&limit=30` returns signed coordination
packets and `next_cursor`; limit is 1?50. These are public-consented relay messages
with destination IDs, not encrypted private messages. A phone verifies and stores
the whole page before persisting its cursor. `/v1/sync/download-ack` accepts
`{cursor, packet_ids}` after persistence. This acknowledges gateway storage only;
it never marks the owner/requester as having read or accepted anything. Unexpired
events remain downloadable to other gateways regardless of these acknowledgements.

Owner-signed ASSISTANCE_DECISION (17) uploads carry the pinned, server-signed
ASSISTANCE_STATUS (16) as proof. The server checks owner, version, deadline and
current availability again. Decisions and reservations deduplicate transactionally;
acceptance reserves known stock or one volunteer commitment. Two acceptances
cannot allocate the same remaining stock. A conflicting acceptance produces a
signed cancellation. Current server commitments subtract from AI availability.

Invitations expire within one hour/source expiry. New resource versions invalidate
pending invitations; withdrawal stops new matches but retains existing commitments.
Edits below committed/used stock or with changed units return 409
`SUPPLIES_ALREADY_COMMITTED`. Owner cancellation/SOS cancellation/expiry release
active reservations. Requester-signed ASSISTANCE_DECISION choice 4 requires a current
committed status addressed to the requester and records state 7. Other choices still
require the resource owner. Confirmation consumes reserved supplies or frees a
volunteer; duplicate decisions do not consume twice. Confirmed receipt survives SOS
closure and expiry. The startup migration extends existing state constraints in place.
Requester resolution records supplies as used rather than
returning them to available stock, and frees volunteers. Signed lifecycle updates
are stored atomically with the change for later download/mesh delivery. Cleanup
runs on uploads and coordination polling; there is no independent fulfilment proof.
See [PROJECT.md](../PROJECT.md).
