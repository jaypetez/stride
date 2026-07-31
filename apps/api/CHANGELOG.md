# @stride/api

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

- 19e260c: docs: architecture, ADRs, per-package READMEs, and worked examples

  Add `docs/architecture.md` (three-layer model + data flow + diagram), four ADRs
  under `docs/adr/` (raw-`.ts` workspaces, durable daily-load series, Option A plan
  generation, advisory sync lock), per-package READMEs for core/schemas/config and
  the cli/api/mcp apps, and a runnable `examples/` directory with real,
  byte-reproducible offline command output. README updated with `doctor`/`profile`,
  the `--note` flag and PAR-Q screening, a Documentation section, and an OpenSSF
  Best Practices badge placeholder. Docs-only; no runtime code changed.

- Updated dependencies [71d11c3]
- Updated dependencies [1b5a68b]
- Updated dependencies [19e260c]
- Updated dependencies [d355613]
- Updated dependencies [671fa6c]
- Updated dependencies [6507f11]
- Updated dependencies [d89eaa9]
  - @stride/core@0.2.0
  - @stride/schemas@0.2.0

## 0.1.0

### Patch Changes

- Updated dependencies
  - @stride/core@0.1.0
  - @stride/schemas@0.1.0
