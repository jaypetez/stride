# @stride/config

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
