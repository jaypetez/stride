# @stride/core

## 0.2.7

### Patch Changes

- 61ab7c6: Batch dependency maintenance. Runtime: `hono` 4.13.5 → 4.13.7 (api, web),
  `@anthropic-ai/sdk` 0.122.0 → 0.124.0 (core), `@hono/zod-validator` 0.9.0 →
  0.9.1 (api), and `@clack/prompts` 1.7.0 → 1.8.0 (cli). Dev tooling: `vitest`
  and `@vitest/coverage-v8` 4.1.11 → 5.0.0, `@types/node` 26.2.0 → 26.5.0,
  `@playwright/test` 1.62.1 → 1.63.0, `@changesets/cli` 3.0.1 → 3.0.2, and
  `lint-staged` 17.4.1 → 17.5.0.
  
  `@clack/prompts` 1.8.0 pulls `@clack/core` 1.5.0, which retypes `isCancel` from
  `(value) => value is symbol` to `(value) => value is typeof CANCEL_SYMBOL`.
  TypeScript therefore no longer narrows `boolean | symbol` to `boolean` after the
  guard, so the PAR-Q screening loop in `stride profile` needed an explicit
  `boolean` assertion at one call site. Type-level only; no runtime behaviour
  changed.
- Updated dependencies [61ab7c6]
  - @stride/schemas@0.2.7

## 0.2.6

### Patch Changes

- ecfc597: Patch the `qs` transitive dependency to 6.16.0, clearing two moderate advisories
  (GHSA-4mjr-xmp4-gh2g denial of service via attacker-controlled `isBuffer`, and
  GHSA-x5fp-wj9c-mxmx array-limit bypass via bracket-key comma parsing). `qs` is
  reached through `express`/`body-parser` under `@modelcontextprotocol/sdk`, so it
  was in the runtime tree.
- Updated dependencies [ecfc597]
  - @stride/schemas@0.2.6

## 0.2.5

### Patch Changes

- chore: dependency maintenance
  
  Roll up the ten open Dependabot updates and patch three high-severity
  `fast-uri` advisories. No runtime behaviour changed.
  
  Runtime: `hono` 4.12.34 -> 4.13.5 (api, web), `@hono/node-server` 2.1.0 ->
  2.1.1 (api), `@anthropic-ai/sdk` 0.116.0 -> 0.122.0 (core),
  `@tanstack/react-query` 5.101.4 -> 5.102.8 (web), and `zod` 4.4.3 -> 4.5.4
  (catalog).
  
  Dev tooling: `@biomejs/biome` 2.5.7 -> 2.5.11, `@changesets/cli` 2.31.1 ->
  3.0.1, `@commitlint/cli` 21.2.1 -> 21.2.2, `@commitlint/config-conventional`
  21.2.0 -> 21.2.2, `vitest` and `@vitest/coverage-v8` 4.1.10 -> 4.1.11,
  `lint-staged` 17.3.0 -> 17.4.1, `tsx` 4.23.12 -> 4.23.13, `turbo` 2.10.9 ->
  2.10.12, `@testing-library/react` 16.3.2 -> 16.3.3, `@types/react-dom` 19.2.4
  -> 19.2.5, `@vitejs/plugin-react` 6.0.5 -> 6.1.1, `happy-dom` 20.11.2 ->
  20.12.0, and `vite` 8.2.1 -> 8.2.2.
  
  CI actions: `github/codeql-action` (init/analyze/upload-sarif) v4.37.6 ->
  v4.37.9, and `changesets/action` v1.9.0 -> v2.1.1, whose v2 renamed every
  input the release job passes and requires Changesets CLI v3.
  
  Security: the `fast-uri` override moves to ^3.1.6, clearing
  GHSA-f65p-4m7j-42xc, GHSA-fph4-wmhf-6fwf and GHSA-jqff-g426-hqxp. They reach
  the tree only through dev tooling (`@commitlint/cli` -> `@commitlint/load` ->
  `@commitlint/config-validator` -> `ajv`) but were failing the required audit
  gate on every open pull request.
- Updated dependencies
  - @stride/schemas@0.2.5

## 0.2.4

### Patch Changes

- chore: dependency maintenance

  Roll up the three open Dependabot updates. No runtime behaviour changed.

  Runtime: `@anthropic-ai/sdk` 0.115.0 -> 0.116.0 (core) and `@hono/node-server`
  2.0.12 -> 2.1.0 (api).

  Dev tooling: `@types/node` 26.1.2 -> 26.2.0, `tsx` 4.23.5 -> 4.23.12, `turbo`
  2.10.8 -> 2.10.9, `@testing-library/jest-dom` 7.0.0 -> 7.0.1, `happy-dom`
  20.11.1 -> 20.11.2, and `vite` 8.2.0 -> 8.2.1.

- Updated dependencies
  - @stride/schemas@0.2.4

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
  - @stride/schemas@0.2.3

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
  - @stride/schemas@0.2.1

## 0.2.0

### Minor Changes

- 1b5a68b: Durable daily-load persistence, incremental Strava sync, and rate limiting.

  Persist a derived per-day training-load series (`daily-loads.json`) forever as the
  single source of truth for the PMC/ACWR (GOAL.md §7), while raw Strava activities
  keep expiring at the 7-day cache limit (§4). Add a new `SyncState` (`meta.json`)
  schema, atomic + mutex-guarded store writes, a cross-process advisory sync lock,
  and `~`/`$HOME`/`%USERPROFILE%` expansion for `STRIDE_DATA_DIR`. Rewrite
  `syncStrava` with incremental/backfill/rebuild modes, migration seeding, deletion
  reconciliation, and a rate-limit-aware Strava client (proactive throttling,
  429 retry with `Retry-After`, graceful partial-result degradation). The read path
  (coach context, API `/pmc` + analyze/next/plan, MCP tools, CLI) now reads the
  durable series for live data, so fitness/fatigue survives the 7-day raw prune.

- 671fa6c: Modernize the Claude coach and enforce the safety layer.

  - Real proposal→repair→reject plan loop (Option A): when an LLM is present, `generatePlan` asks Claude for a STRUCTURAL plan via structured outputs (`messages.parse` + `zodOutputFormat`, new `LlmPlanProposal` schema — phases and per-day type/emphasis/rationale, no numbers), then deterministically materializes each session with `makeSession` and runs the hardened `validatePlan`→`repairPlan`→re-validate loop. Valid → return; repaired-to-valid → return (`repaired: true`); unrepairable/refused/empty → reject and fall back to the always-valid skeleton. The no-key path is untouched, so golden snapshots stay byte-identical.
  - Anthropic layer redesign: the `CoachLLM` seam now returns rich results (`CompleteResult`/`ParseResult`/`ClassifyResult`), with prompt caching (`cache_control` ephemeral on an expanded frozen persona), streaming for interactive paths, structured outputs for plans, adaptive thinking + per-tier `effort` (plan/opus=high, chat/sonnet=medium, classify/haiku=none), a tool runner over the shared toolset, refusal/truncation handling (discard model output → deterministic fallback), and usage/`request_id` audit logging (`coach/llm-log.ts`).
  - Shared read-only toolset (`coach/tools.ts`): a `CoachDataProvider` interface plus the five §8 fact tools, reused by both the coach tool runner and the MCP server so MCP and the coach expose byte-identical facts. MCP is now a thin adapter over the core toolset (adds `get_next_workout_inputs` and `get_plan_context`), emits the disclaimer, and accepts a `note` input.
  - Safety enforcement: a `disclaimer` is attached to every coach output (including the offline path); `analyzeWorkout` halts on a `stop` red flag (skips the LLM) like `suggestNextWorkout`; a `note` free-text param is threaded into `detectRedFlags` so `STOP_KEYWORDS` fire; a PAR-Q screening helper is exported; and an optional Haiku `classify` second pass augments WARNING-level flags on free text while keyword rules stay authoritative for `stop`.

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

- d355613: Harden the training-plan guardrail into a true repair-or-reject enforcer. The
  ramp check now uses GOAL §7's CTL cap (5–7 pts/week by experience) when the
  athlete's current fitness is known — projecting the plan's per-session TSS
  through the PMC EWMA — and falls back to the week-over-week TSS ratio only at
  cold start. `rest_minimum` and `long_run_cap` are promoted from advisory
  warnings to repairable errors, and `repairPlan` now also inserts a weekly rest
  day (converting the lowest-load non-quality day), caps an oversized long run,
  and scales a loading week down until its ramp is within cap; a `repaired` flag
  surfaces on `PlanValidation`. The deterministic demo plan still passes unchanged.
  Also tightens `CoachContext.recentActivities` to use the real `SportType` and
  `LoadMethod` enums instead of bare strings, and adds schema round-trip contract
  tests so core outputs can never silently drift from `@stride/schemas`.
- 6507f11: Harden the local OAuth connect flow and wire up deauthorization on disconnect.
  The CLI callback server now binds `127.0.0.1` only (previously all interfaces,
  so the one-time OAuth `code` was reachable from the LAN) and times out after 5
  minutes instead of hanging forever if the browser flow is abandoned, always
  closing the listener on success, failure, or timeout. The loopback wait is
  extracted into a testable `waitForOAuthCode` helper that preserves the CSRF
  `state` check. `stride disconnect` now calls `@stride/core`'s `deauthorize`
  (exposed on the package's public surface) best-effort to revoke the grant on
  Strava's side before deleting local tokens, in both the normal and `--purge`
  paths; a failed or offline revoke warns and continues rather than blocking
  local cleanup.
- d89eaa9: Fix three sports-science correctness defects: ACWR no longer raises false
  `very_high` injury flags during the first ~4 weeks of history (warm-up window +
  seeded EWMAs + low-chronic reliability guard); the PMC (fitness/fatigue/form) is
  now projected to the reference day so fatigue decays over rest days instead of
  freezing at the last activity; and treadmill (`trainer`) activities no longer
  drive pace-based rTSS or VDOT anchors (their belt-estimated distance is
  untrustworthy), falling back to heart-rate or duration.
- Updated dependencies [19e260c]
  - @stride/schemas@0.2.0

## 0.1.0

### Minor Changes

- Establish the 0.1.0 baseline: a local-first Strava AI running coach with a
  deterministic sports-science engine (load → PMC → ACWR → zones), a Claude coach,
  and four surfaces (CLI, HTTP API, web UI, MCP server) over one shared core.

### Patch Changes

- @stride/schemas@0.1.0
