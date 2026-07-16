<p align="center">
  <img src="./assets/stride-hero.svg" alt="Stride — your Strava agentic coach" width="100%">
</p>

# Stride

> Your Strava agentic coach — a local-first, open-source AI running coach.

[![CI](https://github.com/jaypetez/stride/actions/workflows/ci.yml/badge.svg)](https://github.com/jaypetez/stride/actions/workflows/ci.yml)
[![CodeQL](https://github.com/jaypetez/stride/actions/workflows/codeql.yml/badge.svg)](https://github.com/jaypetez/stride/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/jaypetez/stride/badge)](https://securityscorecards.dev/viewer/?uri=github.com/jaypetez/stride)
[![OpenSSF Best Practices](https://img.shields.io/badge/OpenSSF_Best_Practices-not_yet_registered-inactive.svg)](https://www.bestpractices.dev/)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](./LICENSE)
[![Node](https://img.shields.io/badge/node-%3E%3D22-brightgreen.svg)](.nvmrc)

<!-- TODO(maintainer): register Stride at https://www.bestpractices.dev/ and replace the
     placeholder badge above with the real project badge (GOAL.md §10 Phase 3). -->


Stride pulls your Strava workouts, computes real sports-science metrics **in
code**, and uses Claude to explain what happened, suggest your next workout, and
build a training plan — over a shared core exposed through a **CLI**, an **HTTP
API**, a **web UI**, and an **MCP server**.

The guiding split: **numbers are computed by deterministic code; the LLM reasons,
explains, plans, and motivates over those numbers — it never computes them.**

See [`GOAL.md`](GOAL.md) for the full project brief, architecture, and roadmap.

## See it in action

Everything below runs **offline on bundled demo data** — no Strava app and no
Anthropic key required. Add your own credentials to swap in live data and richer,
LLM-written prose. Every number shown is computed deterministically in
`packages/core`; the coach only writes the words that explain it.

### Web dashboard

`pnpm --filter @stride/web dev` → <http://localhost:5173> (demo mode by default)

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./assets/screenshots/web-dashboard-dark.png">
    <img alt="The Stride web dashboard in demo mode: current form (CTL / ATL / TSB and ACWR), the next recommended workout with its rationale, a CTL/ATL/TSB fitness-trend chart, the latest activity analysis, a recent-activities table, and an 8-week guardrail-checked training plan — with 'Powered by Strava' attribution in the footer." src="./assets/screenshots/web-dashboard.png" width="100%">
  </picture>
</p>

Current form, the next workout and *why*, the fitness trend, the latest analysis,
recent activities, and a guardrail-checked plan — one dashboard over the shared
core. A **Demo / My data** toggle switches between bundled data and your synced
Strava history.

### Command line

Analyze a finished run, get your next workout, and build a whole training block —
each explained in plain language, right in the terminal:

<table>
  <tr>
    <td width="50%" valign="top">
      <img alt="'stride analyze --demo' output: sports-science metrics for the demo run (training load in TSS, intensity factor, pace, grade-adjusted pace, average HR, efficiency factor, aerobic decoupling, and intensity split) followed by a coach explanation." src="./assets/screenshots/cli-analyze.png">
    </td>
    <td width="50%" valign="top">
      <img alt="'stride next --demo' output: a current-form summary (CTL / ATL / TSB, ACWR, last-7-day volume) and the next recommended workout with target duration, pace, HR zone, estimated load, and a rationale." src="./assets/screenshots/cli-next.png">
    </td>
  </tr>
</table>

<p align="center">
  <img alt="'stride plan --demo --race 10k --weeks 8' output: an 8-week 10k plan progressing base → build → peak → taper with a recovery week, weekly TSS and distance targets, session lists, and a note that the plan passes all guardrails (ramp, rest, no back-to-back-hard, long-run caps)." src="./assets/screenshots/cli-plan.png" width="88%">
</p>

## Status

All four surfaces are implemented on one shared core: the deterministic
sports-science engine, rate-limit-aware Strava client, local-first store, and
Claude coach in `packages/core`, exposed through the **CLI**, **HTTP API**, **web
UI**, and an **MCP server** (8 read-only + action tools). The entire inner loop —
analyze, next-workout, and plan generation — runs **offline with no credentials**
via demo mode; a Strava app and an Anthropic key unlock live data and richer
prose. See [`docs/architecture.md`](docs/architecture.md) for the design.

## Quickstart

Prerequisites: **Node.js 22+** and **pnpm 10+** (`corepack enable`).

```bash
pnpm install
cp .env.example .env      # add your own Strava + Anthropic credentials

# Preflight — shows tooling, configured credentials, and what runs offline:
pnpm --filter @stride/cli dev -- doctor

# Try the coach offline on bundled demo data (no credentials needed):
pnpm --filter @stride/cli dev -- analyze --demo
pnpm --filter @stride/cli dev -- next --demo
pnpm --filter @stride/cli dev -- plan --demo --race 10k --weeks 8

# Tell the coach how you feel — screened for safety red flags before any advice:
pnpm --filter @stride/cli dev -- next --demo --note "left knee a bit sore"

# Connect your own Strava account, then sync and coach:
pnpm --filter @stride/cli dev -- connect
pnpm --filter @stride/cli dev -- sync
pnpm --filter @stride/cli dev -- profile --screen   # PAR-Q readiness screening
pnpm --filter @stride/cli dev -- next
pnpm --filter @stride/cli dev -- plan --race 10k --weeks 8
```

Add `--json` to `analyze`/`next`/`plan` for machine-readable output, and
`--now <ISO>` (or `STRIDE_NOW`) to pin the clock for byte-reproducible demos.
See [`examples/`](examples/) for real captured output.

### Safety screening

Onboarding includes a **PAR-Q-style readiness screening** (`stride profile
--screen`, or `POST /profile/screening` on the API). Answers are persisted to
your profile and, together with the `--note` free-text red-flag detection,
constrain every later recommendation — a STOP keyword (e.g. "chest pain")
halts coaching and refers you to a professional.

## Workspace

```
apps/
  cli/   commander + @clack/prompts CLI          (@stride/cli)
  api/   Hono HTTP API                            (@stride/api)
  web/   Vite + React dashboard                   (@stride/web)
  mcp/   Model Context Protocol server            (@stride/mcp)
packages/
  core/     sports-science engine + Strava + coach (@stride/core)
  schemas/  Zod schemas — single source of truth   (@stride/schemas)
  config/   shared TypeScript config               (@stride/config)
```

## Scripts

| Command | What it does |
|---|---|
| `pnpm check` | The gate: lint → typecheck → test → build (run before committing) |
| `pnpm verify` | Runtime smoke: boots the API, drives the MCP stdio protocol, and runs the CLI — all offline |
| `pnpm build` | Build all packages and apps (Turborepo) |
| `pnpm typecheck` | Type-check the workspace |
| `pnpm test` | Run unit/integration tests (Vitest) |
| `pnpm coverage` | Test with V8 coverage |
| `pnpm lint` | Biome lint + format check |
| `pnpm format` | Auto-fix formatting |

## Documentation

- [`docs/architecture.md`](docs/architecture.md) — the three-layer model
  (deterministic compute → Claude reasoning → guardrail/safety), the Strava →
  durable daily-load → PMC/ACWR → coach data flow, and the four surfaces over one
  core.
- [`docs/adr/`](docs/adr/) — Architecture Decision Records (raw-`.ts` workspaces,
  the durable daily-load series, Option A plan generation, the advisory sync
  lock).
- [`examples/`](examples/) — real, byte-reproducible output from the offline demo
  commands (`analyze`, `next`, `plan`, `doctor`).
- Per-package READMEs live beside each package under `packages/*` and `apps/*`.
- [`GOAL.md`](GOAL.md) — the full project brief; [`AGENTS.md`](AGENTS.md) — the
  machine-readable command manifest for AI agents.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
Security issues: [SECURITY.md](SECURITY.md). **AI coding agents:** start with
[AGENTS.md](AGENTS.md) — the machine-readable command manifest and conventions.

## Disclaimer

Stride is for **informational and educational purposes only** and is **not a
substitute for professional medical advice**. Consult a qualified healthcare
provider before beginning any fitness program.

## Attribution

Stride connects to the Strava API but is **not affiliated with, endorsed by, or
sponsored by Strava, Inc.** Powered by Strava. Data obtained via the Strava API
is subject to the [Strava API Agreement](https://www.strava.com/legal/api).

## License

[Apache-2.0](./LICENSE) © 2026 Jayson Petersen and Stride contributors.
