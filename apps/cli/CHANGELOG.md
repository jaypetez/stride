# @stride/cli

## 0.2.1

### Patch Changes

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

- 71d11c3: Resolve 10 bugs found by the adversarial bug-hunt sweep.

  HIGH:

  - Sync: a rate-limit-truncated `rebuild` no longer wholesale-replaces (and wipes)
    the durable daily-load series — it only replaces wholesale when the fetch
    actually completed, otherwise it falls back to the safe merge that preserves
    existing PMC history.
  - Sync: an incremental sync no longer advances the `lastSyncedAt` watermark when
    its fetch was rate-limit-truncated, so gap activities are re-fetched next run
    instead of being orphaned.
  - Planner: `makeSession` for a `paceIf === 0` type (e.g. `cross_training`) no
    longer produces an `Infinity` target pace (which serialized to `null` and made
    the saved plan fail schema re-parse); the pace field is omitted and distance
    is 0.

  MEDIUM:

  - Guardrail: the back-to-back-hard repair now recovers the athlete's real
    threshold from the day's session instead of a hardcoded default, so the
    downgraded easy day is paced/sized correctly.
  - Coach: `generatePlan` now halts on a STOP red flag, returning a safe all-rest
    plan with the referral message instead of a full training block.
  - Store lock: an empty/mid-write lock file is no longer reclaimed immediately; it
    is only reclaimed after a short grace period, preventing two processes from
    both acquiring the lock.
  - Science: hrTSS from a stream now integrates over moving time (via the `moving`
    stream or by scaling elapsed→moving), consistent with the rTSS/avg-HR paths.
  - Verify: `scripts/smoke.mjs` scrubs `ANTHROPIC_API_KEY` and `STRAVA_*` from
    child process env so demo/verify paths stay offline and deterministic.
  - CLI: `plan --json` now includes `flags` and `disclaimer` (parity with
    `analyze --json` / `next --json`).

  LOW:

  - Science: time-in-zone (HR and pace) skips non-moving samples when a `moving`
    stream is present, so stops no longer inflate easy%/zone seconds.

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
