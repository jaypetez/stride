# @stride/config

## 0.2.9

### Patch Changes

- d39d097: Dependency maintenance batch. No runtime behaviour changed outside routine
  patch/minor bumps to third-party libraries.
  
  Runtime deps: `zod` 4.5.4 -> 4.6.5, `@anthropic-ai/sdk` 0.124.0 -> 0.127.0,
  `@clack/prompts` 1.8.0 -> 1.8.1, `react`/`react-dom`/`@types/react`/
  `@types/react-dom` 19.2.x -> 19.3.0.
  
  The `react`/`react-dom` pair and the `@anthropic-ai/sdk` bump each needed a
  Dependabot rebase to land: React refuses to boot when `react` and `react-dom`
  resolve to different versions ("Incompatible React versions"), so the two
  PRs had to merge back-to-back rather than independently; the SDK PR's first
  CI run predated the React merge and was retested after rebasing onto the
  updated lockfile.
  
  CI: `github/codeql-action` (`init`/`analyze`/`upload-sarif`) 4.37.9 -> 4.38.0.
  `init` and `analyze` pin the same SHA in `codeql.yml`, so Dependabot's split
  PRs each show a version mismatch and fail their own `Analyze` check in
  isolation — same shape as the React pairing above, resolved by merging both.
  
  Dev tooling: `@biomejs/biome` 2.5.12 -> 2.5.14, `@changesets/cli` 3.0.2 ->
  3.0.3, `lint-staged` 17.5.0 -> 17.5.1, `turbo` 2.10.12 -> 2.11.2, `happy-dom`
  20.14.0 -> 20.14.5, `vite` 8.2.2 -> 8.3.0.
  
  `vitest`/`@vitest/coverage-v8` are deliberately held at 5.0.0 (Dependabot
  proposed 5.0.1): bumping them makes `@testing-library/jest-dom@7.0.1`'s
  vitest module augmentation resolve against the 5.0.1 peer variant, and every
  jest-dom matcher (`toBeInTheDocument`, etc.) disappears from the `Assertion`
  type, breaking `apps/web`'s typecheck. jest-dom's own peer range (`>= 0.32`)
  is too loose to catch this at install time. Revisit once jest-dom ships a
  vitest 5.0.1-compatible release.

## 0.2.8

### Patch Changes

- 8f37212: Security and CI maintenance. No runtime behaviour changed — every change here is
  confined to the dev toolchain and the workflow pins.
  
  Security: the `js-yaml@4` override pinned `^4.3.1` and the lockfile resolved to
  exactly 4.3.1, inside the vulnerable range of GHSA-2883-xcg3-v3hh (`>=4.0.0
  <4.3.2`). `js-yaml` reaches the tree transitively via `@commitlint/cli >
  @commitlint/load > cosmiconfig`, and Dependabot's security-update job runs with
  `update-subdependencies: false`, so it could not fix this itself — its run
  errored out. Both override lines now pin the patched versions (`js-yaml@4` →
  `^4.3.2`, `js-yaml@3` → `^3.15.2`). The v3 pin is currently unused, since the
  `@changesets/cli` 3.x bump dropped the `read-yaml-file` path that pulled it in,
  but is kept as a defensive pin.
  
  This advisory was failing the `audit` job, which fails the aggregate `build`
  gate — the single required check on `main` — and so had the whole Dependabot
  queue blocked.
  
  CI: `pnpm/action-setup` 6.0.10 → 6.1.0 and `changesets/action` 2.1.1 → 2.1.2
  (patch-only; no input renames, so the lockstep with `@changesets/cli` v3 holds).

## 0.2.7

### Patch Changes

- 61ab7c6: Batch dependency maintenance. Runtime: `hono` 4.13.5 → 4.13.7 (api, web),
  `@anthropic-ai/sdk` 0.122.0 → 0.124.0 (core), `@hono/zod-validator` 0.9.0 →
  0.9.1 (api), and `@clack/prompts` 1.7.0 → 1.8.0 (cli). Dev tooling: `vitest`
  and `@vitest/coverage-v8` 4.1.11 → 5.0.0, `@types/node` 26.2.0 → 26.5.0,
  `@playwright/test` 1.62.1 → 1.63.0, `@changesets/cli` 3.0.1 → 3.0.2, and
  `lint-staged` 17.4.1 → 17.5.0.
  
  `@clack/prompts` 1.8.0 pulls `@clack/core` 1.5.0, which retypes `isCancel` from
  `(value) => value is symbol` to `(value) => value is typeof CANCEL_SYMBOL`.
  TypeScript therefore no longer narrows `boolean | symbol` to `boolean` after the
  guard, so the PAR-Q screening loop in `stride profile` needed an explicit
  `boolean` assertion at one call site. Type-level only; no runtime behaviour
  changed.

## 0.2.6

### Patch Changes

- ecfc597: Patch the `qs` transitive dependency to 6.16.0, clearing two moderate advisories
  (GHSA-4mjr-xmp4-gh2g denial of service via attacker-controlled `isBuffer`, and
  GHSA-x5fp-wj9c-mxmx array-limit bypass via bracket-key comma parsing). `qs` is
  reached through `express`/`body-parser` under `@modelcontextprotocol/sdk`, so it
  was in the runtime tree.

## 0.2.5

### Patch Changes

- chore: dependency maintenance
  
  Roll up the ten open Dependabot updates and patch three high-severity
  `fast-uri` advisories. No runtime behaviour changed.
  
  Runtime: `hono` 4.12.34 -> 4.13.5 (api, web), `@hono/node-server` 2.1.0 ->
  2.1.1 (api), `@anthropic-ai/sdk` 0.116.0 -> 0.122.0 (core),
  `@tanstack/react-query` 5.101.4 -> 5.102.8 (web), and `zod` 4.4.3 -> 4.5.4
  (catalog).
  
  Dev tooling: `@biomejs/biome` 2.5.7 -> 2.5.11, `@changesets/cli` 2.31.1 ->
  3.0.1, `@commitlint/cli` 21.2.1 -> 21.2.2, `@commitlint/config-conventional`
  21.2.0 -> 21.2.2, `vitest` and `@vitest/coverage-v8` 4.1.10 -> 4.1.11,
  `lint-staged` 17.3.0 -> 17.4.1, `tsx` 4.23.12 -> 4.23.13, `turbo` 2.10.9 ->
  2.10.12, `@testing-library/react` 16.3.2 -> 16.3.3, `@types/react-dom` 19.2.4
  -> 19.2.5, `@vitejs/plugin-react` 6.0.5 -> 6.1.1, `happy-dom` 20.11.2 ->
  20.12.0, and `vite` 8.2.1 -> 8.2.2.
  
  CI actions: `github/codeql-action` (init/analyze/upload-sarif) v4.37.6 ->
  v4.37.9, and `changesets/action` v1.9.0 -> v2.1.1, whose v2 renamed every
  input the release job passes and requires Changesets CLI v3.
  
  Security: the `fast-uri` override moves to ^3.1.6, clearing
  GHSA-f65p-4m7j-42xc, GHSA-fph4-wmhf-6fwf and GHSA-jqff-g426-hqxp. They reach
  the tree only through dev tooling (`@commitlint/cli` -> `@commitlint/load` ->
  `@commitlint/config-validator` -> `ajv`) but were failing the required audit
  gate on every open pull request.

## 0.2.4

### Patch Changes

- chore: dependency maintenance

  Roll up the three open Dependabot updates. No runtime behaviour changed.

  Runtime: `@anthropic-ai/sdk` 0.115.0 -> 0.116.0 (core) and `@hono/node-server`
  2.0.12 -> 2.1.0 (api).

  Dev tooling: `@types/node` 26.1.2 -> 26.2.0, `tsx` 4.23.5 -> 4.23.12, `turbo`
  2.10.8 -> 2.10.9, `@testing-library/jest-dom` 7.0.0 -> 7.0.1, `happy-dom`
  20.11.1 -> 20.11.2, and `vite` 8.2.0 -> 8.2.1.

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

## 0.2.2

## 0.2.1

## 0.2.0

### Patch Changes

- 19e260c: docs: architecture, ADRs, per-package READMEs, and worked examples

  Add `docs/architecture.md` (three-layer model + data flow + diagram), four ADRs
  under `docs/adr/` (raw-`.ts` workspaces, durable daily-load series, Option A plan
  generation, advisory sync lock), per-package READMEs for core/schemas/config and
  the cli/api/mcp apps, and a runnable `examples/` directory with real,
  byte-reproducible offline command output. README updated with `doctor`/`profile`,
  the `--note` flag and PAR-Q screening, a Documentation section, and an OpenSSF
  Best Practices badge placeholder. Docs-only; no runtime code changed.

## 0.1.0
