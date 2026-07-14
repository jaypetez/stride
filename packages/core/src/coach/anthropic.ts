import Anthropic from '@anthropic-ai/sdk';
import { betaZodTool } from '@anthropic-ai/sdk/helpers/beta/zod';
import { zodOutputFormat } from '@anthropic-ai/sdk/helpers/zod';
import type { z } from 'zod';
import type { StrideConfig } from '../config';
import { type LlmUsage, logLlmCall, toLlmUsage } from './llm-log';
import { COACH_TOOLS, type CoachDataProvider } from './tools';

// --- Result shapes returned across the CoachLLM seam ---

/** The result of a free-text completion. `refused` is true on a safety refusal. */
export interface CompleteResult {
  text: string;
  usage?: LlmUsage;
  requestId?: string;
  stopReason?: string | null;
  refused: boolean;
}

/** The result of a structured-output parse. `value` is absent on refusal/failure. */
export interface ParseResult<T> {
  value?: T;
  usage?: LlmUsage;
  requestId?: string;
  stopReason?: string | null;
  refused: boolean;
}

/** The result of the Haiku classification pass: a list of concern labels. */
export interface ClassifyResult {
  labels: string[];
  usage?: LlmUsage;
  requestId?: string;
  refused: boolean;
}

export interface CompleteOptions {
  model: string;
  system: string;
  prompt: string;
  maxTokens?: number;
  /** Logical path label for audit logging (e.g. 'analyze', 'next'). */
  path?: string;
}

export interface ParseOptions extends CompleteOptions {}
export interface ClassifyOptions extends CompleteOptions {}

export interface RunToolsOptions extends CompleteOptions {
  /** The data source the read-only tools read from. */
  provider: CoachDataProvider;
}

/**
 * The LLM seam the coach depends on. This indirection keeps the coach fully
 * testable offline: with no API key `createCoachLLM` returns null and the coach
 * uses its deterministic fallbacks; tests inject a fake implementing this
 * interface. Every method returns a rich result so the coach can honor refusals
 * and truncation (discard model output → deterministic fallback).
 */
export interface CoachLLM {
  /** Free-text completion (streamed under the hood for interactive paths). */
  complete(opts: CompleteOptions): Promise<CompleteResult>;
  /** Structured output constrained to a Zod schema (used for plan proposals). */
  parse<T>(opts: ParseOptions, schema: z.ZodType<T>): Promise<ParseResult<T>>;
  /** Optional cheap classification pass (Haiku). */
  classify?(opts: ClassifyOptions): Promise<ClassifyResult>;
  /** Optional tool-runner over the shared read-only toolset. */
  runTools?(opts: RunToolsOptions): Promise<CompleteResult>;
}

/**
 * Minimal structural view of the Anthropic client the implementation uses. The
 * real SDK client is assignable to this; tests inject a capturing fake to assert
 * per-path model tier and `cache_control` placement without any network.
 */
export interface AnthropicLike {
  messages: {
    create(params: any, options?: any): Promise<any>;
    parse(params: any, options?: any): Promise<any>;
    stream(params: any, options?: any): { finalMessage(): Promise<any> };
  };
  beta: {
    messages: {
      toolRunner(params: any, options?: any): any;
    };
  };
}

/** Frozen system prefix as a cacheable block (min 4096-token prefix on our models). */
function cachedSystem(system: string) {
  return [{ type: 'text' as const, text: system, cache_control: { type: 'ephemeral' as const } }];
}

function extractText(message: any): string {
  const content = Array.isArray(message?.content) ? message.content : [];
  return content
    .filter((b: any) => b?.type === 'text' && typeof b.text === 'string')
    .map((b: any) => b.text)
    .join('\n')
    .trim();
}

function requestIdOf(message: any): string | undefined {
  return message?._request_id ?? message?.request_id ?? undefined;
}

/** Best-effort parse of the classifier's reply into a list of labels. */
function parseLabels(text: string): string[] {
  const trimmed = text.trim();
  try {
    const parsed = JSON.parse(trimmed);
    if (Array.isArray(parsed)) return parsed.filter((x): x is string => typeof x === 'string');
  } catch {
    // fall through to a lenient comma/space split
  }
  return trimmed
    .replace(/[[\]"']/g, '')
    .split(/[,\n]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

/** Create a Claude-backed CoachLLM, or null if no API key is configured. */
export function createCoachLLM(config: StrideConfig, injected?: AnthropicLike): CoachLLM | null {
  if (!config.anthropicApiKey && !injected) return null;
  const client: AnthropicLike =
    injected ?? (new Anthropic({ apiKey: config.anthropicApiKey }) as unknown as AnthropicLike);

  // Per-tier adaptive thinking + effort. NEVER send temperature/budget_tokens
  // (they 400 on Opus 4.8 / Sonnet 5 / Haiku 4.5). Classify (Haiku) uses no
  // thinking and no effort (effort errors on Haiku 4.5).
  function tierFor(model: string): {
    thinking?: { type: 'adaptive' };
    effort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  } {
    if (model === config.models.classify) return {};
    if (model === config.models.plan) return { thinking: { type: 'adaptive' }, effort: 'high' };
    return { thinking: { type: 'adaptive' }, effort: 'medium' };
  }

  function baseParams(opts: CompleteOptions, extraOutputConfig?: Record<string, unknown>) {
    const tier = tierFor(opts.model);
    const outputConfig = {
      ...(tier.effort ? { effort: tier.effort } : {}),
      ...(extraOutputConfig ?? {}),
    };
    return {
      model: opts.model,
      max_tokens: opts.maxTokens ?? 1024,
      system: cachedSystem(opts.system),
      messages: [{ role: 'user' as const, content: opts.prompt }],
      ...(tier.thinking ? { thinking: tier.thinking } : {}),
      ...(Object.keys(outputConfig).length > 0 ? { output_config: outputConfig } : {}),
    };
  }

  return {
    async complete(opts: CompleteOptions): Promise<CompleteResult> {
      // Interactive paths stream and take the final message (avoids HTTP timeouts).
      const stream = client.messages.stream(baseParams(opts));
      const message = await stream.finalMessage();
      const refused = message?.stop_reason === 'refusal';
      const result: CompleteResult = {
        text: refused ? '' : extractText(message),
        usage: toLlmUsage(message?.usage),
        requestId: requestIdOf(message),
        stopReason: message?.stop_reason ?? null,
        refused,
      };
      logLlmCall({
        path: opts.path ?? 'complete',
        model: opts.model,
        requestId: result.requestId,
        usage: result.usage,
        stopReason: result.stopReason,
        refused: result.refused,
      });
      return result;
    },

    async parse<T>(opts: ParseOptions, schema: z.ZodType<T>): Promise<ParseResult<T>> {
      const params = baseParams(opts, { format: zodOutputFormat(schema) });
      const message = await client.messages.parse(params);
      const refused = message?.stop_reason === 'refusal';
      const value = refused ? undefined : ((message?.parsed_output ?? undefined) as T | undefined);
      const result: ParseResult<T> = {
        value,
        usage: toLlmUsage(message?.usage),
        requestId: requestIdOf(message),
        stopReason: message?.stop_reason ?? null,
        refused,
      };
      logLlmCall({
        path: opts.path ?? 'plan-proposal',
        model: opts.model,
        requestId: result.requestId,
        usage: result.usage,
        stopReason: result.stopReason,
        refused: result.refused,
      });
      return result;
    },

    async classify(opts: ClassifyOptions): Promise<ClassifyResult> {
      // Haiku, no thinking/effort. Plain create (short, cheap).
      const message = await client.messages.create(baseParams({ maxTokens: 256, ...opts }));
      const refused = message?.stop_reason === 'refusal';
      const result: ClassifyResult = {
        labels: refused ? [] : parseLabels(extractText(message)),
        usage: toLlmUsage(message?.usage),
        requestId: requestIdOf(message),
        refused,
      };
      logLlmCall({
        path: 'classify',
        model: opts.model,
        requestId: result.requestId,
        usage: result.usage,
        stopReason: message?.stop_reason ?? null,
        refused: result.refused,
      });
      return result;
    },

    async runTools(opts: RunToolsOptions): Promise<CompleteResult> {
      // A tool runner over the SHARED read-only toolset (same facts MCP exposes).
      // Strict, frozen-order tools with inline examples (GOAL §8). Live-only —
      // exercised via the mock seam in tests; asserting real cache_read>0 and
      // real streaming requires a network and is documented, not run.
      const tools = COACH_TOOLS.map((tool) =>
        betaZodTool({
          name: tool.name,
          description:
            tool.examples.length > 0
              ? `${tool.description}\nExamples: ${tool.examples.map((e) => JSON.stringify(e)).join(' ')}`
              : tool.description,
          inputSchema: tool.inputSchema,
          run: async (args) => JSON.stringify((await tool.run(opts.provider, args)).data),
        }),
      );
      const tier = tierFor(opts.model);
      const message = await client.beta.messages.toolRunner({
        model: opts.model,
        max_tokens: opts.maxTokens ?? 1024,
        system: cachedSystem(opts.system),
        messages: [{ role: 'user' as const, content: opts.prompt }],
        tools,
        ...(tier.thinking ? { thinking: tier.thinking } : {}),
        ...(tier.effort ? { output_config: { effort: tier.effort } } : {}),
      });
      const refused = message?.stop_reason === 'refusal';
      const result: CompleteResult = {
        text: refused ? '' : extractText(message),
        usage: toLlmUsage(message?.usage),
        requestId: requestIdOf(message),
        stopReason: message?.stop_reason ?? null,
        refused,
      };
      logLlmCall({
        path: 'tools',
        model: opts.model,
        requestId: result.requestId,
        usage: result.usage,
        stopReason: result.stopReason,
        refused: result.refused,
      });
      return result;
    },
  };
}
