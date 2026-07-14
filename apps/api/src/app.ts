import { randomUUID } from 'node:crypto';
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
  demoHistory,
  generatePlan,
  latestAcwr,
  latestPmc,
  rampRatePerWeek,
  resolveNowIso,
  STRIDE_CORE_VERSION,
  StravaApiError,
  StravaRateLimitError,
  SyncLockError,
  screenReadiness,
  suggestNextWorkout,
  syncStrava,
  toDailyLoads,
} from '@stride/core';
import { type Activity, AthleteProfile, type DailyLoad, RaceGoal } from '@stride/schemas';
import { type Context, Hono } from 'hono';
import { cors } from 'hono/cors';
import { z } from 'zod';
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

const PlanBody = z.object({
  race: z.enum(['5k', '10k', 'half', 'marathon']).optional(),
  weeks: z.number().int().positive().max(52).optional(),
  start: z.string().optional(),
  date: z.string().optional(),
  note: z.string().optional(),
  demo: z.boolean().optional(),
});

const ScreeningBody = z.object({
  /** One boolean per PAR-Q question; `true` == "yes" (a potential red flag). */
  answers: z.array(z.boolean()),
});

const DemoQuery = z.object({ demo: z.string().optional() });
const ActivitiesQuery = z.object({ demo: z.string().optional(), limit: z.string().optional() });
const AnalyzeQuery = z.object({ note: z.string().optional() });
const NextQuery = z.object({ demo: z.string().optional(), note: z.string().optional() });

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

export function buildApp(state: ApiState) {
  const { store, llm, config } = state;
  const nowIso = () => resolveNowIso(config);
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
  const app = new Hono()
    // The API serves the owner's own Strava data, so CORS is locked to the web
    // UI origin (STRIDE_WEB_ORIGIN) — never `*` (GOAL §4 owner-only visibility).
    .use('*', cors({ origin: config.webOrigin }))
    .use('*', async (c, next) => {
      const id = c.req.header('x-request-id') ?? randomUUID();
      c.set('requestId', id);
      c.header('x-request-id', id);
      await next();
    })
    .onError((err, c) => {
      const requestId = c.get('requestId');
      log.error('request failed', {
        method: c.req.method,
        path: c.req.path,
        requestId,
        err: String(err),
      });
      if (err instanceof StravaRateLimitError)
        return c.json({ error: err.message, requestId }, 429);
      if (err instanceof StravaApiError) return c.json({ error: err.message, requestId }, 502);
      if (err instanceof SyncLockError) return c.json({ error: err.message, requestId }, 409);
      return c.json({ error: err.message || 'Internal Server Error', requestId }, 500);
    })
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
        goal = RaceGoal.parse({ distance: '10k', name: '10k' });
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
        asOfDate: activity.startDate,
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
      const goal = demo
        ? RaceGoal.parse({ distance: '10k', date: '2026-09-06' })
        : ((await store.loadGoal()) ?? undefined);
      const dailyLoads = demo ? undefined : await store.loadDailyLoads();
      const context = buildCoachContext({
        activities,
        profile,
        goal,
        asOfDate: nowIso(),
        dailyLoads,
      });
      const workout = await suggestNextWorkout({ context, profile, note, deps });
      return c.json({
        context: {
          fitness: context.fitness,
          acwr: context.acwr,
          weeklyDistribution: context.weeklyDistribution,
          weeklyVolumeKm: context.weeklyVolumeKm,
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
      const startDate = body.start ?? nowIso().slice(0, 10);
      const dailyLoads = demo ? undefined : await store.loadDailyLoads();
      const context = buildCoachContext({ activities, profile, goal, dailyLoads });
      const result = await generatePlan({
        profile,
        goal,
        weeks,
        startDate,
        context,
        note: body.note,
        deps,
      });
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
