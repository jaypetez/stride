# @stride/schemas

The **single source of truth** for Stride's domain types. Every schema is a
[Zod](https://zod.dev) object; the inferred TypeScript type is exported under the
same name. These schemas are reused by `@stride/core`, the CLI, the API
(request/response validation via `zValidator`), the MCP server (tool input
schemas), and the web UI — so one definition drives runtime validation *and*
end-to-end types.

Ships as **raw TypeScript** (no build step); see
[ADR 0001](../../docs/adr/0001-raw-ts-workspace-consumption.md).

## Public surface

Everything is exported from the root (`@stride/schemas`). Grouped by file:

- **`activity`** — `Activity`, `ActivitySummary`, `ActivityStreams`,
  `ActivityMetrics`, `DailyLoad`.
- **`athlete`** — `AthleteProfile` (the persistent athlete model: anchors,
  experience, `medicalClearance`, `healthFlags`), `RaceGoal`.
- **`metrics`** — `PmcPoint`, `AcwrPoint`, `AcwrFlag`, `Zones`, `HrZone`,
  `PaceZone`, `ZoneDistribution`.
- **`coach`** — `CoachContext`, `AnalysisResult`, `NextWorkoutResult`,
  `WorkoutSuggestion`, `TrainingPlan`, `PlanWeek`, `PlanDay`, `PlanResult`,
  `PlanValidation`, `GuardrailViolation`, `RedFlag`, `LlmPlanProposal` (+
  `LlmPlanWeek` / `LlmPlanDay`), and the read-only tool input schemas
  (`GetTrainingLoadInput`, `GetRecentActivitiesInput`, `GetPaceZonesInput`,
  `GetNextWorkoutInputsInput`, `GetPlanContextInput`).
- **`enums`** — `SportType`, `WorkoutType`, `IntensityLabel`, `PlanPhase`,
  `ExperienceLevel`, `RaceDistance`, `Sex`, `Units`, `LoadMethod`,
  `RedFlagSeverity`, `ActivitySource`.
- **`sync`** — `SyncState` (durable sync bookkeeping), `SYNC_SCHEMA_VERSION`.

Also exports `STRIDE_SCHEMAS_VERSION`.

## Run it offline

```bash
pnpm --filter @stride/schemas test        # schema round-trip / default tests
pnpm --filter @stride/schemas typecheck
```

## Conventions

- Prefer parsing at boundaries: `AthleteProfile.parse({})` fills defaults;
  `RaceGoal.parse({ distance: '10k' })` normalizes a goal.
- The `LlmPlanProposal` schema deliberately carries **structure only** (phase,
  workout type, rationale) and **no numbers** — the coach materializes numbers in
  code (see [ADR 0003](../../docs/adr/0003-option-a-plan-generation.md)).
