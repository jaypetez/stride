# @stride/api

The Stride HTTP API — the same `@stride/core` coach exposed over HTTP with
[Hono](https://hono.dev). Requests/responses are validated against the shared
`@stride/schemas` Zod schemas, and the app's `AppType` is exported so the web UI
gets a fully typed `hc` RPC client with no codegen.

## Run it offline

```bash
pnpm --filter @stride/api dev        # http://localhost:8720 (STRIDE_API_PORT)
```

Then hit the demo endpoints — no credentials needed:

```bash
curl http://localhost:8720/health
curl http://localhost:8720/analyze/demo
curl "http://localhost:8720/next?demo=true"
curl "http://localhost:8720/pmc?demo=true"
curl -X POST http://localhost:8720/plan -H 'content-type: application/json' -d '{"demo":true,"race":"10k","weeks":8}'
```

## Routes

| Method & path | Purpose |
|---|---|
| `GET /health` | Liveness: `{ status: "ok", version }` |
| `GET /profile` | The stored athlete profile (defaults if unset) |
| `POST /profile/screening` | Run PAR-Q screening; body `{ answers: boolean[] }`; persists `medicalClearance` + `healthFlags`; returns `{ screening, profile }` |
| `GET /activities` | Recent activity summaries (streams stripped); query `demo`, `limit` |
| `GET /pmc` | PMC + ACWR series and latest values; query `demo`. Live reads the durable daily-load series |
| `GET /analyze/:id` | Analyze one activity; `:id` may be `demo`, `last`, or an id; query `note`. Returns `{ metrics, analysis, disclaimer, flags }` |
| `GET /next` | Next-workout suggestion; query `demo`, `note`. Returns `{ context, workout, disclaimer, flags }` |
| `POST /plan` | Generate a plan; body `{ race?, weeks?, start?, date?, note?, demo? }`. Saves plan + goal when not demo |
| `GET /plan` | The saved plan, or `404` if none |
| `POST /sync` | Sync Strava into the local store; returns the `SyncResult` |

`GET /analyze/:id`, `GET /next`, `POST /plan`, and `GET /pmc` accept `demo=true`
(query) / `"demo": true` (body) to run on bundled fixtures.

## Error envelope

Every response carries an `x-request-id` header (echoed from the request or
generated). **Errors return `{ error, requestId }`** with a matching
`x-request-id`, routed through a single `onError` handler — including Zod
validation failures, which return `400` with a human-readable `error` string
rather than a bare Hono `400`:

```json
{ "error": "Invalid request — weeks: Expected number, received string", "requestId": "…" }
```

Status mapping: rate limit → `429`, upstream Strava error → `502`, sync lock held
→ `409` (see [ADR 0004](../../docs/adr/0004-advisory-sync-lock.md)), validation →
`400`, not found → `404`, otherwise `500`. `500` and `502` bodies carry a generic
message only; the detail is in the stderr log line with the same `requestId`. A
client `x-request-id` is echoed only if it matches `^[\w-]{1,64}$`; otherwise a
UUID is generated.

## Local hardening

The API is a local server for one owner, so it defends against the browser and
the LAN (see `src/security.ts`):

| Guard | Behaviour |
|---|---|
| Bind address | `STRIDE_API_HOST`, default `127.0.0.1` (loopback only). `stride doctor` warns on a non-loopback bind without a token. |
| Bearer token | Optional `STRIDE_API_TOKEN`; when set, every request needs `Authorization: Bearer <token>` or gets `401`. The Vite dev proxy attaches it for the web UI. |
| Host allowlist | `Host` must be `localhost`, `127.0.0.1`, `[::1]`, the bind host, or a `STRIDE_API_ALLOWED_HOSTS` entry, else `403` (blocks DNS rebinding). |
| JSON-only writes | `POST`/`PUT`/`PATCH`/`DELETE` need `Content-Type: application/json`, else `415` (forces a CORS preflight, so cross-site pages cannot fire `/sync`). |
| Body limit | 64 KiB, else `413`. |
| Rate limits | `POST /sync`: 5/min. Coach routes (`/analyze/*`, `/next`, `POST /plan`): 30/min shared. Over the limit → `429` + `Retry-After`. |
| Secure headers | `hono/secure-headers` plus `Content-Security-Policy: default-src 'none'`. |
| Notes | Free-text `note` is capped at 1000 characters (`400`). |

## Compliance / config

- **CORS is locked to `STRIDE_WEB_ORIGIN`** (default `http://localhost:5173`) —
  never `*`. The API serves the owner's own private Strava data, so it must not
  be world-readable (GOAL §4 owner-only visibility).
- Port from `STRIDE_API_PORT` (default `8720`), interface from `STRIDE_API_HOST`
  (default `127.0.0.1`); Anthropic key optional (prose
  enrichment only). Logs go to stderr with a structured logger.
