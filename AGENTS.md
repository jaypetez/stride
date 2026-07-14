# AGENTS.md

Machine-readable guide for AI coding agents working in this repo. Humans: see
[`README.md`](README.md), [`CONTRIBUTING.md`](CONTRIBUTING.md), and the full
project brief in [`GOAL.md`](GOAL.md). When this file and a code comment
disagree, trust the code and update this file.

Stride is a **local-first, offline-capable** Strava AI running coach. An agent
can run the **entire inner loop** — edit → lint → typecheck → test → build →
run/verify → observe — locally with **no secrets and no network**.

## Setup

- Node.js **>= 22** (repo dev uses 24; CI uses 22), pnpm **10** (`corepack enable`).
- `pnpm install` — deps are committed in the lockfile; esbuild is pre-approved.
- No `.env` is required for tests, demos, or the gate. Copy `.env.example` → `.env`
  only for *live* Strava/Anthropic paths.

## Commands (source of truth)

| Command | What it does |
|---|---|
| `pnpm check` | **The gate.** lint → typecheck → test → build, fail-fast. Run before committing. |
| `pnpm verify` | **Runtime smoke** (`scripts/smoke.mjs`): boots the API on a real socket, drives the MCP stdio protocol, runs the CLI — all offline — and asserts. The deterministic verifier. |
| `pnpm lint` / `pnpm format` | Biome check / autofix (whole repo, incl. CLI). |
| `pnpm typecheck` | `tsc --noEmit` per package (Turbo). |
| `pnpm test` | Vitest per package (Turbo). Covers schemas, core, cli, api, mcp, web. |
| `pnpm test:watch` | `turbo watch test` — re-runs on change. |
| `pnpm test:affected` | Only packages changed vs `origin/main`. |
| `pnpm coverage` | Vitest v8 coverage per package. |
| `pnpm build` | tsup (cli/api/mcp) + vite (web) → runnable `dist/`. |
| Per package | `pnpm --filter @stride/<name> <script>` (e.g. `... @stride/core exec vitest run`). |

Prefer `pnpm check` + `pnpm verify` as the two commands that gate a change.

## Running each surface offline (no credentials)

- **CLI:** `pnpm --filter @stride/cli dev -- analyze --demo` (also `next --demo`, `plan --demo --race 10k --weeks 8`, `profile`, `doctor`). Add `--json` for machine-readable output, `--note "<text>"` to pass free-text the safety layer screens for red flags, and (live) `sync --rebuild|--backfill|--reconcile`.
- **API:** `pnpm --filter @stride/api dev` → `http://localhost:8720`; hit `/health`, `/analyze/demo`, `/next?demo=true`, `/pmc?demo=true`, `POST /plan {"demo":true}`.
- **Web:** `pnpm --filter @stride/web dev` → `http://localhost:5173` (defaults to demo mode; proxies `/api` to the API — start the API too for live data).
- **MCP:** `pnpm --filter @stride/mcp dev` — speaks MCP over **stdio**; exposes **8 tools** (5 read-only fact tools + 3 action tools) shared with `packages/core/src/coach/tools.ts`; call them with `{ "demo": true }`. Logs go to **stderr** (stdout is the protocol channel).
- **`stride doctor`** prints tooling, configured credentials, and exactly what runs offline vs needs creds.

## Determinism & observability (use these to debug in a loop)

- **Reproducible output:** set `STRIDE_NOW=<ISO>` (or `--now <iso>` on the CLI) to pin the reference clock, so demo `next`/`plan` output is byte-identical across runs (diffable). Tests inject `deps.nowIso`/`asOfDate` for the same reason.
- **Logs:** `STRIDE_LOG=debug` (or CLI `--verbose`) raises the structured logger (`packages/core/src/log.ts`) — surfaces e.g. swallowed LLM errors and Strava rate-limit hits. `STRIDE_LOG_FORMAT=json` for machine-parseable lines. Logs always go to **stderr**.
- **API errors** return `{ error, requestId }` with a matching `x-request-id` header.

## Project structure

```
packages/schemas  Zod domain model — single source of truth for types
packages/core     domain logic: sports-science engine, Strava client, local store, Claude coach, logger, sync
packages/config   shared tsconfig
apps/cli          commander CLI (@stride/cli)   apps/api  Hono HTTP API (@stride/api)
apps/web          Vite + React dashboard        apps/mcp  MCP stdio server (@stride/mcp)
scripts/smoke.mjs the pnpm verify harness
```

## Conventions & boundaries (non-negotiable)

1. **Compute-in-code, reason-in-LLM.** Every number (load, CTL/ATL/TSB, zones, plans) is computed deterministically in `packages/core`. The LLM only writes prose. If you need a number, add a tested function to core — never ask the model for it.
2. **Every plan feature ships with a guardrail test.** `packages/core/src/coach/guardrail.ts` enforces ramp / rest / no-back-to-back-hard / long-run caps; keep it green.
3. **Strava compliance:** read-only scopes only, owner-only data, 7-day cache expiry, "View on Strava" / "Powered by Strava" attribution in any UI. See `GOAL.md` §4.
4. **Add tests for runtime behavior**; run `pnpm check` (and `pnpm verify` for cross-surface changes) before committing.
5. **Git:** `main` is protected (PR + green CI required; admins may bypass). Branch, PR, sign off commits (`git commit -s`, DCO). Never commit secrets or `.stride/`.

## Gotchas (these will bite you)

- **cwd-relative store:** the local store defaults to `./.stride`, so commands run from different directories point at different stores. Run from the **repo root** or set an absolute `STRIDE_DATA_DIR`. (The shared shell's cwd can drift between calls — use absolute paths or `pnpm --filter`.)
- **MCP stdout is the wire:** never `console.log` in MCP code paths; dotenv is loaded with `{ quiet: true }` for the same reason. Log to stderr via the logger.
- **Bundling workspace packages:** `@stride/core`/`@stride/schemas` ship raw `.ts` (no build step). Any new bundled app must set tsup `noExternal: [/^@stride\//]` or its `dist` will try to import `.ts` at runtime.
- **Platform drift:** dev is Windows/Node 24, CI is Ubuntu/Node 22; the TypeScript 7 native compiler and esbuild are platform-specific binaries.

## Stop conditions (don't retry into bankruptcy)

- If the **same command fails 2–3 times with the same error**, stop and escalate — re-read the actual error instead of re-running. Raise signal with `--verbose` / `STRIDE_LOG=debug`, the API's `{ error, requestId }`, or `--json`.
- Treat `pnpm check` and `pnpm verify` as the objective verdict; a change isn't done until both are green.
