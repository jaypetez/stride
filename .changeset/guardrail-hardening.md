---
"@stride/core": patch
---

Harden the training-plan guardrail into a true repair-or-reject enforcer. The
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
