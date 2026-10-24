# GHOST cloud server — CP01 role C

This is the separately runnable Node.js + TypeScript + Fastify server scaffold
selected by CP01 role A. PostgreSQL 16 remains the selected durable database and
OpenAI Responses API remains the selected production model provider, but CP01
does not connect to either one. The scaffold only validates configuration,
serves the contract health endpoint, exposes a provider seam, and runs
deterministic fake-provider/fixture tests.

## Requirements

- Node.js 22 or newer.
- npm, using the checked-in `package-lock.json`.
- No PostgreSQL instance or provider account is needed for the CP01 tests.

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

The expected CP01 response is process/config health only:

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
| `GHOST_DATABASE_URL` | yes | PostgreSQL URL (`postgres://` or `postgresql://`). CP01 validates syntax but does not connect. |
| `GHOST_MODEL_PROVIDER` | no; defaults to `fake` | `fake` for deterministic local tests or `openai` for the configuration-only production adapter. |
| `GHOST_MODEL` | yes | Deployment model identifier. |
| `GHOST_OPENAI_API_KEY` | only for `openai` | Server-side provider credential; never commit or print it. CP01 refuses to call the provider. |

Missing or invalid required configuration causes startup to exit nonzero with
the variable names and safe validation messages. It does not log values.

## CP01 boundaries

- `GET /v1/health` is the only HTTP route in this scaffold.
- `FakeModelProvider` returns a stable bounded `finish` decision and performs
  no coordination, tool execution, persistence, or network request.
- `OpenAiModelProvider` is a configuration-only seam and intentionally throws
  if called in CP01; no real OpenAI request is made.
- PostgreSQL migrations, repositories, authenticated sync/dashboard APIs,
  durable jobs, real model calls, Docker Compose, HTTPS, and deployment are
  later work. None of those capabilities are claimed here.
