/**
 * Runtime configuration, resolved from environment variables. Stride is
 * local-first: every credential is the user's own. Apps call dotenv then
 * `loadConfig(process.env)`; core never reads the environment implicitly.
 */

import os from 'node:os';
import path from 'node:path';
import { z } from 'zod';

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
   * Allowed browser origin for the HTTP API's CORS policy. The API serves the
   * user's own Strava data, so it must NOT be world-readable (`*`); it is locked
   * to the web UI's origin. Sourced from STRIDE_WEB_ORIGIN.
   */
  webOrigin: string;
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
export const DEFAULT_WEB_ORIGIN = 'http://localhost:5173';

type Env = Record<string, string | undefined>;

/** Thrown by {@link loadConfig} when an environment variable is malformed. */
export class ConfigError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigError';
  }
}

/** Treat an empty env var as unset (e.g. `STRIDE_API_PORT=` in a .env). */
const optionalVar = <T extends z.ZodType>(schema: T) =>
  z.preprocess((v) => (v === '' ? undefined : v), schema.optional());

/**
 * The env vars whose values are parsed (not just passed through). Validated up
 * front so a typo fails fast with a clear message instead of surfacing later as
 * a NaN port or an "Invalid Date" clock.
 */
const ValidatedEnv = z.object({
  STRIDE_API_PORT: optionalVar(
    z
      .string()
      .regex(/^\d+$/, 'expected an integer port between 1 and 65535')
      .transform(Number)
      .pipe(z.number().int().min(1).max(65535, 'expected an integer port between 1 and 65535')),
  ),
  STRIDE_NOW: optionalVar(
    z
      .string()
      .regex(/^\d{4}-\d{2}-\d{2}/, 'expected an ISO-8601 date/time, e.g. 2026-07-09T00:00:00Z')
      .refine((v) => Number.isFinite(Date.parse(v)), 'not a valid ISO-8601 date/time'),
  ),
});

function validateEnv(env: Env): z.infer<typeof ValidatedEnv> {
  const parsed = ValidatedEnv.safeParse(env);
  if (parsed.success) return parsed.data;
  const detail = parsed.error.issues
    .map((i) => {
      const key = String(i.path[0] ?? 'env');
      return `${key}=${JSON.stringify(env[key])}: ${i.message}`;
    })
    .join('; ');
  throw new ConfigError(`Invalid configuration — ${detail}`);
}

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

/**
 * Resolve the runtime config from `env`. Throws {@link ConfigError} on a
 * malformed STRIDE_API_PORT or STRIDE_NOW so every surface fails fast.
 */
export function loadConfig(env: Env = {}): StrideConfig {
  const valid = validateEnv(env);
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
    apiPort: valid.STRIDE_API_PORT ?? 8720,
    webOrigin: env.STRIDE_WEB_ORIGIN ?? DEFAULT_WEB_ORIGIN,
    now: valid.STRIDE_NOW,
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
