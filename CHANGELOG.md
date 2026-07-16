# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/jaypetez/stride/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/jaypetez/stride/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/jaypetez/stride/releases/tag/v0.1.0
