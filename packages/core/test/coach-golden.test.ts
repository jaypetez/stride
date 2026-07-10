import { describe, expect, it } from 'vitest';
import { analyzeWorkout, generatePlan } from '../src/coach/index';
import { DEMO_PROFILE, demoActivity } from '../src/fixtures';

describe('coach golden snapshots (deterministic, offline)', () => {
  it('analyzes a demo workout the same way every time', async () => {
    const r = await analyzeWorkout({ activity: demoActivity(), profile: DEMO_PROFILE });
    expect({ headline: r.headline, explanation: r.explanation }).toMatchSnapshot();
  });

  it('builds a stable periodized 10k plan', async () => {
    const { plan } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k', name: '10k' },
      weeks: 8,
      startDate: '2026-07-13',
      deps: { nowIso: () => '2026-07-09T00:00:00Z' },
    });
    // Snapshot only stable structure — never volatile ids/dates.
    const projection = plan.weeks.map((w) => ({
      week: w.weekNumber,
      phase: w.phase,
      targetTss: w.targetTss,
      sessions: w.days
        .flatMap((d) => d.sessions)
        .filter((s) => s.type !== 'rest')
        .map((s) => s.type),
    }));
    expect(projection).toMatchSnapshot();
  });
});
