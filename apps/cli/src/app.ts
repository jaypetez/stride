import {
  type CoachDeps,
  type CoachLLM,
  createCoachLLM,
  LocalStore,
  loadConfig,
  resolveNowIso,
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

/** Reference clock (ISO). Fixed via STRIDE_NOW when set, else real now. */
export function todayIso(config: StrideConfig): string {
  return resolveNowIso(config);
}

export function todayKey(config: StrideConfig): string {
  return resolveNowIso(config).slice(0, 10);
}

/** Coach dependencies, including a fixed clock when STRIDE_NOW is set. */
export function coachDeps(app: App): CoachDeps {
  return {
    llm: app.llm,
    models: app.config.models,
    nowIso: app.config.now ? () => resolveNowIso(app.config) : undefined,
  };
}
