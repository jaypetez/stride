# 0009. One authorization policy module, proven by a route × role matrix

- Status: Accepted
- Date: 2026-11-01

## Context
D1 has no row-level security, so nothing at the database layer stops a route
from leaking another group's data. Stride Groups stores private health-adjacent
data: activities, heart rate, chat. One forgotten check is a breach.

## Decision
- **Single policy module:** every route handler calls `apps/cloud/src/policy`
  (e.g. `can(actor, 'activity:read', resource)`) before touching data. The
  policy module also enforces blocks and the Strava visibility/AI flags
  (ADR 0006).
- **Roles:** `anonymous`, `user`, `member`, `admin`, `owner`, `blocked`.
- **Matrix test (the authorization "guardrail test"):** it enumerates
  **every registered route** and **every role**, and asserts the expected
  allow/deny. It works in the same spirit as
  `packages/core/src/coach/guardrail.ts`.
  - A route that isn't in the matrix fails the test.
  - Adding a route without a matrix entry therefore cannot pass CI.
- **Scoped queries:** queries are scoped by `groupId` derived from the
  verified membership, never from client input alone.

## Consequences
- Every new endpoint must state its policy entry. Backlog issues require
  "added to the authz matrix".
- The matrix doubles as human-readable documentation of who can do what.
