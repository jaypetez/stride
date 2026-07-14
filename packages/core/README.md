# @stride/core

Stride's shared domain logic — the deterministic sports-science engine, the
Strava client, the local store, and the Claude coach. **Every surface (CLI, API,
web, MCP) is a thin adapter over this package**; domain logic lives here and is
never duplicated into an app.

Ships as **raw TypeScript** (no build step); consumers bundle it via tsup
`noExternal: [/^@stride\//]`. See
[ADR 0001](../../docs/adr/0001-raw-ts-workspace-consumption.md).

## The one rule

> Numbers are computed here; the LLM only writes prose over them. If you need a
> number, add a tested function to this package — never ask the model for it.

## Public surface

Exported from the root (`@stride/core`) and via subpath entrypoints:

| Import | Contents |
|---|---|
| `@stride/core/science` | Sports-science engine — every metric Stride reports |
| `@stride/core/strava` | Rate-limit-aware Strava client, OAuth, payload mappers |
| `@stride/core/store` | Local-first JSON store + advisory sync lock |
| `@stride/core/coach` | Coach (analyze/next/plan), guardrail, safety, tools, prompts |
| `@stride/core/config` | `loadConfig(env)`, model tiers, `assert*Configured` |
| `@stride/core` (root) | all of the above, plus `sync.ts`, `fixtures.ts`, `log.ts` |

Key entry points:

- **Science** — `computeActivityMetrics` / `computeActivityLoad` (rTSS →
  TRIMP/hrTSS → duration fallback), `buildPmcSeries` (CTL/ATL/TSB),
  `buildAcwrSeries` (EWMA-ACWR), `computeZones` (HR + pace), `estimateAnchors`,
  `toDailyLoads`, `efficiencyFactor`, `aerobicDecoupling`, VDOT helpers.
- **Coach** — `analyzeWorkout`, `suggestNextWorkout`, `generatePlan`,
  `buildCoachContext`; `validatePlan` / `repairPlan`; `detectRedFlags`,
  `screenReadiness`, `PARQ_QUESTIONS`, `DISCLAIMER`; `COACH_TOOLS` /
  `runCoachTool` (the frozen read-only fact toolset shared with MCP).
- **Sync / store** — `syncStrava`, `LocalStore`, `StravaClient`, OAuth flow.
- **Fixtures** — `DEMO_PROFILE`, `demoActivity()`, `demoHistory()` for offline
  demos and tests.

## Run it offline

No credentials or network needed:

```bash
pnpm --filter @stride/core test         # unit tests (science, coach, store, sync)
pnpm --filter @stride/core typecheck    # tsc --noEmit
```

The Claude coach degrades gracefully: with no `ANTHROPIC_API_KEY`, all three
coach functions return deterministic, computed output (templated prose + a
code-built plan skeleton), which is what makes the whole package exercisable
offline. Provide a key only to enrich the prose.

## Design docs

- [Architecture](../../docs/architecture.md)
- ADRs: [durable daily-load series](../../docs/adr/0002-durable-daily-load-series.md),
  [Option A plan generation](../../docs/adr/0003-option-a-plan-generation.md),
  [advisory sync lock](../../docs/adr/0004-advisory-sync-lock.md)
- Sports-science spec: [`GOAL.md`](../../GOAL.md) §7
