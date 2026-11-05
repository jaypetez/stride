import { mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js';
import {
  COACH_TOOLS,
  DEMO_PROFILE,
  demoActivity,
  demoHistory,
  LocalStore,
  loadConfig,
  toDailyLoads,
} from '@stride/core';
import { RaceGoal } from '@stride/schemas';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { buildServer } from '../src/server';
import type { McpState } from '../src/state';
import { factTool, toolAnalyze, toolNext, toolPlan } from '../src/tools';

const NOW = '2026-07-09T00:00:00Z';
const dirs: string[] = [];

/** A temp-dir LocalStore fixture seeded with the demo athlete's data (live mode). */
async function liveState(seed = true): Promise<McpState> {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'stride-mcp-live-'));
  dirs.push(dir);
  const store = new LocalStore(dir);
  if (seed) {
    const activities = [...demoHistory(), demoActivity()];
    await store.saveProfile({ ...DEMO_PROFILE, name: 'Live Runner' });
    await store.saveActivities(activities);
    await store.saveDailyLoads(toDailyLoads(activities, DEMO_PROFILE));
    await store.saveGoal(
      RaceGoal.parse({ distance: 'half', name: 'City Half', date: '2026-10-04' }),
    );
  }
  return { config: loadConfig({ STRIDE_NOW: NOW }), store, llm: null };
}

/** A store whose every read fails, to prove errors surface (never crash the server). */
function brokenState(): McpState {
  const store = new LocalStore(path.join(os.tmpdir(), 'stride-mcp-broken-unused'));
  const boom = async () => {
    throw new Error('disk on fire');
  };
  Object.assign(store, {
    loadProfile: boom,
    loadActivities: boom,
    loadGoal: boom,
    loadDailyLoads: boom,
  });
  return { config: loadConfig({ STRIDE_NOW: NOW }), store, llm: null };
}

afterAll(async () => {
  await Promise.all(dirs.map((d) => rm(d, { recursive: true, force: true })));
});

describe('MCP tools in live mode (temp-dir LocalStore)', () => {
  let state: McpState;
  beforeAll(async () => {
    state = await liveState();
  });

  it('fact tools read the stored athlete, goal and daily loads', async () => {
    const load = await factTool(state, 'get_training_load', {});
    expect((load.data as any).fitness).not.toBeNull();
    const plan = await factTool(state, 'get_plan_context', {});
    expect((plan.data as any).goal.name).toBe('City Half');
    expect(plan.text).toContain('City Half');
    const recent = await factTool(state, 'get_recent_activities', { limit: 3 });
    expect(recent.data).toHaveLength(3);
    const zones = await factTool(state, 'get_pace_zones', {});
    expect((zones.data as any).hr).toHaveLength(5);
  });

  it('analyze_workout finds a stored activity by id and by "last"', async () => {
    const byId = await toolAnalyze(state, false, 'demo-hist-0');
    expect((byId.data as any).metrics.activityId).toBe('demo-hist-0');
    const last = await toolAnalyze(state, false, 'last');
    expect((last.data as any).metrics.activityId).toBe('demo-activity');
    const implicit = await toolAnalyze(state, false);
    expect((implicit.data as any).metrics.activityId).toBe('demo-activity');
  });

  it('analyze_workout reports an unknown activity id without throwing', async () => {
    const r = await toolAnalyze(state, false, 'does-not-exist');
    expect(r.text).toBe('Activity "does-not-exist" not found.');
    expect(r.data).toBeNull();
  });

  it('suggest_next_workout and generate_plan use the stored goal', async () => {
    const next = await toolNext(state, false);
    expect((next.data as any).disclaimer).toContain('informational');
    const plan = await toolPlan(state, { demo: false, weeks: 4, start: '2026-07-13' });
    expect(plan.text).toContain('City Half');
    expect((plan.data as any).plan.goal.distance).toBe('half');
    expect((plan.data as any).plan.goal.date).toBe('2026-10-04');
    expect((plan.data as any).validation.valid).toBe(true);
  });

  it('an empty live store falls back to defaults rather than failing', async () => {
    const empty = await liveState(false);
    const load = await factTool(empty, 'get_training_load', {});
    expect(load.text).toMatch(/No training-load data/);
    const missing = await toolAnalyze(empty, false, 'last');
    expect(missing.data).toBeNull();
    const plan = await toolPlan(empty, { demo: false });
    expect((plan.data as any).plan.weeks).toHaveLength(8);
    expect((plan.data as any).plan.startDate).toBe('2026-07-09');
  });
});

describe('MCP server over an in-memory transport', () => {
  async function connect(state: McpState): Promise<Client> {
    const server = buildServer(state);
    const [clientT, serverT] = InMemoryTransport.createLinkedPair();
    const client = new Client({ name: 'test', version: '0.0.0' });
    await Promise.all([server.connect(serverT), client.connect(clientT)]);
    return client;
  }

  it('lists the 5 fact tools and 3 action tools', async () => {
    const client = await connect(await liveState());
    const { tools } = await client.listTools();
    const names = tools.map((t) => t.name);
    for (const t of COACH_TOOLS) expect(names).toContain(t.name);
    expect(names).toEqual(
      expect.arrayContaining(['analyze_workout', 'suggest_next_workout', 'generate_plan']),
    );
    expect(names).toHaveLength(8);
    await client.close();
  });

  it('a successful live call is not an error', async () => {
    const client = await connect(await liveState());
    const r = await client.callTool({ name: 'get_plan_context', arguments: {} });
    expect(r.isError).toBeFalsy();
    expect((r.content as any[])[0].text).toContain('City Half');
    await client.close();
  });

  it('an unknown activity id is reported in content, not as a protocol failure', async () => {
    const client = await connect(await liveState());
    const r = await client.callTool({ name: 'analyze_workout', arguments: { id: 'nope' } });
    expect(r.isError).toBeFalsy();
    expect((r.content as any[])[0].text).toContain('Activity "nope" not found.');
    await client.close();
  });

  it('a tool that throws surfaces as isError: true with the message', async () => {
    const client = await connect(brokenState());
    for (const name of ['get_training_load', 'analyze_workout', 'suggest_next_workout']) {
      const r = await client.callTool({ name, arguments: {} });
      expect(r.isError, name).toBe(true);
      expect((r.content as any[])[0].text, name).toContain('disk on fire');
    }
    await client.close();
  });

  it('invalid arguments and unknown tools surface as isError: true', async () => {
    const client = await connect(await liveState());
    const bad = await client.callTool({ name: 'generate_plan', arguments: { weeks: 99 } });
    expect(bad.isError).toBe(true);
    expect((bad.content as any[])[0].text).toMatch(/Input validation error/);
    const unknown = await client.callTool({ name: 'no_such_tool', arguments: {} });
    expect(unknown.isError).toBe(true);
    expect((unknown.content as any[])[0].text).toMatch(/not found/);
    await client.close();
  });
});
