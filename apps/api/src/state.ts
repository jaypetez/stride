import {
  type CoachLLM,
  createCoachLLM,
  LocalStore,
  loadConfig,
  type StrideConfig,
} from '@stride/core';
import { config as loadDotenv } from 'dotenv';

export interface ApiState {
  config: StrideConfig;
  store: LocalStore;
  llm: CoachLLM | null;
}

export function loadApiState(): ApiState {
  loadDotenv({ quiet: true });
  const config = loadConfig(process.env as Record<string, string | undefined>);
  const store = new LocalStore(config.dataDir);
  const llm = createCoachLLM(config);
  return { config, store, llm };
}
