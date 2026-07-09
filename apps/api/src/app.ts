import { zValidator } from '@hono/zod-validator';
import {
  analyzeWorkout,
  buildAcwrSeries,
  buildCoachContext,
  buildPmcSeries,
  computeActivityMetrics,
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
  generatePlan,
  latestAcwr,
  latestPmc,
  rampRatePerWeek,
  STRIDE_CORE_VERSION,
  suggestNextWorkout,
  syncStrava,
  toDailyLoads,
} from '@stride/core';
import { type Activity, AthleteProfile, RaceGoal } from '@stride/schemas';
import { Hono } from 'hono';
import { cors } from 'hono/cors';
import { z } from 'zod';
import type { ApiState } from './state';

const PlanBody = z.object({
  race: z.enum(['5k', '10k', 'half', 'marathon']).optional(),
  weeks: z.number().int().positive().max(52).optional(),
  start: z.string().optional(),
  date: z.string().optional(),
  demo: z.boolean().optional(),
});

const nowIso = () => new Date().toISOString();

export function buildApp(state: ApiState) {
  const { store, llm, config } = state;
  const deps = { llm, models: config.models };

  async function loadProfile(): Promise<AthleteProfile> {
    return (await store.loadProfile()) ?? AthleteProfile.parse({});
  }

  const app = new Hono();
  app.use('*', cors());

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
