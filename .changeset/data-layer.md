---
"@stride/core": minor
---

Durable daily-load persistence, incremental Strava sync, and rate limiting.

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
