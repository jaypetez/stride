import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { findCoachTool } from '@stride/core';
import { z } from 'zod';
import type { McpState } from './state';
import { factTool, type ToolResult, toolAnalyze, toolNext, toolPlan } from './tools';

function toContent(r: ToolResult) {
  return {
    content: [{ type: 'text' as const, text: `${r.text}\n\n${JSON.stringify(r.data, null, 2)}` }],
  };
}

/** The read-only §8 fact tools, registered as thin wrappers over the core toolset. */
const FACT_TOOLS: Array<{ name: string; title: string; input: z.ZodRawShape }> = [
  {
    name: 'get_training_load',
    title: 'Get training load',
    input: { demo: z.boolean().optional() },
  },
  {
    name: 'get_recent_activities',
    title: 'Get recent activities',
    input: { demo: z.boolean().optional(), limit: z.number().int().min(1).max(50).optional() },
  },
  {
    name: 'get_pace_zones',
    title: 'Get HR and pace zones',
    input: { demo: z.boolean().optional() },
  },
  {
    name: 'get_next_workout_inputs',
    title: 'Get next-workout inputs',
    input: { demo: z.boolean().optional() },
  },
  { name: 'get_plan_context', title: 'Get plan context', input: { demo: z.boolean().optional() } },
];

/**
 * Read-only Stride MCP server. Every tool calls into @stride/core — the same
 * deterministic engine behind the CLI and API. The five fact tools are thin
 * adapters over the SHARED coach toolset, so MCP and the coach expose
 * byte-identical facts. The three action tools emit the safety disclaimer and
 * accept an optional free-text `note` (threaded into red-flag detection).
 */
export function buildServer(state: McpState): McpServer {
  const server = new McpServer({ name: 'stride', version: '0.1.0' });

  for (const t of FACT_TOOLS) {
    const def = findCoachTool(t.name);
    server.registerTool(
      t.name,
      {
        title: t.title,
        description: def?.description ?? t.title,
        inputSchema: t.input,
      },
      async (args) => toContent(await factTool(state, t.name, args as Record<string, unknown>)),
    );
  }

  server.registerTool(
    'analyze_workout',
    {
      title: 'Analyze a workout',
      description: 'Compute metrics for a workout (most recent by default) and explain it.',
      inputSchema: {
        demo: z.boolean().optional(),
        id: z.string().optional(),
        note: z.string().optional(),
      },
    },
    async ({ demo, id, note }) => toContent(await toolAnalyze(state, demo ?? false, id, note)),
  );

  server.registerTool(
    'suggest_next_workout',
    {
      title: 'Suggest next workout',
      description: 'Recommend the next workout based on current form, workload, and phase.',
      inputSchema: { demo: z.boolean().optional(), note: z.string().optional() },
    },
    async ({ demo, note }) => toContent(await toolNext(state, demo ?? false, note)),
  );

  server.registerTool(
    'generate_plan',
    {
      title: 'Generate a training plan',
      description: 'Generate a periodized, guardrail-validated training plan toward a goal race.',
      inputSchema: {
        demo: z.boolean().optional(),
        race: z.enum(['5k', '10k', 'half', 'marathon']).optional(),
        weeks: z.number().int().min(1).max(52).optional(),
        start: z.string().optional(),
        date: z.string().optional(),
        note: z.string().optional(),
      },
    },
    async ({ demo, race, weeks, start, date, note }) =>
      toContent(await toolPlan(state, { demo: demo ?? false, race, weeks, start, date, note })),
  );

  return server;
}
