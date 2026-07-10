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
  suggestNextWorkout,
  syncStrava,
  toDailyLoads,
} from '@stride/core';
import { type Activity, AthleteProfile, RaceGoal } from '@stride/schemas';
import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { z } from 'zod';
import type { ApiState } from './state';

const log = createLogger('api');

const PlanBody = z.object({
  race: z.enum(['5k', '10k', 'half', 'marathon']).optional(),
  weeks: z.number().int().positive().max(52).optional(),
  start: z.string().optional(),
  date: z.string().optional(),
  demo: z.boolean().optional(),
});

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

  const app = new Hono<{ Variables: { requestId: string } }>();
  app.use('*', cors());
  app.use('*', async (c, next) => {
    const id = c.req.header('x-request-id') ?? randomUUID();
    c.set('requestId', id);
    c.header('x-request-id', id);
    await next();
  });

  app.onError((err, c) => {
    const requestId = c.get('requestId');
    log.error('request failed', {
      method: c.req.method,
      path: c.req.path,
      requestId,
      err: String(err),
    });
    if (err instanceof StravaRateLimitError) return c.json({ error: err.message, requestId }, 429);
    if (err instanceof StravaApiError) return c.json({ error: err.message, requestId }, 502);
    return c.json({ error: err.message || 'Internal Server Error', requestId }, 500);
  });

  app.get('/health', (c) => c.json({ status: 'ok', version: STRIDE_CORE_VERSION }));

  app.get('/profile', async (c) => c.json(await loadProfile()));

  app.get('/activities', async (c) => {
    const limit = Number(c.req.query('limit') ?? '50');
    const demo = c.req.query('demo') === 'true';
    const activities = demo ? [...demoHistory(), demoActivity()] : await store.loadActivities();
    const summaries = activities
      .sort((a, b) => b.startDate.localeCompare(a.startDate))
      .slice(0, limit)
      .map(({ streams, ...rest }) => rest);
    return c.json(summaries);
  });

  app.get('/pmc', async (c) => {
    const demo = c.req.query('demo') === 'true';
    const profile = demo ? DEMO_PROFILE : await loadProfile();
    const activities = demo ? [...demoHistory(), demoActivity()] : await store.loadActivities();
    const dailies = toDailyLoads(activities, profile);
    const pmc = buildPmcSeries(dailies);
    const acwr = buildAcwrSeries(dailies);
    return c.json({
      pmc,
      acwr,
      latest: latestPmc(pmc) ?? null,
      latestAcwr: latestAcwr(acwr) ?? null,
      rampRatePerWeek: rampRatePerWeek(pmc, 2),
    });
  });

  app.get('/analyze/:id', async (c) => {
    const id = c.req.param('id');
    let activity: Activity | undefined;
    let profile = DEMO_PROFILE;
    let activities: Activity[];
    let goal = undefined as ReturnType<typeof RaceGoal.parse> | undefined;

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
    }
    if (!activity) return c.json({ error: `Activity "${id}" not found` }, 404);

    const context = buildCoachContext({ activities, profile, goal, asOfDate: activity.startDate });
    const metrics = computeActivityMetrics(activity, profile);
    const analysis = await analyzeWorkout({ activity, profile, context, deps });
    return c.json({ metrics, analysis });
  });

  app.get('/next', async (c) => {
    const demo = c.req.query('demo') === 'true';
    const profile = demo ? DEMO_PROFILE : await loadProfile();
    const activities = demo ? [...demoHistory(), demoActivity()] : await store.loadActivities();
    const goal = demo
      ? RaceGoal.parse({ distance: '10k', date: '2026-09-06' })
      : ((await store.loadGoal()) ?? undefined);
    const context = buildCoachContext({ activities, profile, goal, asOfDate: nowIso() });
    const workout = await suggestNextWorkout({ context, profile, deps });
    return c.json({
      context: {
        fitness: context.fitness,
        acwr: context.acwr,
        weeklyDistribution: context.weeklyDistribution,
        weeklyVolumeKm: context.weeklyVolumeKm,
      },
      workout,
    });
  });

  app.post('/plan', zValidator('json', PlanBody), async (c) => {
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
    const context = buildCoachContext({ activities, profile, goal });
    const { plan, validation } = await generatePlan({
      profile,
      goal,
      weeks,
      startDate,
      context,
      deps,
    });
    if (!demo) {
      await store.savePlan(plan);
      await store.saveGoal(goal);
    }
    return c.json({ plan, validation });
  });

  app.get('/plan', async (c) => {
    const plan = await store.loadPlan();
    return plan ? c.json(plan) : c.json({ error: 'No saved plan' }, 404);
  });

  app.post('/sync', async (c) => {
    try {
      const result = await syncStrava({ store, config });
      return c.json(result);
    } catch (err) {
      return c.json({ error: (err as Error).message }, 400);
    }
  });

  return app;
}

export type AppType = ReturnType<typeof buildApp>;
