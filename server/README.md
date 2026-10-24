# GHOST server

Requires Node.js 22+. From the repository root:

```sh
npm --prefix server ci
npm --prefix server run dev
```

Local default: `http://127.0.0.1:3000`; `GET /v1/health` returns `{"status":"ok"}`.
To check changes, run `npm --prefix server test` and `npm --prefix server run build`.

`GHOST_HOST`, `GHOST_PORT` and `GHOST_ENV` configure the process. Production also
requires `GHOST_AUTH_PRINCIPALS` (JSON records with `id`, `role`, `token_sha256`;
gateways add `gateway_node_id`) and `GHOST_CLOUD_SIGNING_KEY_PEM` (private P-256 PEM,
with literal `\n` escapes supported). Use random bearer tokens and HTTPS. Never
use the public keys/private keys from test fixtures as deployment credentials.

Gateway credentials allow sync; viewers read dashboard data; operators can reach
approval handlers. `GET /v1/auth/session` returns the authenticated role. Empty
credentials deny protected requests. Event ownership is checked separately.

Signing, validation and authorization work. Storage/coordination operations still
return 501; no SOS is accepted or approved yet. Database, model integration and
dashboard are next work, not fake implementations. See [PROJECT.md](../PROJECT.md).
