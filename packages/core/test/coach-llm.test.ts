import { describe, expect, it } from 'vitest';
import {
  analyzeWorkout,
  buildCoachContext,
  type CoachLLM,
  generatePlan,
  suggestNextWorkout,
} from '../src/coach/index';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../src/fixtures';

/** A fake LLM that returns fixed prose so we can assert the LLM path is taken. */
const fake: CoachLLM = { complete: async () => 'FAKE-COACH-PROSE' };

describe('coach LLM path (injected fake LLM)', () => {
  it('uses the LLM prose for a workout analysis explanation', async () => {
    const r = await analyzeWorkout({
      activity: demoActivity(),
      profile: DEMO_PROFILE,
      deps: { llm: fake },
    });
    expect(r.explanation).toBe('FAKE-COACH-PROSE');
  });

  it('uses the LLM prose for the next-workout rationale', async () => {
    const context = buildCoachContext({
      activities: demoHistory(),
      profile: DEMO_PROFILE,
      asOfDate: '2026-07-08',
    });
    const suggestion = await suggestNextWorkout({
      context,
      profile: DEMO_PROFILE,
      deps: { llm: fake },
    });
    expect(suggestion.rationale).toBe('FAKE-COACH-PROSE');
  });

  it('uses the LLM prose for the plan summary', async () => {
    const context = buildCoachContext({
      activities: demoHistory(),
      profile: DEMO_PROFILE,
      asOfDate: '2026-07-08',
    });
    const { plan } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k', name: '10k' },
      weeks: 8,
      startDate: '2026-07-13',
      context,
      deps: { llm: fake, nowIso: () => '2026-07-09T00:00:00Z' },
    });
    expect(plan.summary).toBe('FAKE-COACH-PROSE');
  });
});
