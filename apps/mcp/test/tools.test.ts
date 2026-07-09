import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig } from '@stride/core';
import { describe, expect, it } from 'vitest';
import type { McpState } from '../src/state';
import {
  toolAnalyze,
  toolNext,
  toolPlan,
  toolRecentActivities,
  toolTrainingLoad,
  toolZones,
} from '../src/tools';

function makeState(): McpState {
  const dir = path.join(os.tmpdir(), `stride-mcp-test-${process.pid}-${Date.now()}`);
  return { config: loadConfig({}), store: new LocalStore(dir), llm: null };
}

describe('MCP tools (demo)', () => {
  const state = makeState();

  it('get_training_load returns fitness metrics', async () => {
    const r = await toolTrainingLoad(state, true);
    expect(r.text).toContain('CTL');
    expect((r.data as any).fitness).not.toBeNull();
  });

  it('get_recent_activities returns summaries', async () => {
    const r = await toolRecentActivities(state, true, 5);
    expect((r.data as any[]).length).toBeGreaterThan(0);
  });

  it('get_pace_zones returns HR + pace zones', async () => {
    const r = await toolZones(state, true);
    expect((r.data as any).hr.length).toBe(5);
    expect((r.data as any).pace.length).toBe(5);
  });

  it('analyze_workout returns metrics + analysis', async () => {
    const r = await toolAnalyze(state, true);
    expect((r.data as any).metrics.tss).toBeGreaterThan(0);
  });

  it('suggest_next_workout returns a workout', async () => {
    const r = await toolNext(state, true);
    expect((r.data as any).type).toBeDefined();
  });

  it('generate_plan returns a valid 6-week plan', async () => {
    const r = await toolPlan(state, { demo: true, race: '10k', weeks: 6 });
    expect((r.data as any).plan.weeks).toHaveLength(6);
    expect((r.data as any).validation.valid).toBe(true);
  });
});
