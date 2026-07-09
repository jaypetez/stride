import Anthropic from '@anthropic-ai/sdk';
import type { StrideConfig } from '../config';

export interface CompleteOptions {
  model: string;
  system: string;
  prompt: string;
  maxTokens?: number;
}

/**
 * Minimal LLM interface the coach depends on. This indirection keeps the coach
 * testable offline: when no API key is configured, `createCoachLLM` returns null
 * and the coach uses its deterministic fallbacks.
 */
export interface CoachLLM {
  complete(opts: CompleteOptions): Promise<string>;
}

/** Create a Claude-backed CoachLLM, or null if no API key is configured. */
export function createCoachLLM(config: StrideConfig): CoachLLM | null {
  if (!config.anthropicApiKey) return null;
  const client = new Anthropic({ apiKey: config.anthropicApiKey });
  return {
    async complete({ model, system, prompt, maxTokens = 1024 }: CompleteOptions): Promise<string> {
      const message = await client.messages.create({
        model,
        max_tokens: maxTokens,
        system,
        messages: [{ role: 'user', content: prompt }],
      });
      return message.content
        .filter((block): block is Anthropic.TextBlock => block.type === 'text')
        .map((block) => block.text)
        .join('\n')
        .trim();
    },
  };
}
