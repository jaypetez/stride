---
"@stride/core": patch
---

Plans now start from your last 4 weeks of training (MVP-025). A new `fitnessSeed` summarizes recent running (weekly minutes, longest run, recent VDOT, run count); `buildPlanSkeleton` takes it as an optional `seed` that sets week-1 volume, the first long run and paces, then grows at most 10% per loading week within the guardrails. `generatePlan` seeds from the coach context and returns the seed alongside the plan. The Android engine mirrors it, pinned by new science goldens.
