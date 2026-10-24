# GHOST cloud server

Node.js + TypeScript + Fastify, with CP02 signing, packet validation and API role
guards. PostgreSQL 16 and OpenAI Responses API remain the selected database and
model provider; neither is connected yet. Persistent operations return 501 after
trust checks, never a fabricated acceptance or approval.

## Requirements

- Node.js 22 or newer.
- npm, using the checked-in `package-lock.json`.
- No PostgreSQL instance or provider account is needed for the host tests.

## Exact local commands

From the repository root:

```sh
npm --prefix server ci
npm --prefix server test
npm --prefix server run build
GHOST_ENV=test \
GHOST_HOST=127.0.0.1 \
GHOST_PORT=3000 \
GHOST_DATABASE_URL=postgresql://localhost:5432/ghost \
GHOST_MODEL_PROVIDER=fake \
GHOST_MODEL=cp01-fake \
npm --prefix server run smoke
```

After the Android unit tests, `npm --prefix server run verify:android` verifies
the host-JCA signature written to `app/build/reports/cp02/android-signature.json`.
This is a cross-platform encoding/signature check, not handset Keystore evidence.

`npm --prefix server test` also checks shared API security/tag structure, unique
operation IDs and internal references. It replaces the former separate Ruby
contract-check command. Both server and Android tests read
`contracts/fixtures/cp01-fixtures.json` directly; there is no server fixture copy
to synchronize. Run tests from a checkout containing `contracts/`.

To run the development server locally with the deterministic provider:

```sh
export GHOST_ENV=development
export GHOST_HOST=127.0.0.1
export GHOST_PORT=3000
export GHOST_DATABASE_URL=postgresql://localhost:5432/ghost
export GHOST_MODEL_PROVIDER=fake
export GHOST_MODEL=cp01-fake
npm --prefix server run dev
```

In a second terminal, the health check is:

```sh
curl --fail http://127.0.0.1:3000/v1/health
```

The expected response is process/config health only:

```json
{"status":"ok","contract_version":"ghost.contracts.v1","database":"not_checked","model_provider":"configured"}
```

The alternative `.env.example` lists the same variable names. It is a names-
only template; this server does not load `.env` automatically, so source it in
the shell or export the variables explicitly.

## Environment variables

| Name | Required | Meaning |
| --- | --- | --- |
| `GHOST_ENV` | no; defaults to `development` | `development`, `test`, or `production`. |
| `GHOST_HOST` | no; defaults to `127.0.0.1` | Listen host. |
| `GHOST_PORT` | no; defaults to `3000` | TCP port from 1 through 65535. |
| `GHOST_DATABASE_URL` | yes | PostgreSQL URL (`postgres://` or `postgresql://`). Syntax is validated; no connection is made yet. |
| `GHOST_MODEL_PROVIDER` | no; defaults to `fake` | `fake` for deterministic local tests or `openai` for the configuration-only production adapter. |
| `GHOST_MODEL` | yes | Deployment model identifier. |
| `GHOST_OPENAI_API_KEY` | only for `openai` | Server-side provider credential; never commit or print it. Real provider calls remain disabled. |
| `GHOST_AUTH_PRINCIPALS` | production; optional in development/test | JSON array of `{id, role, token_sha256}`. Gateway records also require `gateway_node_id`; see the trust contract. Empty config denies all protected requests. |
| `GHOST_CLOUD_SIGNING_KEY_PEM` | production; optional in development/test | Server-only P-256 private PEM. Literal `\n` escapes are accepted. Never use fixture keys. |

Missing or invalid required configuration causes startup to exit nonzero with
the variable names and safe validation messages. It does not log values.

## Implemented trust and remaining boundaries

- `GET /v1/health` is public process/config health. `GET /v1/auth/session` returns
  only the caller's authenticated principal and role.
- Gateway tokens authorize sync only; viewer/operator tokens authorize dashboard
  reads and only operators may reach proposal decision handlers. Missing/invalid
  tokens return 401; wrong roles return 403. Identity in request bodies grants no role.
- Upload verifies signed origin packets, actor rules and cloud permission. The
  legacy `receipts` array must be empty; actual receipts are signed packets.
- Successful trust checks return 501 for persistent sync/read/approval operations.
  No SOS is saved, no job is created, no approval is granted and no signed cloud
  acceptance is issued yet. Unknown ownership context is rejected.
- Startup validates configured P-256 signing material without logging it. Model
  provider construction remains at the future coordinator execution boundary.
- `FakeModelProvider` returns a stable bounded `finish` decision and performs
  no coordination, tool execution, persistence, or network request.
- `OpenAiModelProvider` is a configuration-only seam and intentionally throws
  if called; no real OpenAI request is made.
- PostgreSQL migrations, repositories, persistent sync/dashboard operations,
  durable jobs, real model calls, Docker Compose, HTTPS, and deployment are
  later work. None of those capabilities are claimed here.

The [hackathon architecture](../ARCHITECTURE.md#hackathon-implementation) keeps
the future API and single database-backed worker in this service, with one
PostgreSQL database. There is no separate queue broker or agent service to deploy.

See [TRUST_V1.md](../contracts/TRUST_V1.md) for exact signing bytes, public-key pin
configuration, bounds, roles and rejection codes. Run the service behind HTTPS
before using bearer credentials outside localhost. Test-only shared private keys
are public fixtures, not deployable credentials.
