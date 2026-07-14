# 0002. Durable derived daily-load series beyond the 7-day raw cache

- Status: Accepted
- Date: 2026-07-14

## Context

Two [`GOAL.md`](../../GOAL.md) constraints pull in opposite directions:

- **§4 — 7-day cache limit.** Strava's API Agreement forbids caching Strava data
  longer than 7 days, and requires removing a resource promptly once it
  disappears upstream.
- **§7 — the daily TSS series is the single source of truth** for everything
  downstream: the Performance Management Chart (CTL is a **42-day** EWMA) and the
  EWMA-ACWR (a **28-day** chronic window). A coach that can only see 7 days of
  history has no fitness (CTL) signal at all.

A 42-day fitness metric computed from a 7-day cache would collapse to near-zero.
So we need history that outlives the raw cache without violating the cache rule.

The resolution rests on a distinction Strava's terms draw between *raw data* and
*derived analysis*: the per-day training-load aggregate (a TSS number, plus
duration/distance totals) is a **scientific derivative**, not Strava content. It
contains no activity payloads, GPS, or streams.

## Decision

Persist a **durable derived series** in `daily-loads.json` that is never
subject to the 7-day expiry, while raw Strava activities (`activities.json`)
keep expiring at 7 days. Concretely, in `packages/core`:

- `sync.ts` orders each pass so a day's **derived aggregate is written before its
  raw data is pruned**: fetch → enrich → merge raw → `pruneExpiredStrava(nowMs,
  7)` → reconcile deletions → **recompute + upsert the daily-load series**. The
  recompute runs every sync for every still-live day, so a day's durable entry
  is always current before its raw activity expires. A migration seed derives the
  series from existing raw on first upgrade so the PMC never collapses to zero.
- `store.upsertDailyLoads(recomputed, retentionCutoffDate, authoritativeDates?)`
  merges the freshly recomputed dates over the durable series and preserves all
  older days. Two compliance details live here:
  - Once a day freezes past the raw-retention window (`date <
    retentionCutoffDate`), its raw activities no longer exist locally, so the
    `activityIds` back-references are dropped — only the derived scalar aggregate
    remains.
  - `authoritativeDates` handles upstream deletions: if reconciliation emptied a
    day, that date is marked authoritative and, because the recompute produced no
    entry for it, its stale durable entry is **deleted** rather than left as
    phantom load.
- The read path (`buildCoachContext`) prefers the durable series for the long
  PMC/ACWR history and reads the still-fresh raw activities only for recent
  (≤7-day) windows. The API `/pmc` and MCP tools do the same.

## Consequences

**Easier:**

- Fitness/fatigue/form survive the 7-day raw prune — CTL is meaningful, plans
  and next-workout suggestions work off a real training history.
- `stride next` still works after the raw cache has fully expired (the CLI only
  bails when *both* raw activities and the durable series are empty).

**Harder / watch out for:**

- The sync step ordering is load-bearing and documented as such in `sync.ts`;
  reordering the prune before the recompute would silently lose a day's load.
- Deletion handling needs the `authoritativeDates` escape hatch because a normal
  merge can only ever overwrite a date it contains, never remove one.
- The durable series is a derived aggregate by construction — do not add raw
  Strava fields to `DailyLoad`, or it stops being compliant to persist.
- Long-term, GOAL §9 notes the fully compliant durable source is user FIT/GPX
  upload; this series is the interim, terms-respecting bridge.
