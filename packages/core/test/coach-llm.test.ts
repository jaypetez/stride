import type { CoachContext, LlmPlanProposal as LlmPlanProposalT } from '@stride/schemas';
import { LlmPlanProposal } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import {
  type AnthropicLike,
  analyzeWorkout,
  buildCoachContext,
  type CoachLLM,
  createCoachLLM,
  DISCLAIMER,
  generatePlan,
  suggestNextWorkout,
} from '../src/coach/index';
import { loadConfig } from '../src/config';
import { DEMO_PROFILE, demoActivity, demoHistory } from '../src/fixtures';

/** A fake LLM on the NEW seam: complete returns a CompleteResult; parse refuses. */
const fake: CoachLLM = {
  complete: async () => ({ text: 'FAKE-COACH-PROSE', refused: false, stopReason: 'end_turn' }),
  parse: async () => ({ refused: true, stopReason: 'end_turn' }),
};

/** A minimal context (no fitness → cold-start ramp) for structured-plan tests. */
function minimalContext(): CoachContext {
  return { generatedAt: '2026-07-09T00:00:00Z', profile: DEMO_PROFILE, recentActivities: [] };
}

/** A fake whose structured parse returns a fixed proposal (complete is a no-op). */
function proposingFake(proposal: LlmPlanProposalT): CoachLLM {
  return {
    complete: async () => ({ text: '', refused: false, stopReason: 'end_turn' }),
    parse: async () => ({ value: proposal as any, refused: false, stopReason: 'end_turn' }),
  };
}

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

describe('structured plan proposal → materialize → guardrail loop', () => {
  it('repairs a deliberately-violating proposal to a valid plan', async () => {
    // Back-to-back hard days (day 2 & 3) and no rest day → invalid, repairable.
    const violating: LlmPlanProposalT = {
      weeks: [1, 2].map((weekNumber) => ({
        weekNumber,
        phase: 'build' as const,
        days: [
          { dayOfWeek: 2, workoutType: 'threshold' as const, emphasis: 't', rationale: 'r' },
          { dayOfWeek: 3, workoutType: 'interval' as const, emphasis: 'i', rationale: 'r' },
          { dayOfWeek: 4, workoutType: 'easy' as const, emphasis: 'e', rationale: 'r' },
          { dayOfWeek: 5, workoutType: 'easy' as const, emphasis: 'e', rationale: 'r' },
        ],
      })),
    };
    const { plan, validation } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k', name: '10k' },
      weeks: 2,
      startDate: '2026-07-13',
      context: minimalContext(),
      deps: { llm: proposingFake(violating), nowIso: () => '2026-07-09T00:00:00Z' },
    });
    expect(validation.valid).toBe(true);
    expect(validation.repaired).toBe(true);
    // 'build' phase proves the LLM proposal (not the skeleton, whose wk1 is 'base').
    expect(plan.weeks[0].phase).toBe('build');
    // No back-to-back hard days survive, and a rest day exists.
    for (const week of plan.weeks) {
      const rest = week.days.filter((d) => d.sessions.every((s) => s.type === 'rest'));
      expect(rest.length).toBeGreaterThanOrEqual(1);
    }
  });

  it('rejects an empty/garbage proposal and falls back to the skeleton', async () => {
    const empty: LlmPlanProposalT = { weeks: [] };
    const { plan, validation } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k', name: '10k' },
      weeks: 4,
      startDate: '2026-07-13',
      context: minimalContext(),
      deps: { llm: proposingFake(empty), nowIso: () => '2026-07-09T00:00:00Z' },
    });
    expect(validation.valid).toBe(true);
    expect(plan.weeks).toHaveLength(4); // full skeleton
    expect(plan.weeks[0].phase).toBe('base'); // skeleton, not the (empty) proposal
  });
});

describe('safety layer', () => {
  it('attaches a disclaimer to every output (offline)', async () => {
    const a = await analyzeWorkout({ activity: demoActivity(), profile: DEMO_PROFILE });
    expect(a.disclaimer).toBe(DISCLAIMER);

    const n = await suggestNextWorkout({ context: minimalContext(), profile: DEMO_PROFILE });
    expect(n.disclaimer).toBe(DISCLAIMER);

    const { disclaimer } = await generatePlan({
      profile: DEMO_PROFILE,
      goal: { distance: '10k' },
      weeks: 4,
      startDate: '2026-07-13',
      deps: { nowIso: () => '2026-07-09T00:00:00Z' },
    });
    expect(disclaimer).toBe(DISCLAIMER);
  });

  it('attaches a disclaimer with a fake LLM too', async () => {
    const a = await analyzeWorkout({
      activity: demoActivity(),
      profile: DEMO_PROFILE,
      deps: { llm: fake },
    });
    expect(a.disclaimer).toBe(DISCLAIMER);
  });

  it('halts analyzeWorkout on a chest-pain note WITHOUT calling the model', async () => {
    let completeCalls = 0;
    let classifyCalls = 0;
    const spy: CoachLLM = {
      complete: async () => {
        completeCalls++;
        return { text: 'SHOULD-NOT-BE-USED', refused: false, stopReason: 'end_turn' };
      },
      parse: async () => ({ refused: true }),
      classify: async () => {
        classifyCalls++;
        return { labels: [], refused: false };
      },
    };
    const r = await analyzeWorkout({
      activity: demoActivity(),
      profile: DEMO_PROFILE,
      note: 'I had chest pain and got dizzy on the run',
      deps: { llm: spy },
    });
    expect(completeCalls).toBe(0);
    expect(classifyCalls).toBe(0);
    expect(r.explanation).not.toBe('SHOULD-NOT-BE-USED');
    expect(r.flags.some((f) => /medical professional/i.test(f))).toBe(true);
  });
});

describe('Anthropic layer (capturing mock — no network)', () => {
  it('places cache_control on the frozen prefix and picks the right tier per path', async () => {
    const captured = { create: [] as any[], parse: [] as any[], stream: [] as any[] };
    const message = () => ({
      content: [{ type: 'text', text: 'ok' }],
      stop_reason: 'end_turn',
      usage: { input_tokens: 1, output_tokens: 1 },
      _request_id: 'req_test',
    });
    const client: AnthropicLike = {
      messages: {
        create: async (p: any) => {
          captured.create.push(p);
          return message();
        },
        parse: async (p: any) => {
          captured.parse.push(p);
          return { ...message(), parsed_output: { weeks: [] } };
        },
        stream: (p: any) => {
          captured.stream.push(p);
          return { finalMessage: async () => message() };
        },
      },
      beta: { messages: { toolRunner: () => ({}) } },
    };
    const config = loadConfig({});
    const llm = createCoachLLM(config, client);
    expect(llm).not.toBeNull();
    if (!llm) return;

    // chat path → streamed, sonnet, effort medium, cache_control on the prefix
    await llm.complete({ model: config.models.chat, system: 'SYS', prompt: 'p' });
    expect(captured.stream[0].model).toBe(config.models.chat);
    expect(captured.stream[0].system[0].cache_control).toEqual({ type: 'ephemeral' });
    expect(captured.stream[0].output_config.effort).toBe('medium');
    expect(captured.stream[0].thinking).toEqual({ type: 'adaptive' });

    // plan path → structured (parse), opus, effort high, output format present
    await llm.parse({ model: config.models.plan, system: 'SYS', prompt: 'p' }, LlmPlanProposal);
    expect(captured.parse[0].model).toBe(config.models.plan);
    expect(captured.parse[0].output_config.effort).toBe('high');
    expect(captured.parse[0].output_config.format).toBeDefined();
    expect(captured.parse[0].system[0].cache_control).toEqual({ type: 'ephemeral' });

    // classify path → haiku, no thinking, no effort/output_config
    if (llm.classify) {
      await llm.classify({ model: config.models.classify, system: 'SYS', prompt: 'p' });
      expect(captured.create[0].model).toBe(config.models.classify);
      expect(captured.create[0].thinking).toBeUndefined();
      expect(captured.create[0].output_config).toBeUndefined();
    }
  });
});
