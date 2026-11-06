---
"@stride/core": patch
---

Add a no-race "build my fitness" plan mode (MVP-026). `buildPlanSkeleton` and `generatePlan` take `kind: 'build'` for a 4-16 week block of base then build phases with a recovery week every 4th week and no peak, taper or race session, seeded from your last 4 weeks of training when available. The API (`POST /plan {kind: 'build', weeks}`), CLI (`stride plan --build --weeks N`), MCP `generate_plan` (`kind`) and the web plan card (Race | Build fitness toggle) can all create one, and the Android Plan tab offers the same toggle. Golden cases pin build-plan output in both engines.
