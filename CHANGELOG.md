# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/jaypetez/stride/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/jaypetez/stride/releases/tag/v0.1.0
