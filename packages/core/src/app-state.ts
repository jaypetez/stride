import { config as loadDotenv } from 'dotenv';
import { type CoachLLM, createCoachLLM } from './coach/anthropic';
import { loadConfig, type StrideConfig } from './config';
import { LocalStore } from './store/index';

/** What every app surface (API, MCP, CLI) needs at startup. */
export interface AppState {
  config: StrideConfig;
  store: LocalStore;
  llm: CoachLLM | null;
}

/**
 * Shared startup for the app surfaces: load `.env` (quietly — stdout is the MCP
 * protocol channel), resolve and VALIDATE the config (throws `ConfigError` on a
 * bad STRIDE_API_PORT / STRIDE_NOW), open the local store and, when a key is
 * configured, the Claude client.
 */
export function loadAppState(env: Record<string, string | undefined> = process.env): AppState {
  loadDotenv({ quiet: true });
  const config = loadConfig(env);
  const store = new LocalStore(config.dataDir);
  const llm = createCoachLLM(config);
  return { config, store, llm };
}
