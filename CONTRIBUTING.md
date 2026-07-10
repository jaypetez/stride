# Contributing to Stride

Thanks for your interest in improving Stride! This guide explains how to get set
up and the conventions we follow.

## Ground rules (read `GOAL.md` first)

Stride has a few non-negotiable design rules described in [`GOAL.md`](GOAL.md).
The most important ones for contributors:

1. **Compute-in-code, reason-in-LLM.** All numbers (training load, fitness,
   fatigue, form, zones, projections) are computed by deterministic code in
   `packages/core`. The LLM never computes or predicts numbers. If you find
   yourself asking Claude for a number, stop and add a tested function to
   `packages/core` instead.
2. **Every plan feature ships with a guardrail test.** The deterministic
   plan validator (ramp caps, rest minimums, no back-to-back hard days,
   long-run cap) is not optional.
3. **Respect Strava's API terms by construction.** Read-only scopes,
   owner-only data, 7-day cache expiry, rate-limit handling, and attribution
   in any UI. See the compliance section of `GOAL.md`.

## Development setup

Prerequisites: **Node.js 22+** and **pnpm 10+** (`corepack enable`).

```bash
git clone https://github.com/jaypetez/stride.git
cd stride
pnpm install
cp .env.example .env    # fill in your own Strava + Anthropic credentials

pnpm check        # the gate: lint -> typecheck -> test -> build (run before committing)
pnpm verify       # runtime smoke: boots API + MCP + CLI, offline, and asserts
pnpm test         # run unit/integration tests
pnpm lint         # Biome lint + format check
pnpm format       # auto-fix formatting
```

A Husky pre-commit hook runs the gate automatically. Run the CLI in dev without a build:

```bash
pnpm --filter @stride/cli dev -- analyze --demo
```

> AI coding agents: [AGENTS.md](AGENTS.md) is the machine-readable command
> manifest, conventions, and gotchas — read it first.

## Repository layout

```
apps/
  cli/   commander + @clack/prompts CLI
  api/   Hono HTTP API
  web/   Vite + React dashboard
  mcp/   Model Context Protocol server
packages/
  core/     domain logic: sports-science engine, Strava client, Claude coach
  schemas/  Zod schemas — the single source of truth for types
  config/   shared TypeScript config
```

Domain logic lives in `packages/core`; each app is a thin adapter that imports
it. **Do not duplicate domain logic into an app.**

## Pull requests

- Branch from `main`; keep PRs focused on one change.
- Add or update tests for anything with runtime behavior.
- Ensure `pnpm build`, `pnpm typecheck`, `pnpm test`, and `pnpm lint` all pass.
- We use [Conventional Commits](https://www.conventionalcommits.org/) for commit
  messages (`feat:`, `fix:`, `docs:`, `chore:`, …).
- We use the [Developer Certificate of Origin](https://developercertificate.org/).
  Sign off your commits with `git commit -s` (adds a `Signed-off-by` trailer).

## Reporting bugs & requesting features

Use the GitHub issue templates. For security issues, follow
[SECURITY.md](SECURITY.md) instead of opening a public issue.
