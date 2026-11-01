# Architecture Decision Records

An ADR captures a single significant decision: its context, the decision taken,
and the consequences. They are immutable once accepted — a later decision that
reverses one is a new ADR that supersedes it, rather than an edit.

These records document the non-obvious calls this codebase actually made,
several of which are deliberate deviations from the initial plan in
[`GOAL.md`](../../GOAL.md). Where a record and the code disagree, the **code is
the source of truth** — please open a PR updating the ADR.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-raw-ts-workspace-consumption.md) | Raw-`.ts` workspace consumption (no TS project references) | Accepted |
| [0002](0002-durable-daily-load-series.md) | Durable derived daily-load series beyond the 7-day raw cache | Accepted |
| [0003](0003-option-a-plan-generation.md) | Option A plan generation: LLM proposes, code computes, guardrail enforces | Accepted |
| [0004](0004-advisory-sync-lock.md) | Advisory sync lock and the CodeQL dismissal rationale | Accepted |
| [0005](0005-group-mode-hosted-cloudflare.md) | Group mode: a hosted, multi-user Stride on Cloudflare | Accepted |
| [0006](0006-data-sources-and-strava-risk.md) | Data sources for Stride Groups, and the accepted Strava risk | Accepted |
| [0007](0007-android-architecture.md) | Native Android app architecture | Accepted |
| [0008](0008-auth.md) | Authentication for Stride Groups | Proposed |
| [0009](0009-authorization-policy.md) | One authorization policy module, proven by a route × role matrix | Accepted |

## Template

Each ADR uses a short standard format:

```markdown
# NNNN. Title

- Status: Proposed | Accepted | Superseded by ADR-XXXX
- Date: YYYY-MM-DD

## Context
What forces are at play — the problem, constraints, and any relevant GOAL.md
section.

## Decision
The choice made, stated plainly.

## Consequences
What becomes easier and what becomes harder as a result.
```
