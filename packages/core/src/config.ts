/**
 * Runtime configuration, resolved from environment variables. Stride is
 * local-first: every credential is the user's own. Apps call dotenv then
 * `loadConfig(process.env)`; core never reads the environment implicitly.
 */

import os from 'node:os';
import path from 'node:path';

export interface StravaConfig {
  clientId?: string;
  clientSecret?: string;
  redirectUri: string;
  scopes: string;
  apiBase: string;
}

export interface ModelConfig {
  /** Plan generation & complex repair. */
  plan: string;
  /** Conversational analysis / check-ins. */
  chat: string;
  /** Cheap classification (intent, red-flag detection). */
  classify: string;
}

export interface StrideConfig {
  strava: StravaConfig;
  anthropicApiKey?: string;
  models: ModelConfig;
  /** Directory for the local store and OAuth token file. */
  dataDir: string;
  apiPort: number;
  /**
   * Optional fixed "now" (ISO-8601) sourced from STRIDE_NOW. When set, the apps
   * thread it into the coach as the reference clock so demo `next`/`plan`
   * outputs are byte-reproducible (essential for agentic diffing).
   */
  now?: string;
}

export const DEFAULT_MODELS: ModelConfig = {
  plan: 'claude-opus-4-8',
  chat: 'claude-sonnet-5',
  classify: 'claude-haiku-4-5',
};

export const DEFAULT_STRAVA_API_BASE = 'https://www.strava.com/api/v3';
export const DEFAULT_REDIRECT_URI = 'http://localhost:8721/callback';
export const DEFAULT_SCOPES = 'read,activity:read_all,profile:read_all';

type Env = Record<string, string | undefined>;

/**
 * Expand a leading `~`/`~/` and `$HOME` / `${HOME}` / `%USERPROFILE%` (win32)
 * references in a path to the current user's home directory. Applied to
 * `STRIDE_DATA_DIR` so e.g. `~/.stride` (advertised in `.env.example`) resolves
 * to a real home path instead of a literal `~` directory under the cwd.
 */
export function expandHome(p: string): string {
  if (!p) return p;
  const home = os.homedir();
  let out = p;
  if (out === '~' || out === '~/' || out === '~\\') {
    out = home;
  } else if (out.startsWith('~/') || out.startsWith('~\\')) {
    out = path.join(home, out.slice(2));
  }
  out = out
    .replace(/\$\{HOME\}/g, home)
    .replace(/\$HOME(?![A-Za-z0-9_])/g, home)
    .replace(/%USERPROFILE%/gi, home);
  return out;
}

export function loadConfig(env: Env = {}): StrideConfig {
  return {
    strava: {
      clientId: env.STRAVA_CLIENT_ID,
      clientSecret: env.STRAVA_CLIENT_SECRET,
      redirectUri: env.STRAVA_REDIRECT_URI ?? DEFAULT_REDIRECT_URI,
      scopes: env.STRAVA_SCOPES ?? DEFAULT_SCOPES,
      apiBase: env.STRAVA_API_BASE ?? DEFAULT_STRAVA_API_BASE,
    },
    anthropicApiKey: env.ANTHROPIC_API_KEY,
    models: {
      plan: env.STRIDE_MODEL_PLAN ?? DEFAULT_MODELS.plan,
      chat: env.STRIDE_MODEL_CHAT ?? DEFAULT_MODELS.chat,
      classify: env.STRIDE_MODEL_CLASSIFY ?? DEFAULT_MODELS.classify,
    },
    dataDir: expandHome(env.STRIDE_DATA_DIR ?? '.stride'),
    apiPort: env.STRIDE_API_PORT ? Number(env.STRIDE_API_PORT) : 8720,
    now: env.STRIDE_NOW,
  };
}

/** Resolve the reference clock (ISO): the fixed STRIDE_NOW if set, else real now. */
export function resolveNowIso(config: StrideConfig): string {
  return config.now ?? new Date().toISOString();
}

export function assertStravaConfigured(config: StrideConfig): void {
  if (!config.strava.clientId || !config.strava.clientSecret) {
    throw new Error(
      'Strava is not configured. Set STRAVA_CLIENT_ID and STRAVA_CLIENT_SECRET in your .env ' +
        '(create an app at https://www.strava.com/settings/api).',
    );
  }
}

export function assertAnthropicConfigured(config: StrideConfig): void {
  if (!config.anthropicApiKey) {
    throw new Error(
      'Anthropic is not configured. Set ANTHROPIC_API_KEY in your .env ' +
        '(get a key at https://console.anthropic.com/).',
    );
  }
}
