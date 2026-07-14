import { createLogger } from '../log';

const log = createLogger('coach.llm');

/** Token usage as reported by the Anthropic API (`response.usage`). */
export interface LlmUsage {
  inputTokens?: number;
  outputTokens?: number;
  cacheReadInputTokens?: number;
  cacheCreationInputTokens?: number;
}

/**
 * One auditable LLM call record (GOAL §8 "Auditability"). We log the model, the
 * logical path, the API `request_id`, token usage (incl. cache hits so a silent
 * caching regression is visible), the `stop_reason`, and whether the request was
 * refused/truncated. Everything goes to stderr via the shared logger, so it
 * never contaminates the MCP stdout channel or CLI JSON output.
 */
export interface LlmAudit {
  /** Logical path: 'analyze' | 'next' | 'plan-proposal' | 'plan-summary' | 'classify' | 'tools'. */
  path: string;
  model: string;
  requestId?: string;
  usage?: LlmUsage;
  stopReason?: string | null;
  refused: boolean;
}

/** Normalize the SDK's snake_case usage object into {@link LlmUsage}. */
export function toLlmUsage(usage: unknown): LlmUsage | undefined {
  if (!usage || typeof usage !== 'object') return undefined;
  const u = usage as Record<string, unknown>;
  const num = (v: unknown): number | undefined => (typeof v === 'number' ? v : undefined);
  return {
    inputTokens: num(u.input_tokens),
    outputTokens: num(u.output_tokens),
    cacheReadInputTokens: num(u.cache_read_input_tokens),
    cacheCreationInputTokens: num(u.cache_creation_input_tokens),
  };
}

/** Record an LLM call for audit. Refusals/truncations log at `warn`, else `info`. */
export function logLlmCall(audit: LlmAudit): void {
  const fields = {
    model: audit.model,
    requestId: audit.requestId,
    stopReason: audit.stopReason,
    refused: audit.refused,
    ...audit.usage,
  };
  if (audit.refused || audit.stopReason === 'max_tokens') {
    log.warn(`llm ${audit.path} discarded (refused/truncated)`, fields);
  } else {
    log.info(`llm ${audit.path}`, fields);
  }
}
