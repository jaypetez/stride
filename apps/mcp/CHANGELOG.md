# @stride/mcp

## 0.2.3

### Patch Changes

- chore: dependency maintenance and transitive security patches

  Patch three high-severity advisories reachable only through dev tooling, each of
  which was held in place by one of our own resolution pins. `fast-uri`
  (GHSA-7p8r-x3mc-p8w7, host confusion via backslash) was pinned by an existing
  override to `^3.1.4` while the fix shipped in 3.1.5 — the override itself was
  holding the vulnerable version. `js-yaml` (GHSA-5p4m-2wfm-xmqj / CVE-2026-59870,
  quadratic CPU in `!!omap` resolution) is reached through `@changesets/cli` on
  both the 3.x and 4.x lines, so each major is pinned separately rather than
  forced onto one.

  Runtime: `hono` 4.12.32 -> 4.12.34.

  Dev tooling: `@biomejs/biome` 2.5.6 -> 2.5.7, `lint-staged` 17.1.1 -> 17.3.0,
  `tsx` 4.23.1 -> 4.23.5, `turbo` 2.10.7 -> 2.10.8, `@playwright/test` 1.62.0 ->
  1.62.1, `@types/react` 19.2.17 -> 19.2.18, `@types/react-dom` 19.2.3 -> 19.2.4,
  `@vitejs/plugin-react` 6.0.4 -> 6.0.5, and `vite` 8.1.5 -> 8.2.0.

  CI actions: `github/codeql-action` (init/analyze/upload-sarif) v4.37.3 ->
  v4.37.6 and `pnpm/action-setup` 6.0.9 -> 6.0.10.

  No runtime behaviour changed; `pnpm audit --audit-level=high` is clean.

- Updated dependencies
  - @stride/core@0.2.3
  - @stride/schemas@0.2.3

## 0.2.2

### Patch Changes

- Update dependencies to their latest releases (via Dependabot):

  - `@anthropic-ai/sdk` 0.111.0 → 0.115.0
  - `hono` 4.12.30 → 4.12.32
  - `@hono/node-server` 2.0.10 → 2.0.12
  - `@modelcontextprotocol/sdk` 1.29.0 → 1.30.0
  - `@tanstack/react-query` 5.101.2 → 5.101.4, and `react` / `react-dom` 19.2.7 → 19.2.8
  - dev tooling: `@biomejs/biome`, `@types/node`, `@playwright/test`,
    `@testing-library/jest-dom` (6 → 7), `@vitejs/plugin-react`, `happy-dom`,
    `lint-staged`, and `turbo`
  - CI actions: `actions/checkout` v7.0.1, `github/codeql-action` v4.37.3, and
    `ossf/scorecard-action` v2.4.4
  - a pnpm `overrides` entry pins `fast-uri` to ^3.1.4, clearing GHSA-v2hh-gcrm-f6hx
    from the required `audit` gate

- Updated dependencies
  - @stride/core@0.2.2
  - @stride/schemas@0.2.2

## 0.2.1

### Patch Changes

- Updated dependencies
  - @stride/core@0.2.1
  - @stride/schemas@0.2.1

## 0.2.0

### Minor Changes

- 671fa6c: Modernize the Claude coach and enforce the safety layer.

  - Real proposal→repair→reject plan loop (Option A): when an LLM is present, `generatePlan` asks Claude for a STRUCTURAL plan via structured outputs (`messages.parse` + `zodOutputFormat`, new `LlmPlanProposal` schema — phases and per-day type/emphasis/rationale, no numbers), then deterministically materializes each session with `makeSession` and runs the hardened `validatePlan`→`repairPlan`→re-validate loop. Valid → return; repaired-to-valid → return (`repaired: true`); unrepairable/refused/empty → reject and fall back to the always-valid skeleton. The no-key path is untouched, so golden snapshots stay byte-identical.
  - Anthropic layer redesign: the `CoachLLM` seam now returns rich results (`CompleteResult`/`ParseResult`/`ClassifyResult`), with prompt caching (`cache_control` ephemeral on an expanded frozen persona), streaming for interactive paths, structured outputs for plans, adaptive thinking + per-tier `effort` (plan/opus=high, chat/sonnet=medium, classify/haiku=none), a tool runner over the shared toolset, refusal/truncation handling (discard model output → deterministic fallback), and usage/`request_id` audit logging (`coach/llm-log.ts`).
  - Shared read-only toolset (`coach/tools.ts`): a `CoachDataProvider` interface plus the five §8 fact tools, reused by both the coach tool runner and the MCP server so MCP and the coach expose byte-identical facts. MCP is now a thin adapter over the core toolset (adds `get_next_workout_inputs` and `get_plan_context`), emits the disclaimer, and accepts a `note` input.
  - Safety enforcement: a `disclaimer` is attached to every coach output (including the offline path); `analyzeWorkout` halts on a `stop` red flag (skips the LLM) like `suggestNextWorkout`; a `note` free-text param is threaded into `detectRedFlags` so `STOP_KEYWORDS` fire; a PAR-Q screening helper is exported; and an optional Haiku `classify` second pass augments WARNING-level flags on free text while keyword rules stay authoritative for `stop`.

### Patch Changes

- 19e260c: docs: architecture, ADRs, per-package READMEs, and worked examples

  Add `docs/architecture.md` (three-layer model + data flow + diagram), four ADRs
  under `docs/adr/` (raw-`.ts` workspaces, durable daily-load series, Option A plan
  generation, advisory sync lock), per-package READMEs for core/schemas/config and
  the cli/api/mcp apps, and a runnable `examples/` directory with real,
  byte-reproducible offline command output. README updated with `doctor`/`profile`,
  the `--note` flag and PAR-Q screening, a Documentation section, and an OpenSSF
  Best Practices badge placeholder. Docs-only; no runtime code changed.

- Updated dependencies [71d11c3]
- Updated dependencies [1b5a68b]
- Updated dependencies [19e260c]
- Updated dependencies [d355613]
- Updated dependencies [671fa6c]
- Updated dependencies [6507f11]
- Updated dependencies [d89eaa9]
  - @stride/core@0.2.0
  - @stride/schemas@0.2.0

## 0.1.0

### Patch Changes

- Updated dependencies
  - @stride/core@0.1.0
  - @stride/schemas@0.1.0
