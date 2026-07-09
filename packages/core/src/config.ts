/**
 * Runtime configuration, resolved from environment variables. Stride is
 * local-first: every credential is the user's own. Apps call dotenv then
 * `loadConfig(process.env)`; core never reads the environment implicitly.
 */

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
    dataDir: env.STRIDE_DATA_DIR ?? '.stride',
    apiPort: env.STRIDE_API_PORT ? Number(env.STRIDE_API_PORT) : 8720,
  };
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
