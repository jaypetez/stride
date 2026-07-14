# @stride/core

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
