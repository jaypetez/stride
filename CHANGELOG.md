# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.6] - 2026-09-08

A security-patch release. It moves the `qs` transitive dependency to 6.16.0,
clearing two moderate advisories from the runtime dependency tree. No runtime
behaviour changed. Per-package details are in each package's `CHANGELOG.md`.

### Security

- **`qs` 6.15.3 → 6.16.0**, clearing two moderate advisories:
  [GHSA-4mjr-xmp4-gh2g](https://github.com/advisories/GHSA-4mjr-xmp4-gh2g)
  (denial of service via attacker-controlled `isBuffer`) and
  [GHSA-x5fp-wj9c-mxmx](https://github.com/advisories/GHSA-x5fp-wj9c-mxmx)
  (array-limit bypass via bracket-key comma parsing). Both alerts were
  **runtime** scope — `qs` is reached through
  `@stride/mcp > @modelcontextprotocol/sdk > express > body-parser`.
  `body-parser@2.3.0` declares `qs: ^6.15.2` and `express@5.2.1` declares
  `^6.14.0`, so 6.16.0 satisfies both parents and needs no `pnpm.overrides`
  pin — only the lockfile was holding the stale resolution.
- **Deferred**: `esbuild` 0.27.7
  ([GHSA-g7r4-m6w7-qqqr](https://github.com/advisories/GHSA-g7r4-m6w7-qqqr),
  low, **dev** scope) is knowingly left in place. `tsup@8.5.1` — the current
  latest — pins `esbuild: ^0.27.0`, so 0.28.1+ cannot be reached without
  forcing an override against its declared range, and the vulnerable path is
  esbuild's own development server, which tsup never starts. `vite@8.2.2`
  already resolves esbuild 0.28.2 independently. To be revisited when tsup
  widens its range.

## [0.2.5] - 2026-09-03

A dependency-maintenance release rolling up the ten open Dependabot updates and
patching three high-severity `fast-uri` advisories. No runtime behaviour
changed. Per-package details are in each package's `CHANGELOG.md`.

### Changed

- **Runtime dependencies**: `hono` 4.12.34 → 4.13.5 (api, web),
  `@hono/node-server` 2.1.0 → 2.1.1 (api), `@anthropic-ai/sdk` 0.116.0 →
  0.122.0 (core), `@tanstack/react-query` 5.101.4 → 5.102.8 (web), and `zod`
  4.4.3 → 4.5.4 (catalog).
- **Dev tooling**: `@biomejs/biome` 2.5.7 → 2.5.11, `@changesets/cli` 2.31.1
  → 3.0.1, `@commitlint/cli` 21.2.1 → 21.2.2,
  `@commitlint/config-conventional` 21.2.0 → 21.2.2, `vitest` and
  `@vitest/coverage-v8` 4.1.10 → 4.1.11, `lint-staged` 17.3.0 → 17.4.1,
  `tsx` 4.23.12 → 4.23.13, `turbo` 2.10.9 → 2.10.12,
  `@testing-library/react` 16.3.2 → 16.3.3, `@types/react-dom` 19.2.4 →
  19.2.5, `@vitejs/plugin-react` 6.0.5 → 6.1.1, `happy-dom` 20.11.2 →
  20.12.0, and `vite` 8.2.1 → 8.2.2.
- **CI actions**: `github/codeql-action` (init/analyze/upload-sarif) v4.37.6 →
  v4.37.9, and `changesets/action` v1.9.0 → v2.1.1.
- **Release pipeline**: migrated to Changesets v3. The action's v2 renamed every
  input the release job passes (`version` → `version-script`, `publish` →
  `publish-script`, `commit` → `commit-message`, `title` → `pr-title`) and
  dropped the `GITHUB_TOKEN` env var in favour of a `github-token` input, and
  the CLI renamed `changeset tag` to `changeset git-tag`.

### Fixed

- **Changesets skipped every package under CLI v3**: `@changesets/config` v4
  flips the default of `privatePackages.version` from `true` to `false`. Every
  workspace package here is private, so `changeset version` matched nothing —
  no version bump, no changelog entries, and the changeset left unconsumed, with
  the release job still reporting success. `.changeset/config.json` now sets
  `privatePackages` explicitly.

### Security

- **`fast-uri`**: the `overrides` pin moves ^3.1.5 → ^3.1.6 (resolving to
  3.1.7), patching GHSA-f65p-4m7j-42xc (SSRF via repeated hostname
  percent-decoding), GHSA-fph4-wmhf-6fwf, and GHSA-jqff-g426-hqxp (host
  confusion via percent-encoded scheme normalization). As in 0.2.3, the pin
  added to close the previous advisory was itself holding a now-vulnerable
  version in place, failing the required `audit` gate on every dependency PR.
  Reached only through dev tooling (`@commitlint/cli` → `@commitlint/load` →
  `@commitlint/config-validator` → `ajv`).

## [0.2.4] - 2026-08-13

A dependency-maintenance release rolling up the three open Dependabot updates.
No runtime behaviour changed. Per-package details are in each package's
`CHANGELOG.md`.

### Changed

- **Runtime dependencies**: `@anthropic-ai/sdk` 0.115.0 → 0.116.0 (core) and
  `@hono/node-server` 2.0.12 → 2.1.0 (api).
- **Dev tooling**: `@types/node` 26.1.2 → 26.2.0, `tsx` 4.23.5 → 4.23.12,
  `turbo` 2.10.8 → 2.10.9, `@testing-library/jest-dom` 7.0.0 → 7.0.1,
  `happy-dom` 20.11.1 → 20.11.2, and `vite` 8.2.0 → 8.2.1.

## [0.2.3] - 2026-08-07

A dependency-maintenance release. Every open Dependabot update has landed, and
three high-severity advisories reachable through dev tooling are patched. Per-package
details are in each package's `CHANGELOG.md`.

### Changed

- **Runtime dependencies**: `hono` 4.12.32 → 4.12.34.
- **Dev tooling**: `@biomejs/biome` 2.5.6 → 2.5.7, `lint-staged` 17.1.1 →
  17.3.0, `tsx` 4.23.1 → 4.23.5, `turbo` 2.10.7 → 2.10.8, `@playwright/test`
  1.62.0 → 1.62.1, `@types/react` 19.2.17 → 19.2.18, `@types/react-dom` 19.2.3 →
  19.2.4, `@vitejs/plugin-react` 6.0.4 → 6.0.5, and `vite` 8.1.5 → 8.2.0.
- **CI actions**: `github/codeql-action` (init/analyze/upload-sarif) v4.37.3 →
  v4.37.6, and `pnpm/action-setup` 6.0.9 → 6.0.10.

### Security

- **`fast-uri`**: the `overrides` pin moves ^3.1.4 → ^3.1.5, patching
  GHSA-7p8r-x3mc-p8w7 (host confusion via backslash). The pin added in 0.2.2 to
  close GHSA-v2hh-gcrm-f6hx was itself holding the vulnerable version in place,
  which had been failing the required `audit` gate on every dependency PR.
- **`js-yaml`**: new `overrides` entries pin the 3.x line to ^3.15.1 and the 4.x
  line to ^4.3.1, patching GHSA-5p4m-2wfm-xmqj (CVE-2026-59870, quadratic CPU
  consumption in `!!omap` resolution). Both are reached through
  `@changesets/cli`; because the two consumers sit on different majors, each
  line is pinned separately rather than forced onto one.

## [0.2.2] - 2026-07-31

A docs-and-dependencies release: the four surfaces are now documented with
end-to-end screenshots and a runnable guided tour, and every open Dependabot
update has landed. Per-package details are in each package's `CHANGELOG.md`.

### Added

- **Docs**: end-to-end screenshots of all four surfaces captured offline on demo
  data — a theme-aware web dashboard shot plus CLI cards for `doctor`, `analyze`,
  `next`, and `plan` — in a new README "See it in action" section, and "A guided
  tour" in `docs/README.md` walking through the CLI, web UI, HTTP API (with
  `curl` and JSON), and MCP server.

### Changed

- **Runtime dependencies**: `@anthropic-ai/sdk` 0.111.0 → 0.115.0, `hono`
  4.12.30 → 4.12.32, `@hono/node-server` 2.0.10 → 2.0.12,
  `@modelcontextprotocol/sdk` 1.29.0 → 1.30.0, `@tanstack/react-query` 5.101.2 →
  5.101.4, and `react` / `react-dom` 19.2.7 → 19.2.8.
- **Dev tooling**: `@biomejs/biome` 2.5.4 → 2.5.6, `@types/node` 26.1.1 →
  26.1.2, `@playwright/test` 1.61.1 → 1.62.0, `@testing-library/jest-dom` 6.9.1
  → 7.0.0, `@vitejs/plugin-react` 6.0.3 → 6.0.4, `happy-dom` 20.10.6 → 20.11.1,
  `lint-staged` 17.0.8 → 17.1.1, and `turbo` 2.10.5 → 2.10.7.
- **CI actions**: `actions/checkout` v7.0.0 → v7.0.1, `github/codeql-action`
  (init/analyze/upload-sarif) v4.37.1 → v4.37.3, and `ossf/scorecard-action`
  2.4.3 → 2.4.4.

### Security

- **`fast-uri`**: a pnpm `overrides` entry pins `fast-uri` to ^3.1.4, patching
  GHSA-v2hh-gcrm-f6hx, which had been failing the required `audit` gate on every
  dependency PR.

## [0.2.1] - 2026-07-16

A dependency-maintenance release: all open Dependabot updates, plus a CI fix so
the audit gate and Conventional Commits check stop wedging dependency PRs.
Per-package details are in each package's `CHANGELOG.md`.

### Changed

- **Runtime dependencies**: `@anthropic-ai/sdk` 0.110.0 → 0.111.0, `hono`
  4.12.28 → 4.12.30, `@hono/node-server` 2.0.8 → 2.0.10, and
  `@hono/zod-validator` 0.8.0 → 0.9.0.
- **Dev tooling**: the `dev-dependencies` group (6 updates).
- **CI actions**: `pnpm/action-setup`, `actions/setup-node`, and
  `github/codeql-action` (init/analyze/upload-sarif) bumped to current majors.

### Fixed

- **CI**: the `audit` gate soft-passes only on npm's retired-audit-endpoint
  infra error (still fails hard on genuine high+ advisories), and commitlint no
  longer rejects Dependabot's capitalized `Bump …` subject.

## [0.2.0] - 2026-07-14

A correctness, safety, and infrastructure overhaul. Per-package details are in
each package's `CHANGELOG.md`.

### Added

- **Durable training-load persistence**: a `daily-loads.json` series that is the
  PMC/ACWR source of truth and survives the 7-day raw Strava cache expiry, with
  incremental/backfill/rebuild sync modes, a watermark + resumable cursor, and
  deletion reconciliation.
- **Rate-limit-aware Strava client**: proactive window throttling, `Retry-After`
  429 backoff, and graceful degradation to partial results.
- **Modernized Claude coach**: structured-output plan proposals materialized into
  code-computed numbers, prompt caching, streaming, adaptive thinking/effort per
  model tier, a shared read-only tool set (reused by the MCP server, now 8 tools),
  a tool runner, and usage/`request_id` auditability.
- **Enforced safety layer**: a disclaimer on every coach output, red-flag halting
  (analyze/next/plan), a `--note` free-text channel, and PAR-Q screening.
- **OSS/CI**: OS×Node CI matrix, CodeQL, OpenSSF Scorecard, dependency review,
  DCO + commitlint, Changesets release automation, governance files, an
  architecture doc, ADRs, per-package READMEs, and worked `examples/`.

### Changed

- CTL-based plan ramp cap with repair-or-reject guardrail semantics.
- Atomic, lock-guarded local store writes; `~`/`$HOME` expansion for the data dir.
- Web dashboard consumes the API via the typed Hono `hc` client.

### Fixed

- ACWR cold-start false `very_high` flags; PMC projected to the reference day;
  treadmill pace excluded from rTSS/VDOT; plus 10 bugs surfaced by an adversarial
  bug-hunt sweep (durable-series data loss on truncated rebuild/incremental sync,
  `Infinity` pace from `cross_training`, and others).

## [0.1.0] - 2026-07-14

### Added

- Initial monorepo scaffold (pnpm + Turborepo) with `apps/{cli,api,web,mcp}` and
  `packages/{core,schemas,config}`.
- `@stride/schemas`: Zod domain model (activities, streams, daily load, athlete
  profile, PMC, zones, workout suggestions, training plans).
- `@stride/core`: deterministic sports-science engine (grade-adjusted pace, NGP,
  rTSS with HR/duration fallbacks, CTL/ATL/TSB, EWMA-ACWR + ramp guardrails, HR &
  pace zones, VDOT, time-in-zone / 80-20, efficiency factor, aerobic decoupling).
- `@stride/core`: rate-limit-aware Strava client, local OAuth flow, and a
  local-first store with 7-day Strava cache expiry.
- `@stride/core`: Claude-backed coach (analyze / suggest-next / generate-plan)
  with a deterministic plan guardrail validator and a red-flag safety layer.
- CLI (`stride`): `analyze`, `connect`, `sync`, `next`, `plan` commands, with an
  offline `--demo` mode.
- HTTP API (Hono) exposing the core over `/analyze`, `/next`, `/plan`, `/pmc`,
  `/sync`, with a typed client.
- Web dashboard (Vite + React) with a PMC chart, workout analysis, next workout,
  and plan view; includes Strava attribution.
- MCP server exposing read-only coach tools over stdio.

### Changed

- License changed from MIT to Apache-2.0 (adds an explicit patent grant).

[Unreleased]: https://github.com/jaypetez/stride/compare/v0.2.6...HEAD
[0.2.6]: https://github.com/jaypetez/stride/compare/v0.2.5...v0.2.6
[0.2.5]: https://github.com/jaypetez/stride/compare/v0.2.4...v0.2.5
[0.2.4]: https://github.com/jaypetez/stride/compare/v0.2.3...v0.2.4
[0.2.3]: https://github.com/jaypetez/stride/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/jaypetez/stride/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/jaypetez/stride/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/jaypetez/stride/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jaypetez/stride/releases/tag/v0.1.0
