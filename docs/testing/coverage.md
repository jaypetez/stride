# Coverage thresholds (TypeScript)

Every TypeScript test suite enforces Vitest v8 **coverage thresholds**. A drop
below any threshold fails `pnpm coverage` locally and the `coverage` job in CI
(which is part of the required `gate` check). Added for AUD-017; the cloud app
(`apps/cloud`, QA-006) follows the same policy when it lands.

## Commands

| Command | Effect |
|---|---|
| `pnpm coverage` | Runs every package suite with `--coverage` (Turbo), then the root `scripts/**/*.test.ts` suite. Exits non-zero on any threshold miss. |
| `pnpm coverage:summary` | Prints a Markdown table of the last run's coverage vs. thresholds (what CI posts to the job summary). |
| `pnpm coverage:ratchet` | Re-runs coverage with `COVERAGE_RATCHET=1`, which rewrites the thresholds in each `vitest.config.*` up to the new, floored coverage. Commit the result. |
| `pnpm --filter @stride/<pkg> exec vitest run --coverage` | One package, while iterating. |

Reports land in each package's `coverage/` (text, `json-summary`, `lcov`); the
root scripts suite writes to `coverage/scripts/`. All are git-ignored.

## Where thresholds live

Each suite owns its thresholds in its own Vitest config, as literal numbers
(Vitest's `autoUpdate` rewrites them in place):

| Suite | Config | Lines | Statements | Functions | Branches |
|---|---|---:|---:|---:|---:|
| `@stride/core` | `packages/core/vitest.config.ts` | 92 | 89 | 88 | 79 |
| `@stride/schemas` | `packages/schemas/vitest.config.ts` | 100 | 100 | 100 | 100 |
| `@stride/api` | `apps/api/vitest.config.ts` | 89 | 90 | 92 | 94 |
| `@stride/cli` | `apps/cli/vitest.config.ts` | 40 | 37 | 43 | 30 |
| `@stride/mcp` | `apps/mcp/vitest.config.ts` | 80 | 81 | 80 | 85 |
| `@stride/web` | `apps/web/vitest.config.ts` | 64 | 62 | 52 | 64 |
| `@stride/factory` | `tools/factory/vitest.config.ts` | 67 | 63 | 56 | 51 |
| repo scripts | `vitest.config.mts` (root) | 94 | 95 | 98 | 92 |

The configs are the source of truth; this table is a snapshot from when the
policy was introduced and is refreshed by whoever ratchets.

Coverage is measured over **all** source files (`coverage.include: ['src/**']`),
not only the files a test happens to import, so an untested module counts
against the package.

## Policy

1. **Baseline = measured coverage, rounded down** to a whole percent. The
   sports-science engine (`@stride/core`) carries the highest bar of the
   runtime packages and must stay at **85% lines or more**.
2. **Thresholds only go up (the ratchet).** When a change raises coverage by a
   point or more, run `pnpm coverage:ratchet` and commit the updated configs in
   the same PR. The ratchet floors every value, so it never sets a threshold
   above what was measured.
3. **Never lower a threshold to get a PR green.** Add tests instead. The only
   exception is deleting or moving tested code; then lower the affected
   threshold in the same PR and say why in the PR body.
4. **New packages** start with a `vitest.config.ts` carrying the same
   `coverage` block (provider `v8`, `json-summary` reporter, floored
   thresholds) so the CI summary and the ratchet pick them up automatically.
5. Thresholds are global per package. Prefer raising the package number over
   per-file thresholds; add a per-file glob only for code that must stay near
   100% (for example a safety rule).

## CI

The `coverage` job in `.github/workflows/ci.yml` runs `pnpm coverage` (fails on
a miss), then always runs `node scripts/coverage-summary.mjs >>
"$GITHUB_STEP_SUMMARY"` so the run page shows a per-package table with ✅/❌
against each threshold, and uploads the `coverage/` reports as an artifact.

`COVERAGE_RATCHET` is declared in the Turbo `test` task's `env`, so a ratchet
run never reuses a cached, non-ratcheting result.
