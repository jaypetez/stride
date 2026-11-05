import {
  type AppState,
  type CoachDeps,
  demoNowIso,
  type LocalStore,
  loadAppState,
  resolveNowIso,
  type StrideConfig,
} from '@stride/core';
import { type Activity, AthleteProfile } from '@stride/schemas';

export type App = AppState;

/**
 * Load env, the VALIDATED config (a bad STRIDE_NOW / STRIDE_API_PORT throws a
 * clear `ConfigError`), the local store, and (if configured) the Claude client.
 */
export function loadApp(): App {
  return loadAppState();
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

/**
 * Reference clock for a command: in demo mode the demo "today" (anchored to the
 * bundled history unless STRIDE_NOW/--now pins it), else {@link todayIso}.
 */
export function refNowIso(config: StrideConfig, demo: boolean | undefined): string {
  return demo ? demoNowIso(config.now) : todayIso(config);
}

/** Coach dependencies, including a fixed clock when STRIDE_NOW is set. */
export function coachDeps(app: App): CoachDeps {
  return {
    llm: app.llm,
    models: app.config.models,
    nowIso: app.config.now ? () => resolveNowIso(app.config) : undefined,
  };
}
