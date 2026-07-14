---
"@stride/api": minor
"@stride/cli": minor
"@stride/web": minor
---

Wire safety + a typed client across the CLI, API, and web surfaces.

API: route every failure through `onError` so all error responses share the
`{ error, requestId }` envelope with a matching `x-request-id` header and the
right status (rate-limit 429, Strava 502, sync-lock 409, else 500), including the
404 branches and zValidator failures (custom hook). Lock CORS to the web origin
(`STRIDE_WEB_ORIGIN`, default `http://localhost:5173`) instead of `*`. Thread a
safety `note` through `/analyze/:id`, `/next`, and `/plan`, surface the coach
`disclaimer`/`flags` in the JSON, and add `POST /profile/screening` (PAR-Q via
`screenReadiness`, persisting `medicalClearance`/`healthFlags`). Routes are chained
so `AppType` carries the schema for RPC.

CLI: add `--note` to `analyze`/`next`/`plan` and thread it into the coach; print
the coach `disclaimer` from the result (not a hard-coded string) and show safety
`flags` first, with a prominent STOP banner; offer PAR-Q onboarding in `profile`
(interactive-only, TTY/`--json`-safe); guard `--weeks` against `NaN`.

Web: consume the API through Hono's typed `hc` client off `@stride/api`'s
`AppType` (no hand-maintained response interfaces), reuse `formatPace`/
`formatDuration` from `@stride/core/science`, and replace the plain-text
attribution with a compliant styled "Powered by Strava" badge (Strava orange, no
fabricated logo) while keeping the "View on Strava" links.
