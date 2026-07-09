import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import type { McpState } from './state';
import {
  type ToolResult,
  toolAnalyze,
  toolNext,
  toolPlan,
  toolRecentActivities,
  toolTrainingLoad,
  toolZones,
} from './tools';

function toContent(r: ToolResult) {
  return {
    content: [{ type: 'text' as const, text: `${r.text}\n\n${JSON.stringify(r.data, null, 2)}` }],
  };
}

/**
 * Read-only Stride MCP server. Every tool calls into @stride/core — the same
 * deterministic engine behind the CLI and API — so an MCP client (e.g. Claude)
 * gets analysis-ready facts, never raw data it must recompute.
 */
export function buildServer(state: McpState): McpServer {
  const server = new McpServer({ name: 'stride', version: '0.1.0' });

  server.registerTool(
    'get_training_load',
    {
      title: 'Get training load',
      description:
        'Current fitness (CTL), fatigue (ATL), form (TSB), ACWR guardrail, and CTL ramp rate.',
      inputSchema: { demo: z.boolean().optional() },
    },
    async ({ demo }) => toContent(await toolTrainingLoad(state, demo ?? false)),
  );

  server.registerTool(
    'get_recent_activities',
    {
      title: 'Get recent activities',
      description: 'Recent activity summaries (date, name, distance, time). Read-only.',
      inputSchema: {
        demo: z.boolean().optional(),
        limit: z.number().int().min(1).max(50).optional(),
      },
    },
    async ({ demo, limit }) =>
      toContent(await toolRecentActivities(state, demo ?? false, limit ?? 10)),
  );

  server.registerTool(
    'get_pace_zones',
    {
      title: 'Get HR and pace zones',
      description: "The athlete's heart-rate and pace training zones, derived from their anchors.",
      inputSchema: { demo: z.boolean().optional() },
    },
    async ({ demo }) => toContent(await toolZones(state, demo ?? false)),
  );

  server.registerTool(
    'analyze_workout',
    {
      title: 'Analyze a workout',
      description: 'Compute metrics for a workout (most recent by default) and explain it.',
      inputSchema: { demo: z.boolean().optional(), id: z.string().optional() },
    },
    async ({ demo, id }) => toContent(await toolAnalyze(state, demo ?? false, id)),
  );

  server.registerTool(
    'suggest_next_workout',
    {
      title: 'Suggest next workout',
      description: 'Recommend the next workout based on current form, workload, and phase.',
      inputSchema: { demo: z.boolean().optional() },
    },
    async ({ demo }) => toContent(await toolNext(state, demo ?? false)),
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
      },
    },
    async ({ demo, race, weeks, start, date }) =>
      toContent(await toolPlan(state, { demo: demo ?? false, race, weeks, start, date })),
  );

  return server;
}
