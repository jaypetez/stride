import type { WorkstreamBacklog } from '../types.ts';

/**
 * Quality Audit (November 2026): a whole-repo deep dive of the existing
 * TypeScript surfaces (core, CLI, API, MCP, web) and the Android MVP app.
 * Findings are grouped into actionable issues; each `context` carries the
 * file:line evidence found by the audit. High-severity defects are MVP-phase
 * so the dispatcher hands them out first.
 */

const VITEST_COV = '[Vitest coverage config](https://vitest.dev/config/coverage)';
const HONO_BODY = '[Hono body-limit](https://hono.dev/middleware/builtin/body-limit)';
const A11Y_COMPOSE =
  '[Compose accessibility testing](https://developer.android.com/develop/ui/compose/accessibility/testing)';
const TINK_535 = '[tink#535](https://github.com/google/tink/issues/535)';
const DS_FACTORY =
  '[PreferenceDataStoreFactory](https://developer.android.com/reference/androidx/datastore/preferences/core/PreferenceDataStoreFactory)';

export const backlog: WorkstreamBacklog = {
  workstream: {
    key: 'AUD',
    title: 'Quality Audit',
    area: 'audit',
    description:
      'Findings from the November 2026 whole-repo quality audit: correctness and safety bugs, API hardening, unit-test and coverage gaps, web UX/a11y/e2e, Android robustness, UX, accessibility, tests and CI. Each issue cites file:line evidence and a suggested fix.',
  },
  epics: [
    {
      key: 'AUD-E1',
      title: 'TypeScript correctness and safety',
      phase: 'MVP',
      summary:
        'Fix logic bugs in `packages/core` and the apps that produce wrong numbers, unsafe medical clearance, wrong dates or silent failures.',
    },
    {
      key: 'AUD-E2',
      title: 'Local API and MCP hardening',
      phase: 'MVP',
      summary:
        'The local Hono API serves owner data and triggers syncs; lock it to the owner (loopback, Host allowlist, CSRF guard, headers, limits).',
    },
    {
      key: 'AUD-E3',
      title: 'TypeScript unit-test gaps',
      phase: 'P1',
      summary:
        'Cover the untested core functions, API routes, MCP live mode and factory CLI so regressions in the findings above stay fixed.',
    },
    {
      key: 'AUD-E4',
      title: 'Web UX, accessibility and end-to-end tests',
      phase: 'MVP',
      summary:
        'Make the web dashboard show safety information, real errors and accessible controls, and make Playwright exercise it against the real API.',
    },
    {
      key: 'AUD-E5',
      title: 'TypeScript CI, DX and docs',
      phase: 'P1',
      summary:
        'Close gaps where the gate silently skips files (lint, typecheck, empty test globs, coverage) and keep AGENTS.md truthful.',
    },
    {
      key: 'AUD-E6',
      title: 'Android correctness and robustness',
      phase: 'MVP',
      summary:
        'Sync, FIT parsing, key storage and coroutine handling must fail into a recoverable, visible state instead of crashing or hanging.',
    },
    {
      key: 'AUD-E7',
      title: 'Android UX, accessibility and performance',
      phase: 'P1',
      summary:
        'Error states, process-death restoration, localisation, TalkBack semantics and startup/query performance for the MVP screens.',
    },
    {
      key: 'AUD-E8',
      title: 'Android tests and CI',
      phase: 'P1',
      summary:
        'Unit tests for untested ViewModels and managers, more offline Maestro flows, release-build coverage in CI and honest manifest tests.',
    },
  ],
  issues: [
    // ---------------------------------------------------------------- E1
    {
      key: 'AUD-001',
      epic: 'AUD-E1',
      title: 'Require a complete PAR-Q answer set before granting medical clearance',
      phase: 'MVP',
      priority: 'high',
      size: 'XS',
      type: 'feature',
      agent: true,
      context:
        'Safety bug. `apps/api/src/app.ts:57` accepts `z.array(z.boolean())` of any length and `packages/core/src/coach/safety.ts:157-163` treats missing answers as \'no\', so `POST /profile/screening {"answers": []}` returns `cleared: true` and persists `medicalClearance: true`.\n\nFix: require exactly `PARQ_QUESTIONS.length` answers in the API schema (`.length(n)`) and make `screenReadiness` throw or return not-cleared when the length differs.',
      acceptance: [
        '`POST /profile/screening` with fewer or more answers than `PARQ_QUESTIONS.length` returns 400 and does not change the profile.',
        '`screenReadiness` never returns `cleared: true` for an incomplete answer list.',
        'CLI and MCP screening paths use the same validation.',
      ],
      testPlan: [
        '`pnpm --filter @stride/api test` (empty, short, long, complete answer arrays)',
        '`pnpm --filter @stride/core exec vitest run safety`',
      ],
      files: ['apps/api/src/app.ts', 'packages/core/src/coach/safety.ts'],
    },
    {
      key: 'AUD-002',
      epic: 'AUD-E1',
      title: 'Fix date handling: ISO datetimes, local-vs-UTC asOf, cut series at asOf',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        "Several date bugs produce wrong numbers:\n- `dates.ts:11-13` splits on `-`, so `daysBetween` returns NaN for a full ISO datetime goal date (`context.ts:102`); `daysToRace` becomes null and `derivePhase(NaN)` returns base.\n- `pmc.ts:68-69` and `acwr.ts:54-56` extend to `throughDate` but never truncate, and `context.ts:80` includes activities after `asOf`, so `/analyze/:id` and MCP `analyze_workout` show today's CTL/ATL for an old run.\n- Callers pass UTC `activity.startDate` as `asOfDate` (`app.ts:197`, `apps/mcp/src/tools.ts:105`) while day keys use `startDateLocal`.\n- The ACWR seed (`acwr.ts:57`) averages the first 7 activities, not 7 calendar days.\n\nFix: normalise through `toDateKey` inside `daysBetween`/`keyToMs`, truncate series and recent activities at `asOf`, pass `startDateLocal ?? startDate`, seed ACWR over calendar days.",
      acceptance: [
        '`daysBetween` accepts `YYYY-MM-DD` and full ISO datetimes and returns integers.',
        'Analyzing an older activity reports CTL/ATL/TSB as of that activity date, not today.',
        'An evening run west of UTC is attributed to its local date in `asOf`.',
        'ACWR seed averages 7 calendar days with zeros for rest days; golden contracts regenerated with `pnpm science:golden`.',
      ],
      testPlan: [
        '`pnpm --filter @stride/core exec vitest run dates context pmc acwr`',
        '`pnpm science:golden --check`',
      ],
      files: [
        'packages/core/src/science/dates.ts',
        'packages/core/src/science/pmc.ts',
        'packages/core/src/science/acwr.ts',
        'packages/core/src/coach/context.ts',
        'apps/api/src/app.ts',
        'apps/mcp/src/tools.ts',
        'contracts/science-golden/*.json',
      ],
    },
    {
      key: 'AUD-003',
      epic: 'AUD-E1',
      title: 'Plan generation: pin asOf, validate dates, align to race, never save invalid plans',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        '- Plan context ignores today and `STRIDE_NOW`: `app.ts:248`, `apps/mcp/src/tools.ts:146` and `apps/cli/src/commands/plan.ts:61` call `buildCoachContext` without `asOfDate`; `generatedAt` uses `new Date()` (`context.ts:105`), breaking byte-identical output.\n- `start`/`date` are plain `z.string()` (`app.ts:49-50`, `apps/mcp/src/server.ts:94-95`); garbage produces `NaN-NaN-NaN` dates that get persisted.\n- `buildPlanSkeleton` (`planner.ts:337`) never aligns taper or race day with `goal.date`.\n- If guardrail repair fails, `coach.ts:359` still returns the plan and API/CLI save it (`app.ts:258`, `plan.ts:74`).\n\nFix: pass `asOfDate: nowIso()` everywhere, validate dates as `YYYY-MM-DD` in schemas, derive weeks/start from `goal.date` and add a race-day session, return 422 (CLI non-zero) instead of saving an invalid plan.',
      acceptance: [
        '`STRIDE_NOW` pins `generatedAt` and `daysToRace`; two runs of `plan --demo` are byte-identical.',
        'Invalid `start`/`date` values are rejected with 400 (API), an MCP error and a CLI error before planning.',
        'When a goal date is set, the last plan week ends on race day and contains a race session.',
        'A plan that fails `validatePlan` after repair is never persisted.',
        'Every plan change keeps a guardrail test green (AGENTS.md rule 2).',
      ],
      testPlan: [
        '`pnpm --filter @stride/core exec vitest run planner coach guardrail`',
        '`pnpm --filter @stride/api test`',
        '`pnpm verify`',
      ],
      files: [
        'packages/core/src/coach/planner.ts',
        'packages/core/src/coach/coach.ts',
        'packages/core/src/coach/context.ts',
        'packages/schemas/src/**',
        'apps/api/src/app.ts',
        'apps/mcp/src/server.ts',
        'apps/cli/src/commands/plan.ts',
      ],
    },
    {
      key: 'AUD-004',
      epic: 'AUD-E1',
      title: 'Guardrail: cross-week back-to-back hard days, recovery is not rest, dedupe proposals',
      phase: 'MVP',
      priority: 'high',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '- The back-to-back check (`guardrail.ts:150-160`) only compares days within one week; an LLM proposal can place intervals on day 7 and day 1 of the next week.\n- `isRestDay` (`guardrail.ts:41`) counts `recovery` runs as rest, so a week of daily recovery runs passes `rest_minimum`.\n- `materializeProposal` (`planner.ts:423-439`) keeps duplicate `weekNumber`/`dayOfWeek` entries.\n\nFix: check consecutive hard days on a flat date-sorted list, count only `rest`/empty days as rest, reject or dedupe duplicate proposal keys.',
      acceptance: [
        'A plan with hard sessions on the last day of week N and the first day of week N+1 fails validation.',
        'A week with no `rest` day fails `rest_minimum` even if it contains recovery runs.',
        'Proposals with duplicate week or day keys are rejected (or deduped) with a test for each.',
        'Kotlin golden contracts updated via `pnpm science:golden` if outputs change.',
      ],
      testPlan: [
        '`pnpm --filter @stride/core exec vitest run guardrail planner`',
        '`pnpm science:golden --check`',
      ],
      files: ['packages/core/src/coach/guardrail.ts', 'packages/core/src/coach/planner.ts'],
    },
    {
      key: 'AUD-005',
      epic: 'AUD-E1',
      title: 'Widen STOP/WARN red-flag phrase coverage with table-driven safety tests',
      phase: 'MVP',
      priority: 'high',
      size: 'S',
      type: 'test',
      agent: true,
      context:
        '`packages/core/src/coach/safety.ts:21-31`: `/\\bheart palpitation/` misses bare "palpitations"; "lightheaded", "chest feels tight" and "can\'t breathe" are not matched. `screenReadiness` and `classifierWarnings` have no direct tests (only `coach.test.ts:25-30` touches safety).\n\nFix: broaden the regex set, then add `safety.test.ts` with positive and negative phrase tables (including false-positive guards like "tight hamstrings").',
      acceptance: [
        '`safety.test.ts` has a positive table (at least 20 phrasings) and a negative table (at least 10).',
        'Each listed missed phrase above produces a STOP flag.',
        '`screenReadiness` and `classifierWarnings` have direct unit tests.',
      ],
      testPlan: ['`pnpm --filter @stride/core exec vitest run safety`'],
      files: ['packages/core/src/coach/safety.ts', 'packages/core/test/safety.test.ts'],
    },
    {
      key: 'AUD-006',
      epic: 'AUD-E1',
      title: 'Make the sync lock outlive rate-limit waits and verify ownership on release',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        "- `SYNC_LOCK_TTL_MS` is 10 min (`packages/core/src/sync/lock.ts:12`) but the Strava client can sleep up to 16 min per 429 retry, three times (`strava/client.ts:60-61`); a second process can steal the 'stale' lock mid-sync.\n- `release()` (`lock.ts:57`) deletes the file without checking the owner.\n- Incremental sync sets `truncated` only on rate-limit (`sync.ts:250`); exhausting the page budget without `reachedEnd` still advances the watermark.\n\nFix: heartbeat the lock (or TTL greater than max wait), write an owner token checked by `release`, treat `!reachedEnd` as truncated.",
      acceptance: [
        'A sync that sleeps longer than the TTL keeps its lock (heartbeat test with fake timers).',
        '`release()` from a non-owner is a no-op.',
        'An incremental sync that stops before the end does not advance the watermark past unseen activities.',
      ],
      testPlan: ['`pnpm --filter @stride/core exec vitest run lock sync`'],
      files: ['packages/core/src/sync/lock.ts', 'packages/core/src/sync/sync.ts'],
    },
    {
      key: 'AUD-007',
      epic: 'AUD-E1',
      title: 'Exit non-zero from CLI commands that fail',
      phase: 'P1',
      priority: 'medium',
      size: 'XS',
      type: 'feature',
      agent: true,
      context:
        "`errorMsg(...); return;` never sets `process.exitCode`, so scripted/cron runs look successful. Affected: `apps/cli/src/commands/sync.ts:16,25,62` (including 'Sync failed'), `connect.ts:128,154`, `plan.ts:41,56`, `analyze.ts:48,53`, `next.ts:40`.\n\nFix: have `errorMsg` set `process.exitCode = 1` (or throw into the `run()` wrapper).",
      acceptance: [
        'Every listed failure path exits with code 1.',
        'Success paths still exit 0.',
        '`scripts/smoke.mjs` asserts a failing command exits non-zero.',
      ],
      testPlan: ['`pnpm --filter @stride/cli test`', '`pnpm verify`'],
      files: ['apps/cli/src/commands/*.ts', 'apps/cli/src/ui.ts', 'scripts/smoke.mjs'],
    },
    {
      key: 'AUD-008',
      epic: 'AUD-E1',
      title: 'Make demo goal and history dates relative so demos never go stale',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        "The demo goal `2026-09-06` is hard-coded in `apps/api/src/app.ts:210`, `apps/mcp/src/tools.ts:42` and `apps/cli/src/commands/next.ts:32`, and demo history ends July 2026. `/next?demo` uses today's date, so the race is now in the past, phase is undefined and CTL has decayed.\n\nFix: define the demo goal once in `packages/core/src/fixtures.ts`, relative to the demo history end (or `STRIDE_NOW`), and anchor demo 'today' to the history.",
      acceptance: [
        'One exported demo goal/now in core; no hard-coded demo dates in apps.',
        '`next --demo` shows a future race and a defined phase regardless of the wall clock.',
        'Demo output stays byte-identical under a pinned `STRIDE_NOW`.',
      ],
      testPlan: ['`pnpm test`', '`pnpm verify`'],
      files: [
        'packages/core/src/fixtures.ts',
        'apps/api/src/app.ts',
        'apps/mcp/src/tools.ts',
        'apps/cli/src/commands/next.ts',
      ],
    },
    {
      key: 'AUD-009',
      epic: 'AUD-E1',
      title: 'Robustness: tolerate corrupt records, surface LLM auth errors, single app bootstrap',
      phase: 'P1',
      priority: 'low',
      size: 'S',
      type: 'chore',
      agent: true,
      context:
        '- `loadActivities` parses strictly (`packages/core/src/store/index.ts:147`): one bad record makes every API call 500. Use `safeParse`, skip and warn.\n- LLM failures, including a bad API key, log only at `debug` (`coach.ts:70,165,218,355,375`), silently falling back. Log auth/config errors at `warn`.\n- Versions are hard-coded `0.1.0` (`apps/cli/src/index.ts:37`, `apps/mcp/src/server.ts:46`) while the repo is 0.2.x. Read from `package.json`.\n- `apps/api/src/state.ts` and `apps/mcp/src/state.ts` are identical; config is not validated (`STRIDE_API_PORT` can be NaN, `STRIDE_NOW` is not checked, `config.ts:99,101`). Move to a shared `loadAppState()` with zod env validation.',
      acceptance: [
        'A corrupt activity file is skipped with a warn log; other reads succeed.',
        'A 401 from Anthropic logs at warn level.',
        '`stride --version` and the MCP server version match `package.json`.',
        'Invalid `STRIDE_API_PORT`/`STRIDE_NOW` fail fast with a clear message.',
      ],
      testPlan: ['`pnpm test`', '`pnpm verify`'],
      files: [
        'packages/core/src/store/index.ts',
        'packages/core/src/coach/coach.ts',
        'packages/core/src/config.ts',
        'apps/api/src/state.ts',
        'apps/mcp/src/state.ts',
      ],
    },
    // ---------------------------------------------------------------- E2
    {
      key: 'AUD-010',
      epic: 'AUD-E2',
      title: 'Bind the local API to 127.0.0.1 by default with an optional bearer token',
      phase: 'MVP',
      priority: 'high',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        "`serve({ fetch, port })` in `apps/api/src/index.ts:8` sets no `hostname`, so owner data, `POST /sync` and `POST /plan` are reachable from the LAN without auth.\n\nFix: default `hostname: '127.0.0.1'` (configurable via `STRIDE_API_HOST`), and support an optional `STRIDE_API_TOKEN` bearer check; `stride doctor` warns when bound to a non-loopback address without a token.",
      acceptance: [
        'Default bind address is loopback; documented override.',
        'With `STRIDE_API_TOKEN` set, requests without the bearer token get 401.',
        '`stride doctor` reports the bind address and token status.',
      ],
      testPlan: ['`pnpm --filter @stride/api test`', '`pnpm verify`'],
      files: [
        'apps/api/src/index.ts',
        'apps/api/src/app.ts',
        'apps/cli/src/commands/doctor.ts',
        '.env.example',
      ],
    },
    {
      key: 'AUD-011',
      epic: 'AUD-E2',
      title: 'Block DNS rebinding and CSRF; add secure headers, body limit and rate limits',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context: `CORS (\`apps/api/src/app.ts:103\`) only stops reading responses; a cross-site simple \`POST /sync\` still runs and burns Strava quota, and there is no Host allowlist, so DNS rebinding can drive the API. No \`secureHeaders\`, \`bodyLimit\` or rate limiter exists in \`apps/api/src\`.

Fix (common pattern for local servers): reject requests whose \`Host\` is not a loopback name, require \`Content-Type: application/json\` (forces a CORS preflight) on state-changing routes, add \`hono/secure-headers\` and \`hono/body-limit\` (keep Hono at or above 4.12.25 for the body-limit bypass fix), and a simple limiter on \`/sync\` and LLM routes. See ${HONO_BODY}. (The cloud API's limiter is SEC-013 and out of scope here.)`,
      acceptance: [
        'A request with `Host: evil.example` gets 403.',
        'A `text/plain` or form-encoded POST to a state-changing route gets 415.',
        'Responses carry secure headers; bodies over the limit get 413.',
        '`/sync` and LLM routes return 429 when called in a tight loop.',
      ],
      testPlan: [
        '`pnpm --filter @stride/api test` (host, content-type, size, rate tests)',
        '`pnpm verify`',
      ],
      files: ['apps/api/src/app.ts', 'apps/api/test/*.test.ts'],
      dependsOn: ['AUD-010'],
    },
    {
      key: 'AUD-012',
      epic: 'AUD-E2',
      title: 'Stop leaking internal errors; validate request ids, note length and OAuth scopes',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '- `app.ts:122` returns `err.message` on 500; `strava/client.ts:254` embeds the raw Strava body in the 502 message. Return a generic message and keep detail in logs (with the `requestId`).\n- `x-request-id` is echoed and logged unvalidated (`app.ts:105`). Accept only `^[\\w-]{1,64}$`, else generate a UUID.\n- Free-text `note` is unbounded in API (`app.ts:51,62-63`) and MCP (`server.ts:69,80,96`), driving LLM cost. Add `.max(1000)`.\n- The OAuth callback ignores the granted `scope` (`apps/cli/src/commands/connect.ts:82`); warn if `activity:read_all` was not granted.',
      acceptance: [
        '500 and 502 bodies contain only `{ error, requestId }` with a generic message.',
        'Malformed request ids are replaced, not echoed.',
        'Notes over 1000 characters are rejected (API and MCP).',
        '`stride connect` warns clearly when required scopes are missing.',
      ],
      testPlan: [
        '`pnpm --filter @stride/api test`',
        '`pnpm --filter @stride/mcp test`',
        '`pnpm --filter @stride/cli test`',
      ],
      files: [
        'apps/api/src/app.ts',
        'packages/core/src/strava/client.ts',
        'apps/mcp/src/server.ts',
        'apps/cli/src/commands/connect.ts',
      ],
    },
    // ---------------------------------------------------------------- E3
    {
      key: 'AUD-013',
      epic: 'AUD-E3',
      title: 'Add API route and error-mapping tests for every endpoint',
      phase: 'P1',
      priority: 'medium',
      size: 'M',
      type: 'test',
      agent: true,
      context:
        "`apps/api/test/api.test.ts` has 11 cases. Untested: `/activities` (limit handling, stream stripping), `/pmc`, `GET /plan` (404 and found), `/profile`, the 429/502/409 mappings in `onError`, malformed PAR-Q arrays.\n\nFix: one test per route plus error-mapping tests with stubbed core errors; add a test that enumerates registered routes and fails if one has no test (mirrors QA-007's approach for the cloud API).",
      acceptance: [
        'Every route in `app.ts` has at least one success and one failure test.',
        'Each `onError` mapping (400/404/409/429/500/502) is asserted.',
        'A route-enumeration test fails when a new route lacks tests.',
      ],
      testPlan: ['`pnpm --filter @stride/api test`'],
      files: ['apps/api/test/*.test.ts'],
    },
    {
      key: 'AUD-014',
      epic: 'AUD-E3',
      title: 'Property-test the planner and proposal materializer across 1-52 weeks',
      phase: 'P1',
      priority: 'medium',
      size: 'M',
      type: 'test',
      agent: true,
      context:
        '`packages/core/test/planner.test.ts` is 34 lines and only covers `makeSession`. Untested: `phaseForWeek` for 1-52 weeks, `buildPlanSkeleton` directly, `materializeProposal` with duplicates or out-of-range weeks, `rescaleSession`.\n\nFix: table/property tests (e.g. fast-check or a loop over weeks x races x volumes) asserting every generated plan passes `validatePlan`.',
      acceptance: [
        'For every race distance and 1-52 weeks, the skeleton passes `validatePlan`.',
        '`materializeProposal` and `rescaleSession` have direct tests including edge cases.',
        '`packages/core` planner line coverage at least 90%.',
      ],
      testPlan: ['`pnpm --filter @stride/core exec vitest run planner --coverage`'],
      files: ['packages/core/test/planner.test.ts'],
      dependsOn: ['AUD-004'],
    },
    {
      key: 'AUD-015',
      epic: 'AUD-E3',
      title: 'Unit-test untested core helpers: dates, zones, Strava mapping, OAuth, logging',
      phase: 'P1',
      priority: 'medium',
      size: 'M',
      type: 'test',
      agent: true,
      context:
        'No test references: `computeZones`, `rollingMeanByTime`, `interpolate`, `eachDay`, `addDays`, `upsertDailyLoads`, `mapStreams`, `mapSportType`, `buildAuthorizeUrl`, `exchangeCodeForTokens`, `createLogger`, `logLlmCall`, `toLlmUsage`, `findCoachTool`, `runTools`, and the prompt builders. Date helpers lack DST, leap-day and ISO-datetime cases.\n\nFix: small pure-function tests; logger tests assert level filtering, JSON format and stderr-only output (MCP stdout is the wire).',
      acceptance: [
        'Each listed function has at least one direct test.',
        'Date tests cover DST transitions, 29 Feb and full ISO datetimes.',
        'A logger test proves nothing is written to stdout.',
      ],
      testPlan: ['`pnpm --filter @stride/core test`'],
      files: ['packages/core/test/*.test.ts'],
    },
    {
      key: 'AUD-016',
      epic: 'AUD-E3',
      title: 'Test MCP live mode and errors, and the factory gh client paths',
      phase: 'P1',
      priority: 'low',
      size: 'S',
      type: 'test',
      agent: true,
      context:
        '`apps/mcp/test/tools.test.ts` only exercises demo mode: no live-store, unknown activity id, or tool-error-to-`isError` tests. In `tools/factory`, `gh.ts` and `cli.ts` have no tests and `seed.ts` (668 lines) is partly covered.\n\nFix: a temp-dir `LocalStore` fixture for MCP live tests; a fake `GhClient` for claim/release/status.',
      acceptance: [
        'MCP tests cover live mode, unknown ids and an error surfacing as `isError: true`.',
        'Factory claim, release and status are tested against a fake `GhClient`.',
      ],
      testPlan: ['`pnpm --filter @stride/mcp test`', '`pnpm --filter @stride/factory test`'],
      files: ['apps/mcp/test/*.test.ts', 'tools/factory/test/*.test.ts'],
    },
    {
      key: 'AUD-017',
      epic: 'AUD-E3',
      title: 'Enforce Vitest coverage thresholds for the existing TypeScript packages',
      phase: 'P1',
      priority: 'high',
      size: 'S',
      type: 'chore',
      agent: true,
      context: `Coverage is collected but never enforced: no package has \`coverage.thresholds\` and CI only uploads the artifact. QA-006 covers this for the cloud app but is blocked on CLOUD-005; this issue does it now for \`packages/*\`, \`apps/{cli,api,mcp,web}\` and \`scripts\`.

Set per-package thresholds at current coverage rounded down (core higher, e.g. 85% lines), enable \`thresholds.autoUpdate\` ratcheting or a documented manual ratchet, include root \`scripts/**/*.test.ts\`, and write a coverage summary to \`$GITHUB_STEP_SUMMARY\`. See ${VITEST_COV}.`,
      acceptance: [
        'Each TS package has `coverage.thresholds`; `pnpm coverage` fails below them.',
        'CI `coverage` job fails on a threshold miss and posts a summary table.',
        'Thresholds and the ratchet rule are documented (shared with QA-006 when it lands).',
      ],
      testPlan: ['`pnpm coverage`'],
      files: ['**/vitest.config.ts', '.github/workflows/ci.yml', 'docs/testing/coverage.md'],
    },
    // ---------------------------------------------------------------- E4
    {
      key: 'AUD-018',
      epic: 'AUD-E4',
      title: 'Show red flags and the safety disclaimer in the web dashboard',
      phase: 'MVP',
      priority: 'high',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '`apps/web/src/App.tsx:103-123,148-163,253-291` ignore `data.flags` and `disclaimer` from `/next`, `/analyze` and `/plan`, so a STOP all-rest plan renders as a plain table. The CLI shows flags first.\n\nFix: a flags banner (`role="alert"`, STOP vs WARN styling) above results, plus the disclaimer text.',
      acceptance: [
        'STOP and WARN flags render above Next, Analyze and Plan results with `role="alert"`.',
        'The disclaimer is visible wherever coach output is shown.',
        'Component tests and a Playwright check cover a red-flag note.',
      ],
      testPlan: [
        '`pnpm --filter @stride/web test`',
        '`pnpm --filter @stride/web exec playwright test`',
      ],
      files: ['apps/web/src/App.tsx', 'apps/web/src/components/*.tsx'],
    },
    {
      key: 'AUD-019',
      epic: 'AUD-E4',
      title: 'Run Playwright against the real API, harden it for CI and add axe checks',
      phase: 'P1',
      priority: 'high',
      size: 'M',
      type: 'test',
      agent: true,
      context:
        "The Playwright `webServer` (`apps/web/playwright.config.ts:12`) starts only Vite; the API on 8720 never runs, so e2e passes even when every card says 'API unavailable'. Config lacks `forbidOnly: !!process.env.CI`, CI retries, `trace: 'on-first-retry'`; `reuseExistingServer` is true in CI; no report/trace artifact upload.\n\nFix: two `webServer` entries (API + web), assert demo data renders (TSS chip, chart SVG, plan table), standard CI-conditional settings, upload `playwright-report` on failure, add `@axe-core/playwright` scans of each view (no serious/critical violations).",
      acceptance: [
        'e2e starts the API and asserts real demo data.',
        'CI config: forbidOnly, retries 2, trace on first retry, no server reuse; report uploaded on failure.',
        'axe scan has zero serious/critical violations on every view.',
      ],
      testPlan: ['`pnpm --filter @stride/web exec playwright test`'],
      files: [
        'apps/web/playwright.config.ts',
        'apps/web/e2e/*.spec.ts',
        '.github/workflows/ci.yml',
      ],
    },
    {
      key: 'AUD-020',
      epic: 'AUD-E4',
      title: 'Cover web error, empty and live states and show real API error messages',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        "`App.test.tsx` has 2 cases. Untested: live-mode switch, error and empty states, `PlanCard` submit with guardrail violations. Messages mislead: any analyze error shows 'No activity to analyze yet.' (`App.tsx:147`); plan errors always say 'is the API running?' (`App.tsx:251`), even for a 400 from `weeks=0` (an empty number input gives `Number('') = 0`, `App.tsx:243`).\n\nFix: show the API `error` field, validate `weeks` client-side, add RTL tests with a mocked `api`.",
      acceptance: [
        'Error text comes from the API response when present.',
        'Empty weeks input is rejected client-side.',
        'RTL tests cover live mode, empty, error and guardrail-violation states.',
      ],
      testPlan: ['`pnpm --filter @stride/web test`'],
      files: ['apps/web/src/App.tsx', 'apps/web/src/App.test.tsx'],
    },
    {
      key: 'AUD-021',
      epic: 'AUD-E4',
      title:
        'Fix web accessibility: toggle semantics, chart text alternative, contrast, live regions',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '- The Demo/My data toggle (`App.tsx:23-30`) has `role="tablist"` but plain buttons with no `role="tab"`/`aria-selected`. Use `aria-pressed` buttons or full tabs.\n- The PMC chart (`PmcChart.tsx:35-50`) has no axes, values or text alternative and distinguishes lines by colour only. Add a visually hidden table or `<desc>`, axis labels, dashed/marked lines.\n- `styles.css:12-13`: `--warn #d97706` (about 3.2:1) and `--ok #059669` (about 3.8:1) fail WCAG AA 4.5:1 for text.\n- \'Loading...\' paragraphs have no `role="status"`/`aria-busy`.',
      acceptance: [
        'Toggle exposes correct ARIA state.',
        'PMC chart has a text alternative and non-colour line cues.',
        'All text colours meet 4.5:1 on their background.',
        'axe (AUD-019) reports no violations for these elements.',
      ],
      testPlan: [
        '`pnpm --filter @stride/web test`',
        '`pnpm --filter @stride/web exec playwright test`',
      ],
      files: [
        'apps/web/src/App.tsx',
        'apps/web/src/components/PmcChart.tsx',
        'apps/web/src/styles.css',
      ],
    },
    {
      key: 'AUD-022',
      epic: 'AUD-E4',
      title: 'Replace the placeholder with the official Powered by Strava badge',
      phase: 'P1',
      priority: 'medium',
      size: 'XS',
      type: 'chore',
      agent: false,
      context:
        "Strava compliance (AGENTS.md rule 3). `apps/web/src/components/PoweredByStrava.tsx:9` still renders a text placeholder with a TODO. The official asset must be downloaded from Strava's brand guidelines by a human (licensing), then wired in. The brand orange `#fc5200` as small text is about 3.0:1 contrast; use the official badge image instead of coloured text.",
      acceptance: [
        'The official badge asset is committed with its source noted.',
        'The component renders it with alt text and links per Strava guidelines.',
      ],
      testPlan: ['`pnpm --filter @stride/web test`'],
      files: ['apps/web/src/components/PoweredByStrava.tsx', 'apps/web/public/**'],
    },
    // ---------------------------------------------------------------- E5
    {
      key: 'AUD-023',
      epic: 'AUD-E5',
      title: 'Close silent gate gaps: lint .mjs, typecheck scripts and e2e, drop passWithNoTests',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'chore',
      agent: true,
      context:
        '- `biome.json` `files.includes` omits `**/*.mjs`, so `scripts/smoke.mjs`, `ci-gate.mjs` and `garmin/probe.mjs` are never linted.\n- Root `tsconfig.json` has `"include": []`; `scripts/science-golden.mts` (about 1600 lines) and `scripts/*.test.ts` are never typechecked.\n- `apps/web` `tsc` may not include `e2e/`.\n- `apps/cli` and `apps/web` run `vitest run --passWithNoTests`, hiding a broken include glob.',
      acceptance: [
        '`pnpm lint` covers `.mjs`.',
        '`pnpm typecheck` covers `scripts/` and `apps/web/e2e/`.',
        'No package uses `--passWithNoTests`.',
        '`pnpm check` stays green.',
      ],
      testPlan: ['`pnpm check`'],
      files: [
        'biome.json',
        'tsconfig.json',
        'scripts/tsconfig.json',
        'apps/web/tsconfig.json',
        'apps/*/package.json',
      ],
    },
    {
      key: 'AUD-024',
      epic: 'AUD-E5',
      title: 'Cache Turbo in CI and replace the retired pnpm audit step',
      phase: 'P1',
      priority: 'low',
      size: 'S',
      type: 'chore',
      agent: true,
      context:
        'Jobs cache only the pnpm store, not `.turbo`, so the 6-runner matrix and coverage job redo all work (complements QA-009, which covers Gradle and the pnpm store). The `audit` job soft-passes on the retired npm audit endpoint. `test:affected` uses `origin/main`, which shallow CI checkouts do not fetch.\n\nFix: `actions/cache` for `.turbo` keyed on lockfile+OS; replace `pnpm audit` with `osv-scanner` (or rely on dependency-review explicitly); fetch `origin/main` where `test:affected` runs.',
      acceptance: [
        'Second CI run shows Turbo cache hits.',
        'Dependency vulnerability scanning fails on a known-vulnerable fixture or is explicitly delegated to dependency-review.',
      ],
      testPlan: ['Two consecutive CI runs; compare durations in the PR.'],
      files: ['.github/workflows/ci.yml', 'package.json'],
    },
    {
      key: 'AUD-025',
      epic: 'AUD-E5',
      title: 'Update AGENTS.md: e2e command, coverage policy, POST /plan side effects',
      phase: 'P1',
      priority: 'low',
      size: 'XS',
      type: 'docs',
      agent: true,
      context:
        'AGENTS.md lists `pnpm coverage` without a policy, has no documented web e2e command (and e2e is not in `pnpm check`), and does not say that non-demo `POST /plan` overwrites `goal.json` (`apps/api/src/app.ts:258-261`).',
      acceptance: [
        'AGENTS.md documents the e2e command and whether it is gated.',
        'Coverage thresholds/ratchet policy are linked.',
        '`POST /plan` side effects are documented.',
      ],
      testPlan: ['`pnpm lint`'],
      files: ['AGENTS.md'],
      dependsOn: ['AUD-017', 'AUD-019'],
    },
    // ---------------------------------------------------------------- E6
    {
      key: 'AUD-026',
      epic: 'AUD-E6',
      title: 'Android sync: always settle status, serialise runs, cap Retry-After, wipe safely',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        '- `core/data/.../sync/GarminSyncer.kt:58-144` only catches its own `Abort`; a `ZipException`, Room or date-parse error escapes and the pull-to-refresh spinner stays on Running forever.\n- No lock: daily work and sync-now use different unique names (`SyncManager.kt:51,61`) and race on the backfill cursor.\n- `OkHttpGarminClient.kt:235,242` does not cap `Retry-After`; a 3600 s value parks the worker for an hour.\n- `DataEraser.kt:50-58`: `cancelAll()` does not wait, so a running sync can rewrite rows after `clearAllTables`.\n\nFix: `try/catch/finally` that always sets `Failed`/`Idle`; a `Mutex` around `sync()`; cap `Retry-After` at `maxBackoffMs` and return `RateLimited`; erase only after sync cancellation completes (or under the mutex).',
      acceptance: [
        'Any exception inside sync ends in `SyncProgress.Failed` with a reason.',
        'Two concurrent `sync()` calls run sequentially.',
        'A huge `Retry-After` returns `RateLimited` without a long sleep.',
        'Delete-all during a sync leaves an empty database.',
      ],
      testPlan: ['`./gradlew -p android :core:data:testDebugUnitTest :core:garmin:test`'],
      files: [
        'android/core/data/src/main/**/sync/GarminSyncer.kt',
        'android/core/data/src/main/**/sync/SyncManager.kt',
        'android/core/data/src/main/**/local/DataEraser.kt',
        'android/core/garmin/src/main/**/OkHttpGarminClient.kt',
      ],
    },
    {
      key: 'AUD-027',
      epic: 'AUD-E6',
      title: 'Recover from Keystore/Tink and DataStore corruption instead of crashing at launch',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context: `- \`core/datastore/.../SecretCipher.kt:38-47\` and \`DataStoreModule.kt:46\`: \`AndroidKeysetManager\` errors propagate during Hilt injection in \`Application.onCreate\`, a known OEM Keystore failure (${TINK_535}), so the app crashes on every launch with no recovery.
- Neither DataStore has a corruption handler (\`DataStoreModule.kt:36,42\`); a corrupt \`.preferences_pb\` throws \`CorruptionException\` on every read (${DS_FACTORY}).
- \`SecretStore.kt:32\` reports connected when a value exists, even if it no longer decrypts.

Fix: catch keyset failures, delete the keyset and encrypted secrets, and surface a "re-enter your key" state; add \`ReplaceFileCorruptionHandler { emptyPreferences() }\`; compute \`connected\` from a successful decrypt. Relates to the MASVS checklist (SEC-003).`,
      acceptance: [
        'A simulated keyset failure at startup does not crash; the user is asked to re-enter keys.',
        'A corrupt preferences file is replaced with defaults and logged.',
        'Connected status is false when the stored key cannot be decrypted.',
      ],
      testPlan: ['`./gradlew -p android :core:datastore:testDebugUnitTest` (Robolectric)'],
      files: [
        'android/core/datastore/src/main/**/SecretCipher.kt',
        'android/core/datastore/src/main/**/DataStoreModule.kt',
        'android/core/datastore/src/main/**/SecretStore.kt',
      ],
    },
    {
      key: 'AUD-028',
      epic: 'AUD-E6',
      title: 'Harden Garmin and FIT parsing against malformed, truncated and hostile files',
      phase: 'MVP',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        '- `OkHttpGarminClient.kt:117-126,274-282`: `unzipFit` can throw `ZipException`; `zip.readBytes()` (`:279`) has no size cap (zip bomb).\n- `GarminMappers.kt:34`: `LocalDateTime.parse` throws on a non-standard `startTimeGMT`, aborting the whole page; missing timestamps give 1970 runs.\n- `fit/FitParser.kt:86`: records without a timestamp get time 0; out-of-order timestamps are not sorted.\n- `FitParser.kt:64-68` catches only `FitRuntimeException` despite a never-throws contract.\n- `FitParser.kt:71`: cadence is halved when the session message is missing.\n\nFix: map IO/zip errors to `GarminError.Parse`, cap inflated size (e.g. 50 MB), `runCatching` date parsing and skip bad DTOs, drop/sort timestamps, catch `RuntimeException` into `FitError.Corrupt`, fall back to the Garmin summary sport for cadence.',
      acceptance: [
        'Each case above has a regression test with a fixture.',
        'No malformed input crashes sync; bad items are skipped and logged.',
        'Inflated FIT data above the cap is rejected.',
      ],
      testPlan: ['`./gradlew -p android :core:garmin:test`'],
      files: [
        'android/core/garmin/src/main/**/OkHttpGarminClient.kt',
        'android/core/garmin/src/main/**/GarminMappers.kt',
        'android/core/garmin/src/main/**/fit/FitParser.kt',
        'android/core/garmin/src/test/**',
      ],
    },
    {
      key: 'AUD-029',
      epic: 'AUD-E6',
      title: 'Date runs by where they happened using Garmin startTimeLocal',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '`ActivityDto` (`GarminDtos.kt:14-31`) never reads `startTimeLocal` or a time zone, so `Mappers.kt:18-20` falls back to `clock.zone()` (`GarminSyncer.kt:162`). After travel, runs land on the wrong day and training week, skewing load and plan compliance.\n\nFix: map `startTimeLocal` (or the time-zone id) from the list endpoint and use it for the local date key.',
      acceptance: [
        'A run recorded in another time zone keeps its local date after sync.',
        'Golden science parity is unaffected (`pnpm science:golden --check`).',
      ],
      testPlan: ['`./gradlew -p android :core:garmin:test :core:data:testDebugUnitTest`'],
      files: [
        'android/core/garmin/src/main/**/GarminDtos.kt',
        'android/core/data/src/main/**/Mappers.kt',
      ],
    },
    {
      key: 'AUD-030',
      epic: 'AUD-E6',
      title: 'Coroutine hygiene: app scope handler, rethrow cancellation, safe state transitions',
      phase: 'P1',
      priority: 'medium',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        '- `core/common/.../Dispatchers.kt:47-49`: `appScope` has no `CoroutineExceptionHandler`; failures in `StrideApplication.kt:36-41` or `ProfileEdit.kt:94` crash the process.\n- `OnboardingViewModel.kt:143-152` hands off before `completeOnboarding` and `runCatching` swallows `CancellationException`.\n- `ConnectViewModel.kt:168-195`: connect/disconnect errors leave the spinner on forever.\n- `CoachViewModel.kt:126-137`: `loadFacts` relaunches without cancelling the previous job.\n- `DatabaseHolder.kt:24-29` closes the old database immediately on demo switch while queries may be running.\n\nFix: add a handler; persist then hand off; rethrow cancellation; `try/finally` resetting flags with `ConnectError.Other`; keep and cancel the `Job`; close the old DB after the switch settles.',
      acceptance: [
        'An exception in `appScope` is logged, not fatal.',
        'Onboarding never ends in demo-on but onboarding-incomplete.',
        'Connect/disconnect failures show an error and clear the spinner.',
        'Rapid coach subject changes never show stale facts.',
        'Switching demo mode during a query does not crash.',
      ],
      testPlan: ['`./gradlew -p android testDebugUnitTest`'],
      files: [
        'android/core/common/src/main/**/Dispatchers.kt',
        'android/feature/onboarding/src/main/**/OnboardingViewModel.kt',
        'android/feature/connect/src/main/**/ConnectViewModel.kt',
        'android/feature/coach/src/main/**/CoachViewModel.kt',
        'android/core/database/src/main/**/DatabaseHolder.kt',
      ],
    },
    {
      key: 'AUD-031',
      epic: 'AUD-E6',
      title: 'Refresh Today and Plan at midnight and regenerate stale demo data',
      phase: 'P1',
      priority: 'low',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '`PlanViewModel.kt:66` captures `today` once and `InsightsRepository.today()` only re-emits on DB changes, so screens left open past midnight show yesterday. Demo data is generated only on entry (`DemoMode.kt:61`), so it ages while demo stays on.\n\nFix: a date ticker flow combined into the screens; regenerate demo data on restore when older than a day.',
      acceptance: [
        'With a fake clock crossing midnight, Today and Plan update without user action.',
        'Restoring demo mode after days regenerates data anchored to today.',
      ],
      testPlan: ['`./gradlew -p android testDebugUnitTest`'],
      files: [
        'android/feature/plan/src/main/**/PlanViewModel.kt',
        'android/core/data/src/main/**/insights/**',
        'android/core/data/src/main/**/DemoMode.kt',
      ],
    },
    // ---------------------------------------------------------------- E7
    {
      key: 'AUD-032',
      epic: 'AUD-E7',
      title: 'Show recoverable error states on every screen and persist the last sync failure',
      phase: 'P1',
      priority: 'high',
      size: 'M',
      type: 'feature',
      agent: true,
      context:
        '`ErrorState` exists (`core/ui/.../States.kt:48`) but no screen uses it, and no ViewModel adds `.catch` to its flows (`TodayViewModel.kt:29-43` and others), so a repository exception crashes the app. `SyncStatus` is in-memory only, so a failure reason disappears after process death.\n\nFix: `.catch` into an error UI state with retry in every ViewModel; store the last failure in `UserPreferences` and show it on Today.',
      acceptance: [
        'Each screen ViewModel maps flow errors to `ErrorState` with a retry action (unit test per ViewModel).',
        'Roborazzi baselines added for error states.',
        'The last sync failure survives process death.',
      ],
      testPlan: ['`./gradlew -p android testDebugUnitTest verifyRoborazziDebug`'],
      files: [
        'android/feature/*/src/main/**/*ViewModel.kt',
        'android/core/ui/src/main/**/States.kt',
        'android/core/datastore/src/main/**',
      ],
    },
    {
      key: 'AUD-033',
      epic: 'AUD-E7',
      title: 'Keep onboarding progress across process death with SavedStateHandle',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '`OnboardingViewModel.kt:78` holds step, profile and PAR-Q answers only in a `MutableStateFlow`. Users who switch apps mid-health-check return to Welcome with everything cleared. Restoration tests exist only for app navigation.\n\nFix: back the state with `SavedStateHandle`; add restoration tests for onboarding, connect and settings dialogs (ViewModel tests seeding `SavedStateHandle`; `StateRestorationTester` for `rememberSaveable` UI state).',
      acceptance: [
        'After simulated process death, onboarding resumes at the same step with answers intact.',
        'Restoration tests cover onboarding, connect and settings dialogs.',
      ],
      testPlan: ['`./gradlew -p android :feature:onboarding:testDebugUnitTest`'],
      files: [
        'android/feature/onboarding/src/**',
        'android/feature/connect/src/test/**',
        'android/feature/settings/src/test/**',
      ],
    },
    {
      key: 'AUD-034',
      epic: 'AUD-E7',
      title: 'Move user-facing strings to resources and format dates by device locale',
      phase: 'P1',
      priority: 'medium',
      size: 'L',
      type: 'chore',
      agent: true,
      context:
        '`strings.xml` contains only `app_name`; there are 0 `stringResource` calls across about 80 `Text("...")` literals. Dates are pinned to `Locale.UK` and 24-hour time (`core/ui/.../Format.kt:101-113`, `SettingsViewModel.kt` `LAST_SYNC_FORMAT`).\n\nFix: extract strings per module, use `DateTimeFormatter.ofLocalizedDate` / device locale and 12/24-hour preference, and enable Lint `HardcodedText` for Compose (or a Detekt rule) to prevent regressions.',
      acceptance: [
        'No hard-coded user-facing literals in `feature/*` (enforced by a lint rule).',
        'Dates and times follow device locale and 24-hour setting.',
        'Roborazzi baselines updated; no visual regressions in English.',
      ],
      testPlan: ['`pnpm check:android`'],
      files: [
        'android/feature/*/src/main/**',
        'android/core/ui/src/main/**/Format.kt',
        'android/*/src/main/res/values/strings.xml',
      ],
    },
    {
      key: 'AUD-035',
      epic: 'AUD-E7',
      title: 'TalkBack semantics: PAR-Q context, toggleable rows, non-colour status, a11y checks',
      phase: 'P1',
      priority: 'medium',
      size: 'M',
      type: 'feature',
      agent: true,
      context: `- \`OnboardingScreen.kt:161-172\`: Yes/No chips read only "Yes"/"No" with no question context. Use \`selectableGroup\` with a description, or \`RadioButton\` rows.
- \`SettingsScreen.kt:250-258\`: row \`clickable\` plus \`Switch\` with \`onCheckedChange\` gives two focus targets. Use \`Modifier.toggleable(role = Role.Switch)\` and \`Switch(onCheckedChange = null)\`.
- \`ConnectScreen.kt:149\`: "Expiring soon" is shown by colour only; change the text.
- None of 70 Roborazzi baselines use font scale or RTL; no automated accessibility checks.

Fix the above, add 1.5x/2x font-scale and RTL screenshot variants, and enable \`enableAccessibilityChecks()\` (Compose 1.8+, \`ui-test-junit4-accessibility\`) in Compose UI tests. See ${A11Y_COMPOSE}.`,
      acceptance: [
        'TalkBack reads each PAR-Q answer with its question.',
        'Settings rows have one focus target with switch role.',
        'Expiring keys show "Expiring soon" text.',
        'Font-scale and RTL screenshot variants exist for key screens; accessibility checks pass in Compose tests.',
      ],
      testPlan: ['`./gradlew -p android testDebugUnitTest verifyRoborazziDebug`'],
      files: [
        'android/feature/onboarding/src/main/**/OnboardingScreen.kt',
        'android/feature/settings/src/main/**/SettingsScreen.kt',
        'android/feature/connect/src/main/**/ConnectScreen.kt',
        'android/build-logic/**',
      ],
    },
    {
      key: 'AUD-036',
      epic: 'AUD-E7',
      title: 'Startup and query performance: lazy crypto, StrictMode first, conflate insights',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        '- Keystore/Tink init runs on the main thread during Hilt injection in `super.onCreate` (`StrideApplication.kt:34`), before `DebugTools.install` enables StrictMode (line 35). Make `SecretCipher` lazy (`dagger.Lazy`) and install StrictMode first.\n- `insights/Insights.kt:108-111,117+` reloads all activities, metrics and week streams on every DB emission (many during sync) and uses `startDateLocal!!`. Conflate/debounce, query only the needed date range, remove `!!`.\n- `AnthropicClient.kt:100` uses blocking `execute()`, so cancelling a coach request does not cancel the call; use the cancellable `await()` helper the Garmin client has.',
      acceptance: [
        'No disk/crypto work on the main thread at startup (StrictMode clean in debug).',
        'Insights recompute at most once per settled batch during sync (test with a fake DAO).',
        'Cancelling a coach request cancels the HTTP call.',
      ],
      testPlan: ['`./gradlew -p android testDebugUnitTest`'],
      files: [
        'android/app/src/main/**/StrideApplication.kt',
        'android/core/data/src/main/**/insights/Insights.kt',
        'android/core/coach/src/main/**/AnthropicClient.kt',
      ],
    },
    {
      key: 'AUD-037',
      epic: 'AUD-E7',
      title: 'Small UX fixes: key expiry picker, splash screen, model-unavailable message',
      phase: 'P1',
      priority: 'low',
      size: 'S',
      type: 'feature',
      agent: true,
      context:
        "- The key expiry date picker allows past dates (`ConnectScreen.kt:261-270`); pass `selectableDates` limited to today or later.\n- Blank screen while settings load (`MainActivity.kt:37`); use `core-splashscreen` with `setKeepOnScreenCondition`.\n- The coach model id is hard-coded (`AnthropicClient.kt:120`); a retired model shows a raw 'HTTP 404'. Map 400/404 to a clear 'model unavailable' message.",
      acceptance: [
        'Past dates cannot be selected.',
        'The splash screen stays until settings load.',
        'A 404 from the model endpoint shows a human-readable message.',
      ],
      testPlan: ['`./gradlew -p android testDebugUnitTest`'],
      files: [
        'android/feature/connect/src/main/**/ConnectScreen.kt',
        'android/app/src/main/**/MainActivity.kt',
        'android/core/coach/src/main/**/AnthropicClient.kt',
      ],
    },
    // ---------------------------------------------------------------- E8
    {
      key: 'AUD-038',
      epic: 'AUD-E8',
      title: 'Unit-test SettingsViewModel, ProfileEdit, SyncManager, DocumentWriter and migrations',
      phase: 'P1',
      priority: 'high',
      size: 'M',
      type: 'test',
      agent: true,
      context:
        '`SettingsViewModel` (delete-all, export, demo enter/exit, busy guard, sync window) is covered only by screenshots. `ProfileEditViewModel`, `MainActivityViewModel`, `SyncManager`, `RepositoryCoachFactsSource` and `DocumentWriter` have 0 test references. `MigrationTest.kt` only covers schema v1 with no guard that a version bump ships a migration.\n\nFix: ViewModel tests with fakes; `work-testing` tests for constraints and unique-work policies; a test that fails if the Room `version` exceeds the highest tested migration. Also add per-module Kover floors (e.g. `core:*` 80%, `feature:*` 50%) alongside the global 60% (complements QA-006).',
      acceptance: [
        'Each listed class has unit tests covering its public actions and failure paths.',
        'SyncManager tests assert constraints and unique-work names/policies.',
        'A migration guard test fails on an untested version bump.',
        'Per-module Kover rules enforced in `./gradlew -p android check`.',
      ],
      testPlan: ['`pnpm check:android`'],
      files: [
        'android/feature/settings/src/test/**',
        'android/core/data/src/test/**',
        'android/core/database/src/test/**/MigrationTest.kt',
        'android/build.gradle.kts',
      ],
    },
    {
      key: 'AUD-039',
      epic: 'AUD-E8',
      title: 'Add offline Maestro flows and an emulator smoke job to the Android gate',
      phase: 'P1',
      priority: 'medium',
      size: 'M',
      type: 'test',
      agent: true,
      context:
        'There is no `androidTest` in any module (`HiltTestRunner` is wired but unused); Keystore, WorkManager and Tink only run under Robolectric. One Maestro flow (`maestro/demo-smoke.yaml`) runs nightly or with the `e2e` label, outside the required gate. No flows cover plan creation, coach setup, delete-all, rotation/process death, deep back navigation or dark mode.\n\nFix: add demo-mode flows (`plan.yaml`, `wipe.yaml`, `rotation.yaml` using `killApp`/relaunch, `dark.yaml`) and a small Hilt `androidTest` for SecretStore round-trip; run a fast emulator smoke on PRs. QA-004 (nightly Maestro against the cloud backend) stays separate.',
      acceptance: [
        'At least 4 new offline Maestro flows pass locally on the `Medium_Phone_API_36.0`-class emulator.',
        'An instrumented SecretStore round-trip test exists.',
        'A PR-gating emulator job runs the smoke flow in under 15 minutes.',
      ],
      testPlan: [
        '`maestro test android/maestro/`',
        '`./gradlew -p android connectedDebugAndroidTest`',
      ],
      files: [
        'android/maestro/*.yaml',
        'android/core/datastore/src/androidTest/**',
        '.github/workflows/android.yml',
      ],
    },
    {
      key: 'AUD-040',
      epic: 'AUD-E8',
      title: 'Build and smoke the minified release in CI and fix toothless release tests',
      phase: 'P1',
      priority: 'medium',
      size: 'S',
      type: 'chore',
      agent: true,
      context:
        'R8/minified builds run only in the release dry run, triggered only when release files change (`.github/workflows/release-android.yml:9-15`); `android.yml:59` builds only `assembleDebug`, and `proguard-rules.pro:8` wrongly claims `android.yml` runs `assembleRelease`. `ManifestSecurityTest.releaseIsNotDebuggable` (`:39-43`) is skipped in debug unit tests and `ReleaseConfigTest` only greps `build.gradle.kts`. R8 mapping artifacts are kept 90 days (`release-android.yml:86`).\n\nFix: `:app:assembleRelease` (unsigned) in `android.yml`, assert on the merged release manifest/APK, correct the comment, attach mapping files to GitHub Releases.',
      acceptance: [
        'PR CI builds the minified release variant.',
        'Release manifest assertions run against merged release output.',
        'Mapping files are retained with each release.',
      ],
      testPlan: ['`./gradlew -p android :app:assembleRelease check`'],
      files: [
        '.github/workflows/android.yml',
        '.github/workflows/release-android.yml',
        'android/app/proguard-rules.pro',
        'android/app/src/test/**/ManifestSecurityTest.kt',
      ],
    },
    {
      key: 'AUD-041',
      epic: 'AUD-E8',
      title: 'Decide on database encryption and tighten backup settings',
      phase: 'P1',
      priority: 'low',
      size: 'S',
      type: 'spike',
      agent: false,
      context:
        'The Room database (runs, health-check flags, profile) is stored unencrypted and is included in device-to-device transfer (`data_extraction_rules.xml:17-20`). `AndroidManifest.xml:11` sets `allowBackup="true"` even though every cloud-backup domain is excluded. This is a product/privacy decision: accept and document the risk, or adopt SQLCipher (size, performance, migration cost). Feeds the MASVS L1 checklist (SEC-003).',
      acceptance: [
        'A short ADR records the decision (SQLCipher vs accepted risk) and its rationale.',
        '`allowBackup` and extraction rules match the decision, covered by `ManifestSecurityTest`.',
      ],
      testPlan: ['`./gradlew -p android :app:testDebugUnitTest`'],
      files: [
        'docs/adr/*.md',
        'android/app/src/main/AndroidManifest.xml',
        'android/app/src/main/res/xml/data_extraction_rules.xml',
      ],
    },
  ],
};
