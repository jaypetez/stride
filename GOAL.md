# Stride — Project Goal Prompt

> **What this document is.** A single, self-contained brief that captures the full
> intent of the Stride project. It is written to be handed to an AI coding agent
> (or a human contributor) as the north-star prompt for building the project. Read
> it top to bottom before writing code. When something here conflicts with an
> assumption you were about to make, this document wins.
>
> **Status:** greenfield. The repo currently contains only `README.md` and a
> `LICENSE`. Nothing below has been built yet.

---

## 1. One-liner

**Stride is a local-first, open-source, agentic AI running coach.** It pulls your
Strava workouts, computes real sports-science metrics *in code*, and uses Claude to
explain what happened, suggest your next workout, and build a training plan — over a
shared core exposed through a **CLI**, an **HTTP API**, a **web UI**, and an **MCP
server**.

The guiding split: **numbers are computed by deterministic code; the LLM reasons,
explains, plans, and motivates over those numbers — it never computes them.**

---

## 2. Vision & non-goals

### The full surface (the destination)
One TypeScript monorepo with one shared domain core (`packages/core`) consumed by four surfaces:

- **CLI** — `stride analyze | connect | sync | next | plan` for terminal-native coaching.
- **HTTP API** — the same core over HTTP (Hono) for the web UI and third parties.
- **Web UI** — a dashboard (fitness/fatigue/form chart, workout analysis, next workout, plan).
- **MCP server** — expose the coach's tools to any MCP client (“bring your Stride coach into Claude”).

### MVP non-goals (explicitly out of scope for v0 — do not build these yet)
- **No multi-user hosting.** Stride is self-hosted and single-user (see §4, §5).
- **No cross-user features.** No leaderboards, no comparisons, no cohort analytics, no social feed — Strava's terms forbid it and it isn't the product.
- **No write-back to Strava.** Read-only. Do not request `activity:write`.
- **No real-time / in-run coaching.** Stride reasons over completed activities only.
- **Not medical advice / not a medical device.** General-fitness, informational and educational only (see §8 safety).
- **No model training on user data.** Never fine-tune, embed, or train on Strava data (see §4).

---

## 3. Guiding principles

1. **Compute-in-code, reason-in-LLM.** Every metric (load, fitness, fatigue, form, zones, projections) is computed by deterministic code and passed to Claude as *facts*. The LLM is forbidden from computing or predicting numbers. This is the single most important architectural rule — it eliminates hallucinated metrics.
2. **Local-first, bring-your-own-keys.** Each user runs Stride themselves with their own Strava app credentials and their own Anthropic API key. No central server, no shared secrets.
3. **Deterministic guardrails over model trust.** Any plan the LLM proposes is validated and, if needed, repaired or rejected by code (ramp caps, rest minimums, long-run caps). The LLM's plan is a *proposal*; code is the *enforcer*.
4. **Small vertical slices that each deliver value.** Ship a useful CLI coach before any web/API work. Each milestone (§9) stands on its own.
5. **Evidence-based, with rationale attached.** Coaching recommendations always carry a short physiological “why” (the “Explainer” behavior that measurably improves adherence).
6. **Honest about limits.** Metrics are trends, not truth (only as good as the FTP/LTHR anchors). Compliance and medical statements here are research, not legal/medical advice.

---

## 4. Strava compliance constraints — **READ THIS FIRST**

> These constraints shape the whole design. They are summarized from Strava's
> developer docs and API Agreement as of mid-2026. **This is research, not legal
> advice — re-verify at <https://www.strava.com/legal/api> and
> <https://www.strava.com/legal/api_policy> before scaling beyond personal use.**

| Constraint | What it means for Stride |
|---|---|
| **AI/ML prohibition** (Nov 2024) | Strava's terms prohibit using API data “in artificial intelligence models or similar applications.” **Firewall Strava data from all training, fine-tuning, and embeddings/RAG — non-negotiable, zero ambiguity.** Stride must never *learn* from Strava data. |
| **LLM inference gray zone** | Whether feeding a user's *own* data to an LLM for *inference* (exactly what a coach does) is allowed is **unresolved** — Strava publicly says user-facing “coaching platforms focused on providing feedback” are fine, but there is no written carve-out. **MVP decision: proceed under personal/local single-user use, cite Strava's public coaching-is-allowed statement, and treat this as a documented risk.** TODO before any hosted/multi-user version: email developers@strava.com for written confirmation. |
| **Owner-only visibility** (Nov 2024) | A user's Strava data may only be shown to *that* user. No displaying anyone else's data — even public activities. Reinforces the single-user, no-cross-user-features design. |
| **7-day cache limit** | Strava data may not be cached longer than 7 days; if a resource disappears from Strava, remove it immediately. (For a durable coaching history that outlives 7 days, the compliance-safe source is user FIT/GPX upload — see §9 “later”.) |
| **Deletion obligations** | On deauthorization/account deletion, permanently delete all Strava-derived data within 30 days; reflect user deletions within 48h; be able to certify deletion. |
| **No aggregation/analytics** | No processing Strava data (even anonymized/aggregated) for analytics, benchmarking, or “product improvement.” |
| **Rate limits** | Default **200 req / 15 min** and **2,000 / day** overall; read sublimit **100 / 15 min** and **1,000 / day**. Windows reset at :00/:15/:30/:45 and midnight UTC. Read `X-RateLimit-*` / `X-ReadRateLimit-*` headers; back off on HTTP 429. Prefer **webhooks over polling**. |
| **Attribution & look/feel** | Link back with the exact text **“View on Strava”** (Strava orange `#FC5200`); show a compliant **“Powered by Strava”** badge (never your app icon, never more prominent than your own brand). Keep a look/feel distinct from Strava. Add **Garmin attribution** if displaying Garmin-derived data. |
| **Access tier / economics (2026)** | New apps start in **Single-Player Mode** (capacity 1 — just the developer). Self-upgrade to 10 athletes; beyond 10 needs app review. Standard-tier access requires a **per-developer** Strava subscription (~$11.99/mo, ~June 2026). **This is exactly why Stride is local-first, bring-your-own-credentials.** |
| **Base URL** | `https://www.strava.com/api/v3` today; a migration to `https://api-v3.strava.com` is announced for Jan 4, 2027 — **parameterize the host.** |

---

## 5. Architecture — three layers

```
┌──────────────────────────────────────────────────────────────────┐
│  Surfaces:   CLI    │    HTTP API    │    Web UI    │   MCP server  │
├──────────────────────────────────────────────────────────────────┤
│  (3) Guardrail / presentation layer                                │
│      - validates & repairs LLM-proposed plans (numeric bounds)     │
│      - safety: red-flag detection, disclaimers, scope boundaries   │
├──────────────────────────────────────────────────────────────────┤
│  (2) Claude reasoning layer                                        │
│      - receives PRE-COMPUTED metrics as structured facts           │
│      - explains / plans / suggests / motivates                     │
│      - NEVER computes or predicts numbers                          │
├──────────────────────────────────────────────────────────────────┤
│  (1) Deterministic compute layer  (packages/core)                 │
│      - Strava client (OAuth, sync, rate-limit handling)           │
│      - sports-science engine (load → PMC → ACWR → zones)          │
│      - computes EVERY number                                       │
└──────────────────────────────────────────────────────────────────┘
                         ▲
                         │ read-only, already-computed tools
              get_training_load() · get_recent_activities()
              get_pace_zones() · get_next_workout_inputs() ...
```

The LLM calls **read-only tools that return already-computed values from the local
store** — it does **not** call the Strava API live per turn, and it does **not** do math.

---

## 6. Tech stack (committed)

**Single-language TypeScript/Node monorepo.** Chosen because the web UI is unavoidably
JS/TS, so this is the only option where the API, web, CLI, *and* MCP server all import
**one real shared domain-logic package** (not just shared types). End-to-end type safety,
official Anthropic + MCP TS SDKs, largest OSS contributor pool.

| Concern | Choice |
|---|---|
| Monorepo | **pnpm workspaces + Turborepo**; TS project references (`composite: true`); `pnpm` catalogs; **Changesets** for versioning/release |
| Layout | `apps/{cli,api,web,mcp}` + `packages/{core,schemas,config}` |
| Shared core | `packages/core` (sports-science compute + Strava client + Claude client); `packages/schemas` (Zod — the single source of truth reused by API validation, CLI, MCP tools, and LLM tool schemas) |
| API | **Hono** (multi-runtime; `zValidator` against shared Zod; typed `hc` RPC client, zero codegen) |
| Web | **Vite + React** SPA + **TanStack Query + TanStack Router**; consume the API via Hono `hc` for automatic shared types |
| CLI | **commander** + **@clack/prompts**; `tsx` in dev, `tsup` to bundle |
| LLM | **`@anthropic-ai/sdk`** wrapped in `packages/core`; Zod-based `toolRunner`; structured outputs; prompt caching |
| MCP | **`@modelcontextprotocol/sdk`** (pin the version — mid-transition to v2 for the 2026-07-28 spec); thin tools that call `packages/core` |
| Quality | **Vitest** (unit/integration) + **Playwright** (web e2e); **Biome** (lint+format); **Node 22 LTS** |

### Claude model tiering
| Task | Model | ID |
|---|---|---|
| Plan generation, complex plan repair | Claude Opus 4.8 (adaptive thinking, effort `high`/`xhigh`) | `claude-opus-4-8` |
| Daily conversational check-ins / Q&A | Claude Sonnet 5 | `claude-sonnet-5` |
| Classification (intent routing, red-flag detection) | Claude Haiku 4.5 | `claude-haiku-4-5` |

Use **streaming** for all interactive responses and the **Batch API** (50% cheaper) for
any non-interactive nightly plan regeneration. Note: on Opus 4.8 / Sonnet 5, use
`thinking: {type:'adaptive'}` + `output_config.effort` (no `temperature`/`budget_tokens`).

---

## 7. Sports-science engine spec (all computed in `packages/core`)

> Treat every output as a **trend, not ground truth** — accuracy depends entirely on the
> FTP/LTHR anchors. Recalibration cadence matters more than formula precision.

### Anchors (compute first; recompute every 4–6 weeks)
- **Functional Threshold Pace (FTP) / Critical Speed (CS)** — from best recent efforts or a VDOT fit to a recent race/hard effort. `CS = (D2 − D1) / (T2 − T1)`; `D = CS·T + D'`.
- **Lactate Threshold HR (LTHR)** — from a 30-min TT (avg HR of last 20 min) or estimated from HR history. `HRmax ≈ 208 − 0.7·age` (Tanaka), `HRrest` from observed lows.

### Per-activity load — graceful fallback chain
1. **rTSS (preferred, pace-based):** grade-adjust pace with **Minetti** cost-of-transport → **30-s rolling average → 4th power → mean → 4th root** = Normalized Graded Pace (NGP) → `IF = NGP / FTP` → **`rTSS = IF² · duration_hours · 100`**.
2. **HR fallback (no reliable pace):** **Banister TRIMP** = `duration_min · HRr · 0.64 · e^(1.92·HRr)` (male; female `0.86`, `1.67`), where `HRr = (HRavg − HRrest)/(HRmax − HRrest)`; or **hrTSS** from LTHR zones.
3. **Duration-only fallback:** estimate from duration × an intensity guess.

Store the **daily TSS series** as the single source of truth for everything downstream.

### Performance Management Chart (fitness / fatigue / form)
- **CTL (Fitness, 42-day EWMA):** `CTL = CTL_prev·e^(-1/42) + TSS_today·(1 − e^(-1/42))`
- **ATL (Fatigue, 7-day EWMA):** same, with `7` instead of `42`
- **TSB (Form):** `TSB = CTL_yesterday − ATL_yesterday`
- **Interpretation:** race-day target **TSB ≈ +5..+15 (up to +25)**; normal training **0..−30**; **TSB < −30 sustained > ~1 week ⇒ force a back-off week.**

### Guardrails (injury/overtraining)
- **EWMA-ACWR:** `EWMA = load·λ + (1−λ)·EWMA_prev`, `λ = 2/(N+1)` with N=7 acute, N=28 chronic; ratio = acute/chronic. **Flag & soften when > ~1.3–1.5.** (Also compute the simple rolling `7d / (28d/4)`; sweet spot 0.8–1.3.)
- **CTL ramp cap:** ~**5–7 pts/week** (3–5 for beginners).
- **Taper:** 2–3 weeks, cut volume ~**40–60%** while *holding intensity*, so TSB rises into the race-day window.
- ACWR is scientifically contested — use it as **one** signal alongside TSB and ramp rate, not a hard predictor.

### Zones & distribution
- **HR zones** anchored to LTHR (Friel %LTHR bands) and/or Karvonen HRR.
- **Pace zones** via **VDOT** (Daniels-Gilbert) or Critical Speed; label workouts by Daniels intensities **E / M / T / I / R**.
- Track **rolling weekly time-in-zone** and nudge toward **~80/20 polarized** (Seiler).

### Fitness signals
- **Efficiency Factor (EF)** = NGP (or speed) / avg HR on steady runs; rising EF at fixed HR = improving aerobic fitness.
- **Aerobic decoupling (Pa:HR)** = split a steady run in half, `(EF_1 − EF_2)/EF_1 · 100`; **<5% good, <3% trained, >10% ⇒ build more base before adding intensity.**

**MVP priority pipeline:** `load → PMC → ACWR → zone-distribution` (highest value, fully
Strava-computable). Critical-Speed D′, precise VDOT race prediction, and Minetti downhill
correction are later refinements.

---

## 8. LLM / agent design

- **Never live-call Strava per turn.** Sync activities into the local store in a background/CLI job (handle ~6-hour access-token expiry via refresh tokens — **persist the rotated refresh token each time**; respect rate limits). Expose to Claude only **read-only, already-computed tools** returning values from the local store: `get_training_load`, `get_recent_activities`, `get_pace_zones`, `get_next_workout_inputs`, `get_plan_context`, … Keep the tool set **small and stable** (frozen order) so prompt caching holds.
- **Structured outputs for plans.** Define a JSON schema `plan → weeks → days → sessions { type, distance, target_pace, target_hr_zone, rationale }` and use `output_config.format: {type:'json_schema'}` (or Zod `zodOutputFormat`). **JSON Schema cannot express numeric bounds** — so a **deterministic post-generation validator** in code enforces ramp ≤ cap, no back-to-back hard days (~48h between quality sessions), long-run cap, and rest-day minimums. Repair or reject violating plans. Treat the LLM plan as a proposal; code is the enforcer.
- **Tool runner + strict tools.** Use the SDK `toolRunner` with `strict: true` tools and 1–5 tool-use examples each (raises complex-parameter accuracy ~72% → ~90%).
- **Frozen coaching persona (cached at the prefix).** A large, stable system prompt: identity, evidence-based methodology (periodization, progressive overload, recovery), empathetic tone, an explicit **Explainer** instruction (always attach a short physiological rationale), and hard scope boundaries. Put system prompt + athlete profile **first** with `cache_control` ephemeral (~90% savings); put volatile per-turn data **last**.
- **Explicit, code-enforced safety layer** (the key gap found in the research):
  - **PAR-Q-style medical screening** at onboarding.
  - **Deterministic red-flag detection** (chest pain, dizziness, injury keywords, extreme load spikes) — via Haiku classifier or keyword rules → a “stop and see a professional” response.
  - **Persistent athlete model** (goals, zones, injury history, historical load) passed on every request so constraints are never “forgotten.”
  - **Standard disclaimer:** “for informational and educational purposes only… not a substitute for professional medical advice… consult a qualified healthcare provider before beginning any fitness program.”
- **Proactive check-ins are code-scheduled.** LLMs can't self-initiate (the “Motivator” failure). Drive check-ins from a cron/scheduler on signals (missed runs, negative TSB, upcoming key session), not by waiting for the user.
- **Auditability.** Have Claude cite which metric/guardrail drives each recommendation; log `request_id`, model, usage, and the pre-computed metrics that were fed in.

Reference for the role framing (Planner good, Explainer good, Motivator structurally
weak; guardrails must be explicit): arXiv **2509.26593**.

---

## 9. MVP roadmap — small, value-early, vertically sliced

Each milestone is independently useful and shippable. **Do M0 and M1 before any web/API work.**

### M0 — Walking skeleton + OSS essentials  *(value: analyze a workout from the terminal)*
- Scaffold the pnpm+Turborepo monorepo; `packages/core` + `packages/schemas`; Biome, Vitest, `tsconfig` base.
- Swap the license to **Apache-2.0**; add the day-one OSS files (§10) and CI.
- Implement the load pipeline (rTSS + fallbacks) and PMC (CTL/ATL/TSB) in `core`, with unit tests against known fixtures.
- CLI `stride analyze <activity-file-or-id>`: compute metrics and print a Claude plain-language explanation.
- **Acceptance:** `stride analyze` on a sample activity prints TSS/zones + a coherent explanation; `pnpm test`, lint, typecheck, and CI all green.

### M1 — Connect + coach loop (CLI)  *(value: a usable coach with no UI)*
- `stride connect` — local Strava OAuth (bring-your-own client ID/secret; loopback redirect), tokens stored locally with restrictive file perms; refresh-token rotation handled.
- `stride sync` — backfill history (page `/athlete/activities` at `per_page=200`) + incremental sync via `before/after`; pull detail + streams (`key_by_type=true`) only for what's needed; respect rate limits; expire Strava-sourced cache at 7 days.
- `stride next` — suggest the next workout from TSB + weekly zone-distribution deficit + plan phase + days-to-race; enforce ~48h between quality sessions.
- `stride plan` — generate a simple periodized plan (Base→Build→Peak→Taper, 3 load + 1 recovery), **validated by the code guardrail**.
- **Acceptance:** a real athlete can connect, sync, and get an analyzed workout + next-workout suggestion + a guardrail-valid plan, entirely from the CLI.

### M2 — HTTP API
- Hono app exposing the same `core`: `POST /sync`, `GET /analyze/:id`, `GET /next`, `POST /plan`. Zod-validated; typed `hc` client exported for the web app.
- **Acceptance:** every CLI capability is reachable over HTTP with shared types; API integration tests pass.

### M3 — Web UI
- Vite + React dashboard: PMC chart (CTL/ATL/TSB), per-activity analysis, next workout, plan view. Consumes the API via `hc`. **Includes “View on Strava” / “Powered by Strava” attribution** and a look/feel distinct from Strava.
- **Acceptance:** the dashboard renders real synced data and a generated plan; Playwright smoke test passes.

### M4 — MCP server
- `apps/mcp` on the MCP SDK exposing the coach's read-only tools (Streamable HTTP + OAuth 2.1 when remote). Thin tools calling `core`.
- **Acceptance:** an MCP client (e.g. Claude) can connect and answer “analyze my last run / what should I run next?” using Stride's tools.

### Later (post-MVP)
- **FIT/GPX upload ingestion** as the *compliance-safe durable store* (removes the 7-day-cache and AI-data-restriction dependence on Strava for long-term history).
- Multisport, Critical-Speed D′, precise VDOT race prediction, Garmin/Apple ingestion, hosted multi-user mode (with the full compliance surface from §4).

---

## 10. Open-source setup checklist

**Day-one (block launch on these):**
- `README.md`, **`LICENSE` (Apache-2.0 — replace the current MIT)**, `CODE_OF_CONDUCT.md` (Contributor Covenant), `CONTRIBUTING.md`, `SECURITY.md`
- `.gitignore`, Biome config
- `.github/workflows/ci.yml` (build + lint + typecheck + test) and a real test suite
- `.github/dependabot.yml` (per-repo; also updates Actions)
- Default-branch protection (require PR review + green CI)
- SemVer tags + GitHub Releases + `CHANGELOG.md` (Keep a Changelog format)
- **Actions hardening:** pin every action to a full 40-char commit SHA (not `@v4`), minimal `GITHUB_TOKEN` permissions, no `pull_request_target`

**Phase 2 (first weeks):** `.github/ISSUE_TEMPLATE/*.yml` + `config.yml`, `PULL_REQUEST_TEMPLATE.md`, `CODEOWNERS`, `.editorconfig`, Husky + lint-staged, README/coverage badges, **Changesets** release automation, **DCO** (`Signed-off-by`, not a CLA).

**Phase 3 (as contributors arrive):** `GOVERNANCE.md`, `MAINTAINERS.md`, docs site (Docusaurus or Astro Starlight), `FUNDING.yml`, Conventional Commits enforcement, CodeQL + OpenSSF Scorecard, OpenSSF Best Practices badge.

---

## 11. Config & secrets

Local, per-user. Provide a `.env.example`; never commit real secrets.

```bash
# Strava (bring your own app: https://www.strava.com/settings/api)
STRAVA_CLIENT_ID=...
STRAVA_CLIENT_SECRET=...
STRAVA_REDIRECT_URI=http://localhost:8721/callback   # loopback for the local OAuth flow
# scopes requested: read,activity:read_all,profile:read_all   (read-only)

# Anthropic (bring your own key)
ANTHROPIC_API_KEY=...

# Storage
STRIDE_DATA_DIR=~/.stride            # local store + token file (chmod 600)
STRAVA_API_BASE=https://www.strava.com/api/v3   # parameterized for the 2027 host migration
```

Notes: access tokens expire ~every 6 hours — refresh before expiry and **persist the
newly returned refresh token** (Strava rotates it). Store tokens in a file with
restrictive permissions.

---

## 12. How to work on this (instructions for the agent/contributor)

- **Build in vertical slices.** Get a thing working end-to-end (core → CLI) before broadening. M0/M1 first.
- **Keep numbers out of the LLM.** If you find yourself asking Claude to compute or predict a value, stop — put it in `packages/core` with a unit test.
- **Every plan feature ships with a guardrail test.** The post-generation validator is not optional.
- **Respect Strava constraints by construction** (§4): read-only scopes, owner-only data, 7-day cache expiry, rate-limit handling, attribution in any UI.
- **Where things live:** sports-science + Strava/Claude clients in `packages/core`; Zod schemas in `packages/schemas`; each surface a thin adapter in `apps/*` that imports `core`. Don't duplicate domain logic into a surface.
- **Before committing anything with runtime behavior, run the `verify` flow** (drive the affected command/endpoint, not just tests) and ensure lint + typecheck + tests are green.
- **Cite this document** in PRs when a change relates to a locked decision (stack, local-first, Strava-sourced-personal-use, Apache-2.0).

---

## 13. Glossary & references

**Glossary** — **TSS/rTSS**: (running) Training Stress Score, per-workout load. **NGP**:
Normalized Graded Pace. **FTP**: Functional Threshold Pace. **CS**: Critical Speed.
**LTHR**: Lactate Threshold HR. **CTL**: Chronic Training Load (fitness, 42-d EWMA).
**ATL**: Acute Training Load (fatigue, 7-d EWMA). **TSB**: Training Stress Balance (form
= CTL−ATL). **ACWR**: Acute:Chronic Workload Ratio. **VDOT**: Daniels' pseudo-VO₂max.
**EF**: Efficiency Factor. **PMC**: Performance Management Chart.

**References**
- Strava API docs — <https://developers.strava.com/docs/> · Rate limits — <https://developers.strava.com/docs/rate-limits/> · Auth — <https://developers.strava.com/docs/authentication/> · Webhooks — <https://developers.strava.com/docs/webhooks/>
- Strava API Agreement / Policy — <https://www.strava.com/legal/api> · <https://www.strava.com/legal/api_policy> · Brand guidelines — <https://developers.strava.com/guidelines/>
- TrainingPeaks — rTSS <https://www.trainingpeaks.com/learn/articles/running-training-stress-score-rtss-explained/> · PMC <https://www.trainingpeaks.com/learn/articles/the-science-of-the-performance-manager/>
- ACWR (Williams/Murray 2017, EWMA) — <https://pubmed.ncbi.nlm.nih.gov/28003238/>
- Jack Daniels VDOT — <https://vdoto2.com/calculator/> · Friel zones — <https://www.trainingpeaks.com/learn/articles/joe-friel-s-quick-guide-to-setting-zones/> · 80/20 polarized — <https://www.fasttalklabs.com/pathways/polarized-training/>
- LLM running coach study — arXiv **2509.26593**
- Anthropic Claude API — <https://platform.claude.com/docs/> · MCP — <https://modelcontextprotocol.io/docs/>
- OSS setup — <https://opensource.guide/starting-a-project/> · Keep a Changelog — <https://keepachangelog.com/> · Contributor Covenant — <https://www.contributor-covenant.org/>

---

*This goal prompt is grounded in dated web research (mid-2026). Compliance, pricing,
model IDs, and API details change — re-verify against the primary sources above before
relying on any specific figure. Not legal or medical advice.*
