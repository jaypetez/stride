---
"@stride/core": patch
"@stride/cli": patch
---

Resolve 10 bugs found by the adversarial bug-hunt sweep.

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
