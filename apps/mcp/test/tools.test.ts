import os from 'node:os';
import path from 'node:path';
import { COACH_TOOLS, LocalStore, loadConfig, runCoachTool } from '@stride/core';
import { describe, expect, it } from 'vitest';
import type { McpState } from '../src/state';
import { buildProvider, factTool, toolAnalyze, toolNext, toolPlan } from '../src/tools';

function makeState(): McpState {
  const dir = path.join(os.tmpdir(), `stride-mcp-test-${process.pid}-${Date.now()}`);
  // Pin the clock so the demo provider is byte-reproducible across calls.
  return {
    config: loadConfig({ STRIDE_NOW: '2026-07-09T00:00:00Z' }),
    store: new LocalStore(dir),
    llm: null,
  };
}

describe('MCP fact tools (demo, via the shared core toolset)', () => {
  const state = makeState();

  it('get_training_load returns fitness metrics', async () => {
    const r = await factTool(state, 'get_training_load', { demo: true });
    expect(r.text).toContain('CTL');
    expect((r.data as any).fitness).not.toBeNull();
  });

  it('get_recent_activities returns summaries', async () => {
    const r = await factTool(state, 'get_recent_activities', { demo: true, limit: 5 });
    expect((r.data as any[]).length).toBeGreaterThan(0);
  });

  it('get_pace_zones returns HR + pace zones', async () => {
    const r = await factTool(state, 'get_pace_zones', { demo: true });
    expect((r.data as any).hr.length).toBe(5);
    expect((r.data as any).pace.length).toBe(5);
  });

  it('get_next_workout_inputs exposes the decision inputs', async () => {
    const r = await factTool(state, 'get_next_workout_inputs', { demo: true });
    expect((r.data as any).tsb).not.toBeUndefined();
  });

  it('get_plan_context exposes the plan-building context', async () => {
    const r = await factTool(state, 'get_plan_context', { demo: true });
    expect((r.data as any).experienceLevel).toBeDefined();
  });

  it('MCP fact tools return the same values as the core toolset', async () => {
    const provider = await buildProvider(state, true);
    for (const tool of COACH_TOOLS) {
      const core = await runCoachTool(provider, tool.name);
      const mcp = await factTool(state, tool.name, { demo: true });
      expect(mcp.data).toEqual(core.data);
    }
  });
});

describe('MCP action tools (demo) carry the disclaimer', () => {
  const state = makeState();

  it('analyze_workout returns metrics + analysis + disclaimer', async () => {
    const r = await toolAnalyze(state, true);
    expect((r.data as any).metrics.tss).toBeGreaterThan(0);
    expect((r.data as any).analysis.disclaimer).toContain('informational');
    expect((r.data as any).disclaimer).toContain('informational');
  });

  it('suggest_next_workout returns a workout with a disclaimer', async () => {
    const r = await toolNext(state, true);
    expect((r.data as any).type).toBeDefined();
    expect((r.data as any).disclaimer).toContain('informational');
  });

  it('generate_plan returns a valid 6-week plan with a disclaimer', async () => {
    const r = await toolPlan(state, { demo: true, race: '10k', weeks: 6 });
    expect((r.data as any).plan.weeks).toHaveLength(6);
    expect((r.data as any).validation.valid).toBe(true);
    expect((r.data as any).disclaimer).toContain('informational');
  });
});
