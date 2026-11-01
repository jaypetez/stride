# 0005. Group mode: a hosted, multi-user Stride on Cloudflare

- Status: Accepted
- Date: 2026-11-01

## Context
Stride was built as a single-user, local-first coach (GOAL.md §2–§3). The
maintainer now wants **Stride Groups**: small private groups of friends and
family who train on a shared program, see each other's runs, comment and chat,
and use the AI coach. A native Android app is the client (ADR 0007).

That needs a hosted backend with accounts, group-scoped access control,
real-time chat and push. We compared Supabase (Postgres + RLS + Realtime),
Firebase and Cloudflare. The maintainer chose an **all-Cloudflare** stack.

## Decision
Add `apps/cloud`, a Hono app on **Cloudflare Workers**, as a pnpm workspace
package (so `pnpm check` covers it):

| Concern | Choice |
|---|---|
| Relational data | **D1** with Drizzle ORM and SQL migrations |
| Group chat | One **Durable Object** (`GroupRoom`) per group: hibernatable WebSockets, DO SQLite storage (per-group isolation) |
| Async work | **Queues** (Strava webhooks, recompute, FCM fan-out) and **Cron Triggers** (token refresh, cache sweep, deletion jobs) |
| Files | **R2**, reserved for photos (parked) |
| Abuse | Workers **Rate Limiting** binding |
| Auth | Better Auth on Workers + D1 (ADR 0008) |
| Push | FCM HTTP v1 signed with WebCrypto (needs a free Firebase project, not the Play Store) |

Domain logic is **reused, not rewritten**: the Worker imports the
`@stride/core/science` and `@stride/core/coach` subpaths. The root `@stride/core`
index pulls in `node:fs` (the local store), so the Worker must not import it.
Compute-in-code / reason-in-LLM (GOAL.md §3) is unchanged.

The CLI, local API, web and MCP surfaces keep working single-user and offline.

## Consequences
- Cloudflare has **no row-level security**: every authorization decision goes
  through one policy module with an exhaustive route × role matrix test (ADR 0009).
- Everything runs offline in CI via Miniflare (`@cloudflare/vitest-pool-workers`)
  and `wrangler dev` with a seeded local D1.
- GOAL.md §2's "no multi-user hosting / no cross-user features" non-goals are
  lifted for Stride Groups; see the amended GOAL.md and ADR 0006 for what that
  means for Strava.
- New human prerequisites: a Cloudflare account and API token, a Firebase project.
