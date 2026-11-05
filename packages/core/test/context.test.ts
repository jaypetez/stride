import { Activity, RaceGoal } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { buildCoachContext } from '../src/coach/context';
import { DEMO_PROFILE, demoActivity, demoGoal, demoHistory, demoNowIso } from '../src/fixtures';

const all = () => [...demoHistory(), demoActivity()];

describe('buildCoachContext as-of semantics (AUD-002)', () => {
  it('reports CTL/ATL/TSB as of an older activity, not as of the latest data', () => {
    const activities = all();
    const old = activities.find((a) => a.id === 'demo-hist-10');
    if (!old) throw new Error('fixture missing');
    const asOf = old.startDateLocal ?? old.startDate;

    const atOld = buildCoachContext({ activities, profile: DEMO_PROFILE, asOfDate: asOf });
    const latest = buildCoachContext({ activities, profile: DEMO_PROFILE });

    expect(atOld.fitness?.date).toBe('2026-06-04');
    expect(atOld.acwr?.date).toBe('2026-06-04');
    expect(latest.fitness?.date).toBe('2026-07-08');
    expect(atOld.fitness?.ctl).toBeLessThan(latest.fitness?.ctl ?? 0);
    // No activity after the reference day leaks into the recent list.
    expect(atOld.recentActivities.every((a) => a.date <= '2026-06-04')).toBe(true);
    expect(atOld.recentActivities[0].date).toBe('2026-06-04');
  });

  it('attributes an evening run west of UTC to its LOCAL date', () => {
    // 19:30 in Los Angeles on Jul 8 = 02:30 UTC on Jul 9.
    const evening = Activity.parse({
      id: 'evening',
      source: 'manual',
      sportType: 'run',
      name: 'Evening Run',
      startDate: '2026-07-09T02:30:00Z',
      startDateLocal: '2026-07-08T19:30:00',
      timezone: 'America/Los_Angeles',
      distance: 10_000,
      movingTime: 3000,
      elapsedTime: 3000,
      averageSpeed: 10_000 / 3000,
    });
    const activities = [...demoHistory(), evening];
    const ctx = buildCoachContext({
      activities,
      profile: DEMO_PROFILE,
      asOfDate: evening.startDateLocal ?? evening.startDate,
    });
    expect(ctx.fitness?.date).toBe('2026-07-08');
    expect(ctx.recentActivities[0]).toMatchObject({ name: 'Evening Run', date: '2026-07-08' });
    expect(ctx.weeklyVolumeKm).toBeGreaterThanOrEqual(10);
  });

  it('accepts a full ISO datetime goal date (integer daysToRace, defined phase)', () => {
    const ctx = buildCoachContext({
      activities: all(),
      profile: DEMO_PROFILE,
      goal: RaceGoal.parse({ distance: '10k', date: '2026-09-06T08:00:00Z' }),
      asOfDate: '2026-07-09T00:00:00Z',
    });
    expect(ctx.daysToRace).toBe(59);
    expect(ctx.planPhase).toBe('build');
  });

  it('pins generatedAt to the supplied asOfDate (deterministic output)', () => {
    const ctx = buildCoachContext({
      activities: all(),
      profile: DEMO_PROFILE,
      asOfDate: '2026-07-09T00:00:00Z',
    });
    expect(ctx.generatedAt).toBe('2026-07-09T00:00:00Z');
  });
});

describe('demo goal / now (AUD-008)', () => {
  it('anchors demo "today" to the bundled history unless pinned', () => {
    expect(demoNowIso()).toBe('2026-07-09T00:00:00Z');
    expect(demoNowIso('2027-01-01T00:00:00Z')).toBe('2027-01-01T00:00:00Z');
  });

  it('puts the demo race in the future with a defined phase, wherever "now" is', () => {
    for (const now of [demoNowIso(), '2027-03-15T12:00:00Z']) {
      const ctx = buildCoachContext({
        activities: all(),
        profile: DEMO_PROFILE,
        goal: demoGoal(now),
        asOfDate: now,
      });
      expect(ctx.daysToRace).toBeGreaterThan(0);
      expect(ctx.planPhase).toBeDefined();
    }
    expect(demoGoal().date).toBe('2026-09-06');
  });
});
