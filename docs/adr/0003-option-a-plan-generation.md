# 0003. Option A plan generation: LLM proposes, code computes, guardrail enforces

- Status: Accepted
- Date: 2026-07-14

## Context

[`GOAL.md`](../../GOAL.md) §3 and §8 set two rules that a plan generator has to
satisfy simultaneously:

- **Compute-in-code.** The LLM must never produce numbers — durations, paces, HR
  zones, load, distances.
- **Deterministic guardrails.** JSON Schema (used for structured LLM output)
  cannot express numeric bounds, so a code validator must enforce ramp caps,
  rest minimums, spacing between hard days, and a long-run cap; a violating plan
  is repaired or rejected.

A naive "ask the model for a full plan with all the numbers" approach violates
the first rule and makes the second one do all the work. We want the model's
genuine value (sensible *structure* and periodization narrative) without letting
it touch a single number.

## Decision

Adopt **Option A**: the LLM proposes only *structure*; code computes every
number; a deterministic guardrail repairs or rejects. Implemented across
`packages/core/src/coach/{coach,planner,guardrail}.ts`:

1. **Propose (structure only).** `generatePlan` asks the model — via structured
   outputs validated by the `LlmPlanProposal` Zod schema — for a per-week
   `phase` and, per day, a `workoutType` + a one-line `rationale`. No numbers are
   requested or accepted.
2. **Materialize (numbers in code).** `materializeProposal` turns that skeleton
   into a real plan: for each day it calls `makeSession(type, duration, threshold
   …)`, which derives duration, target pace, HR zone, TSS, and distance from the
   athlete's anchors. Durations come from a fixed per-type table in code. Every
   number is computed here.
3. **Enforce (validate → repair → re-validate).** `runGuardrail` runs
   `validatePlan`; if invalid it runs `repairPlan` (downgrade the later of two
   consecutive hard days to easy, insert a rest day, cap an oversized long run,
   scale a loading week down until its ramp is within cap) and re-validates. A
   valid or repaired-to-valid plan is returned; an **unrepairable, refused, or
   empty proposal is rejected** in favor of the always-valid deterministic
   `buildPlanSkeleton`.
4. **Enrich prose only.** A final optional LLM pass may replace the plan's
   `summary` text; it never changes numbers.

When no `ANTHROPIC_API_KEY` is configured the proposal loop is skipped entirely
and `buildPlanSkeleton` is used directly, so offline output stays byte-identical
(the golden tests depend on this).

The guardrail ramp check is context-aware: with a seeded CTL/ATL it projects the
plan's per-session TSS through the PMC EWMA and caps the **CTL rise per week**
(GOAL §7, `ctlRampCap` by experience level); without athlete context it falls
back to a week-over-week TSS ratio cap.

## Consequences

**Easier:**

- The compute-in-code rule holds by construction — the model literally cannot
  emit a number into the plan.
- Safety is independent of model quality: a bad or adversarial proposal is
  bounded by the validator and, at worst, replaced by the deterministic
  skeleton. The result always carries a `PlanValidation` (with `repaired` and
  the list of violations) for auditability.
- Offline and online paths converge on the same numeric machinery.

**Harder / watch out for:**

- Two code paths (proposal-materialized vs skeleton) must both stay
  guardrail-clean; every plan feature ships with a guardrail test (AGENTS.md,
  CONTRIBUTING.md).
- `makeSession` is the sole place session numbers are minted; repair helpers
  rebuild sessions through it (`rescaleSession`) so numbers are never hand-edited
  mid-repair.
- Repair loops are iteration-bounded (`MAX_REPAIR_ITERS`) so they always
  terminate even on a pathological proposal.
