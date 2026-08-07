# @stride/web

## 0.2.3

### Patch Changes

- chore: dependency maintenance and transitive security patches

  Patch three high-severity advisories reachable only through dev tooling, each of
  which was held in place by one of our own resolution pins. `fast-uri`
  (GHSA-7p8r-x3mc-p8w7, host confusion via backslash) was pinned by an existing
  override to `^3.1.4` while the fix shipped in 3.1.5 — the override itself was
  holding the vulnerable version. `js-yaml` (GHSA-5p4m-2wfm-xmqj / CVE-2026-59870,
  quadratic CPU in `!!omap` resolution) is reached through `@changesets/cli` on
  both the 3.x and 4.x lines, so each major is pinned separately rather than
  forced onto one.

  Runtime: `hono` 4.12.32 -> 4.12.34.

  Dev tooling: `@biomejs/biome` 2.5.6 -> 2.5.7, `lint-staged` 17.1.1 -> 17.3.0,
  `tsx` 4.23.1 -> 4.23.5, `turbo` 2.10.7 -> 2.10.8, `@playwright/test` 1.62.0 ->
  1.62.1, `@types/react` 19.2.17 -> 19.2.18, `@types/react-dom` 19.2.3 -> 19.2.4,
  `@vitejs/plugin-react` 6.0.4 -> 6.0.5, and `vite` 8.1.5 -> 8.2.0.

  CI actions: `github/codeql-action` (init/analyze/upload-sarif) v4.37.3 ->
  v4.37.6 and `pnpm/action-setup` 6.0.9 -> 6.0.10.

  No runtime behaviour changed; `pnpm audit --audit-level=high` is clean.

- Updated dependencies
  - @stride/core@0.2.3
  - @stride/schemas@0.2.3
  - @stride/api@0.2.3

## 0.2.2

### Patch Changes

- Update dependencies to their latest releases (via Dependabot):

  - `@anthropic-ai/sdk` 0.111.0 → 0.115.0
  - `hono` 4.12.30 → 4.12.32
  - `@hono/node-server` 2.0.10 → 2.0.12
  - `@modelcontextprotocol/sdk` 1.29.0 → 1.30.0
  - `@tanstack/react-query` 5.101.2 → 5.101.4, and `react` / `react-dom` 19.2.7 → 19.2.8
  - dev tooling: `@biomejs/biome`, `@types/node`, `@playwright/test`,
    `@testing-library/jest-dom` (6 → 7), `@vitejs/plugin-react`, `happy-dom`,
    `lint-staged`, and `turbo`
  - CI actions: `actions/checkout` v7.0.1, `github/codeql-action` v4.37.3, and
    `ossf/scorecard-action` v2.4.4
  - a pnpm `overrides` entry pins `fast-uri` to ^3.1.4, clearing GHSA-v2hh-gcrm-f6hx
    from the required `audit` gate

- Updated dependencies
  - @stride/core@0.2.2
  - @stride/api@0.2.2
  - @stride/schemas@0.2.2

## 0.2.1

### Patch Changes

- Update dependencies to their latest releases (via Dependabot):

  - `@anthropic-ai/sdk` 0.110.0 → 0.111.0
  - `hono` 4.12.28 → 4.12.30
  - `@hono/node-server` 2.0.8 → 2.0.10
  - `@hono/zod-validator` 0.8.0 → 0.9.0
  - dev tooling: the `dev-dependencies` group (6 updates)
  - CI actions: `pnpm/action-setup`, `actions/setup-node`, and `github/codeql-action` (init/analyze/upload-sarif)

- Updated dependencies
  - @stride/core@0.2.1
  - @stride/api@0.2.1
  - @stride/schemas@0.2.1

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
