import {
  type CoachLLM,
  createCoachLLM,
  LocalStore,
  loadConfig,
  type StrideConfig,
} from '@stride/core';
import { type Activity, AthleteProfile } from '@stride/schemas';
import { config as loadDotenv } from 'dotenv';

export interface App {
  config: StrideConfig;
  store: LocalStore;
  llm: CoachLLM | null;
}

/** Load env, config, the local store, and (if configured) the Claude client. */
export function loadApp(): App {
  loadDotenv({ quiet: true });
  const config = loadConfig(process.env as Record<string, string | undefined>);
  const store = new LocalStore(config.dataDir);
  const llm = createCoachLLM(config);
  return { config, store, llm };
}

export async function getProfile(store: LocalStore): Promise<AthleteProfile> {
  return (await store.loadProfile()) ?? AthleteProfile.parse({});
}

export function mostRecent(activities: Activity[]): Activity | undefined {
  return [...activities].sort((a, b) => b.startDate.localeCompare(a.startDate))[0];
}

export function todayIso(): string {
  return new Date().toISOString();
}

export function todayKey(): string {
  return new Date().toISOString().slice(0, 10);
}
