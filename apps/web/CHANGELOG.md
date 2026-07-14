# @stride/web

## 0.2.0

### Minor Changes

- c682989: Wire safety + a typed client across the CLI, API, and web surfaces.

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

### Patch Changes

- Updated dependencies [71d11c3]
- Updated dependencies [1b5a68b]
- Updated dependencies [19e260c]
- Updated dependencies [d355613]
- Updated dependencies [671fa6c]
- Updated dependencies [6507f11]
- Updated dependencies [d89eaa9]
- Updated dependencies [c682989]
  - @stride/core@0.2.0
  - @stride/schemas@0.2.0
  - @stride/api@0.2.0

## 0.1.0

### Patch Changes

- @stride/schemas@0.1.0
