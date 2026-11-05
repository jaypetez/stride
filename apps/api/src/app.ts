import { zValidator } from '@hono/zod-validator';
import {
  analyzeWorkout,
  buildAcwrSeries,
  buildCoachContext,
  buildPmcSeries,
  computeActivityMetrics,
  createLogger,
  DEMO_PROFILE,
  demoActivity,
  demoGoal,
  demoHistory,
  demoNowIso,
  generatePlan,
  latestAcwr,
  latestPmc,
  MAX_NOTE_LENGTH,
  rampRatePerWeek,
  resolveNowIso,
  ScreeningAnswers,
  STRIDE_CORE_VERSION,
  StravaApiError,
  StravaRateLimitError,
  SyncLockError,
  screenReadiness,
  suggestNextWorkout,
  syncStrava,
  toDailyLoads,
} from '@stride/core';
import { type Activity, AthleteProfile, type DailyLoad, IsoDate, RaceGoal } from '@stride/schemas';
import { type Context, Hono } from 'hono';
import { cors } from 'hono/cors';
import { HTTPException } from 'hono/http-exception';
import { z } from 'zod';
import {
  apiSecureHeaders,
  bearerToken,
  DEFAULT_RATE_LIMITS,
  hostGuard,
  limitBody,
  type RateLimitOptions,
  rateLimit,
  requestId,
  requireJson,
} from './security';
import type { ApiState } from './state';

const log = createLogger('api');

/**
 * A per-request id, set by middleware and echoed on every response (including
 * errors). Declared on Hono's `ContextVariableMap` so `c.get('requestId')` is
 * typed in route handlers AND in the standalone zValidator failure hook.
 */
declare module 'hono' {
  interface ContextVariableMap {
    requestId: string;
  }
}

/** Free-text note: forwarded to the LLM and the red-flag screen, so bounded. */
const Note = z.string().max(MAX_NOTE_LENGTH);

const PlanBody = z.object({
  race: z.enum(['5k', '10k', 'half', 'marathon']).optional(),
  weeks: z.number().int().positive().max(52).optional(),
  /** Plan start, YYYY-MM-DD (validated before planning). */
  start: IsoDate.optional(),
  /** Goal race date, YYYY-MM-DD (validated before planning). */
  date: IsoDate.optional(),
  note: Note.optional(),
  demo: z.boolean().optional(),
});

const ScreeningBody = z.object({
  /**
   * Exactly one boolean per PAR-Q question; `true` == "yes" (a potential red
   * flag). A short/long list is a 400 — a missing answer is never a "no".
   */
  answers: ScreeningAnswers,
});

const DemoQuery = z.object({ demo: z.string().optional() });
const ActivitiesQuery = z.object({ demo: z.string().optional(), limit: z.string().optional() });
const AnalyzeQuery = z.object({ note: Note.optional() });
const NextQuery = z.object({ demo: z.string().optional(), note: Note.optional() });

/**
 * zValidator failure hook. By default a validation failure returns a bare `400`
 * that BYPASSES `onError`; this routes it through the same `{ error, requestId }`
 * envelope (with the `x-request-id` header the request-id middleware sets).
 */
type ZodIssueLike = { path: PropertyKey[]; message: string };
const onInvalid = (
  result: { success: boolean; error?: unknown },
  c: Context,
): Response | undefined => {
  if (result.success) return undefined;
  const issues = (result.error as { issues?: ZodIssueLike[] }).issues ?? [];
  const detail = issues.map((i) => `${i.path.join('.') || 'body'}: ${i.message}`).join('; ');
  return c.json(
    { error: `Invalid request — ${detail || 'validation failed'}`, requestId: c.get('requestId') },
    400,
  );
};

/** Tunables for the hardening middleware (tests shrink them). */
export interface ApiOptions {
  rateLimits?: { sync?: RateLimitOptions; llm?: RateLimitOptions };
  bodyLimitBytes?: number;
}

export function buildApp(state: ApiState, options: ApiOptions = {}) {
  const { store, llm, config } = state;
  const nowIso = () => resolveNowIso(config);
  /** Demo "today": STRIDE_NOW when pinned, else anchored to the bundled demo history. */
  const demoNow = () => demoNowIso(config.now);
  const deps = {
    llm,
    models: config.models,
    nowIso: config.now ? () => resolveNowIso(config) : undefined,
  };

  async function loadProfile(): Promise<AthleteProfile> {
    return (await store.loadProfile()) ?? AthleteProfile.parse({});
  }

  // Routes are CHAINED so the resulting type carries the full route schema —
  // that is what makes the exported `AppType` usable by the web's typed `hc`
  // client (Hono RPC). Do not split this into separate `app.get(...)` statements.
  // Rate limiters are created per app so each instance has its own buckets.
  const llmLimit = rateLimit({
    ...DEFAULT_RATE_LIMITS.llm,
    ...options.rateLimits?.llm,
  });

  const app = new Hono()
    // --- Hardening middleware (see ./security.ts). Order matters: the request
    // id and secure headers first so every rejection carries them; Host + CORS before auth so the
    // browser's (credential-less) preflight is answered.
    .use('*', requestId())
    .use('*', apiSecureHeaders())
    .use('*', hostGuard(config))
    // The API serves the owner's own Strava data, so CORS is locked to the web
    // UI origin (STRIDE_WEB_ORIGIN) — never `*` (GOAL §4 owner-only visibility).
    .use('*', cors({ origin: config.webOrigin }))
    .use('*', bearerToken(config.apiToken))
    .use('*', requireJson())
    .use('*', limitBody(options.bodyLimitBytes))
    .use('/sync', rateLimit({ ...DEFAULT_RATE_LIMITS.sync, ...options.rateLimits?.sync }))
    .use('/analyze/*', llmLimit)
    .use('/next', llmLimit)
    .use('/plan', async (c, next) => (c.req.method === 'POST' ? llmLimit(c, next) : next()))
    // --- end hardening middleware
    .onError((err, c) => {
      const requestId = c.get('requestId');
      log.error('request failed', {
        method: c.req.method,
        path: c.req.path,
        requestId,
        err: String(err),
      });
      // Only fixed, user-safe messages leave the process; details stay in the
      // log line above, correlated by requestId.
      if (err instanceof StravaRateLimitError)
        return c.json({ error: err.message, requestId }, 429);
      if (err instanceof StravaApiError)
        return c.json({ error: 'Upstream Strava request failed', requestId }, 502);
      if (err instanceof SyncLockError) return c.json({ error: err.message, requestId }, 409);
      // Framework-raised client errors (e.g. a malformed JSON body → 400) keep
      // their status instead of being reported as a server fault.
      if (err instanceof HTTPException && err.status < 500)
        return c.json({ error: err.message || 'Bad Request', requestId }, err.status);
      return c.json({ error: 'Internal Server Error', requestId }, 500);
    })
    .notFound((c) =>
      c.json(
        { error: `No route for ${c.req.method} ${c.req.path}`, requestId: c.get('requestId') },
        404,
      ),
    )
    .get('/health', (c) => c.json({ status: 'ok', version: STRIDE_CORE_VERSION }))
    .get('/profile', async (c) => c.json(await loadProfile()))
    .post('/profile/screening', zValidator('json', ScreeningBody, onInvalid), async (c) => {
      const { answers } = c.req.valid('json');
      const screening = screenReadiness(answers);
      const current = await loadProfile();
      const profile = AthleteProfile.parse({
        ...current,
        medicalClearance: screening.cleared,
        healthFlags: screening.healthFlags,
        updatedAt: nowIso(),
      });
      await store.saveProfile(profile);
      return c.json({ screening, profile });
    })
    .get('/activities', zValidator('query', ActivitiesQuery, onInvalid), async (c) => {
      const { demo: demoQ, limit: limitQ } = c.req.valid('query');
      const limit = Number(limitQ ?? '50');
      const demo = demoQ === 'true';
      const activities = demo ? [...demoHistory(), demoActivity()] : await store.loadActivities();
      const summaries = activities
        .sort((a, b) => b.startDate.localeCompare(a.startDate))
        .slice(0, Number.isFinite(limit) && limit > 0 ? limit : 50)
        .map(({ streams, ...rest }) => rest);
      return c.json(summaries);
    })
    .get('/pmc', zValidator('query', DemoQuery, onInvalid), async (c) => {
      const demo = c.req.valid('query').demo === 'true';
      // Live PMC reads the durable daily-load series (survives the 7-day raw
      // cache); demo computes it from bundled fixtures.
      const dailies = demo
        ? toDailyLoads([...demoHistory(), demoActivity()], DEMO_PROFILE)
        : await store.loadDailyLoads();
      const pmc = buildPmcSeries(dailies);
      const acwr = buildAcwrSeries(dailies);
      return c.json({
        pmc,
        acwr,
        latest: latestPmc(pmc) ?? null,
        latestAcwr: latestAcwr(acwr) ?? null,
        rampRatePerWeek: rampRatePerWeek(pmc, 2),
      });
    })
    .get('/analyze/:id', zValidator('query', AnalyzeQuery, onInvalid), async (c) => {
      const id = c.req.param('id');
      const note = c.req.valid('query').note;
      let activity: Activity | undefined;
      let profile = DEMO_PROFILE;
      let activities: Activity[];
      let goal = undefined as ReturnType<typeof RaceGoal.parse> | undefined;
      let dailyLoads: DailyLoad[] | undefined;

      if (id === 'demo') {
        activity = demoActivity();
        activities = [...demoHistory(), activity];
        goal = demoGoal(demoNow());
      } else {
        profile = await loadProfile();
        activities = await store.loadActivities();
        activity =
          id === 'last'
            ? [...activities].sort((a, b) => b.startDate.localeCompare(a.startDate))[0]
            : activities.find((a) => a.id === id);
        goal = (await store.loadGoal()) ?? undefined;
        dailyLoads = await store.loadDailyLoads();
      }
      if (!activity)
        return c.json({ error: `Activity "${id}" not found`, requestId: c.get('requestId') }, 404);

      const context = buildCoachContext({
        activities,
        profile,
        goal,
        // The athlete's LOCAL day of the run (day keys use startDateLocal).
        asOfDate: activity.startDateLocal ?? activity.startDate,
        dailyLoads,
      });
      const metrics = computeActivityMetrics(activity, profile);
      const analysis = await analyzeWorkout({ activity, profile, context, note, deps });
      return c.json({ metrics, analysis, disclaimer: analysis.disclaimer, flags: analysis.flags });
    })
    .get('/next', zValidator('query', NextQuery, onInvalid), async (c) => {
      const { demo: demoQ, note } = c.req.valid('query');
      const demo = demoQ === 'true';
      const profile = demo ? DEMO_PROFILE : await loadProfile();
      const activities = demo ? [...demoHistory(), demoActivity()] : await store.loadActivities();
      const goal = demo ? demoGoal(demoNow()) : ((await store.loadGoal()) ?? undefined);
      const dailyLoads = demo ? undefined : await store.loadDailyLoads();
      const context = buildCoachContext({
        activities,
        profile,
        goal,
        asOfDate: demo ? demoNow() : nowIso(),
        dailyLoads,
      });
      const workout = await suggestNextWorkout({ context, profile, note, deps });
      return c.json({
        context: {
          fitness: context.fitness,
          acwr: context.acwr,
          weeklyDistribution: context.weeklyDistribution,
          weeklyVolumeKm: context.weeklyVolumeKm,
          goal: context.goal,
          daysToRace: context.daysToRace,
          planPhase: context.planPhase,
        },
        workout,
        disclaimer: workout.disclaimer,
        flags: workout.flags,
      });
    })
    .post('/plan', zValidator('json', PlanBody, onInvalid), async (c) => {
      const body = c.req.valid('json');
      const demo = body.demo ?? false;
      const profile = demo ? DEMO_PROFILE : await loadProfile();
      const activities = demo ? demoHistory() : await store.loadActivities();
      const storedGoal = demo ? null : await store.loadGoal();
      const distance = body.race ?? storedGoal?.distance ?? '10k';
      const goal = RaceGoal.parse({
        distance,
        name: storedGoal?.name ?? distance,
        date: body.date ?? storedGoal?.date,
      });
      const weeks = body.weeks ?? 8;
      const asOfDate = demo ? demoNow() : nowIso();
      const startDate = body.start ?? asOfDate.slice(0, 10);
      const dailyLoads = demo ? undefined : await store.loadDailyLoads();
      const context = buildCoachContext({ activities, profile, goal, asOfDate, dailyLoads });
      const result = await generatePlan({
        profile,
        goal,
        weeks,
        startDate,
        context,
        note: body.note,
        deps,
      });
      // A plan that still breaks a guardrail after repair is never persisted
      // (nor returned as if usable): 422 with the violations.
      if (!result.validation.valid) {
        return c.json(
          {
            error: 'Plan failed guardrail validation after repair; it was not saved.',
            requestId: c.get('requestId'),
            validation: result.validation,
          },
          422,
        );
      }
      if (!demo) {
        await store.savePlan(result.plan);
        await store.saveGoal(goal);
      }
      return c.json({
        plan: result.plan,
        validation: result.validation,
        disclaimer: result.disclaimer,
        flags: result.flags,
      });
    })
    .get('/plan', async (c) => {
      const plan = await store.loadPlan();
      return plan
        ? c.json(plan)
        : c.json({ error: 'No saved plan', requestId: c.get('requestId') }, 404);
    })
    .post('/sync', async (c) => {
      // No local try/catch: let errors propagate to `onError`, which maps them
      // to the right status (rate-limit 429, api 502, lock 409, else 500) and
      // the standard `{ error, requestId }` envelope.
      const result = await syncStrava({ store, config });
      return c.json(result);
    });

  return app;
}

export type AppType = ReturnType<typeof buildApp>;
