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
`{"idempotency_key":"unique-batch-id","packets":[...]}` (1–50 signed SOS_CREATE
packets). Both public sharing and cloud consent must be signed on. The gateway
may relay another phone's SOS. Any invalid or conflicting item rolls back the
batch; other event kinds are not accepted in this slice.

Success returns `results` (event ID, ACCEPTED/DUPLICATE, cloud revision) and
`packets` containing a stored cloud-signed acknowledgement addressed to each
origin phone. The acknowledgement proves server storage, not assistance.
Repeating the same key and ordered signed content returns the same response;
changing content with that key returns 409. Idempotency keys are scoped to the
gateway principal and retained for seven days. Re-uploading under a new key still
deduplicates by packet/event/request IDs and reuses the original acknowledgement.

Server build and tests use the commands above. Storage tests use embedded
PostgreSQL (PGlite), including a file-backed restart; an external PostgreSQL
deployment still needs verification. Phone uploads, acknowledgement delivery
through the mesh, dashboard and coordination remain next work; their handlers
still return 501. See [PROJECT.md](../PROJECT.md).
