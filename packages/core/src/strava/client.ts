import type { Activity, ActivityStreams } from '@stride/schemas';
import type { StravaConfig } from '../config';
import { createLogger } from '../log';
import { mapActivity, mapStreams } from './mapper';
import { type FetchLike, refreshTokens } from './oauth';
import type { RateLimitStatus, StravaTokens } from './types';

const log = createLogger('strava');

export class StravaRateLimitError extends Error {
  constructor(public readonly status: RateLimitStatus | undefined) {
    super('Strava rate limit exceeded (HTTP 429). Wait for the window to reset.');
    this.name = 'StravaRateLimitError';
  }
}

export class StravaApiError extends Error {
  constructor(
    public readonly statusCode: number,
    message: string,
  ) {
    super(message);
    this.name = 'StravaApiError';
  }
}

export interface StravaClientOptions {
  config: StravaConfig;
  tokens: StravaTokens;
  fetchImpl?: FetchLike;
  /** Called whenever tokens are refreshed so the caller can persist them. */
  onTokensRefreshed?: (tokens: StravaTokens) => void | Promise<void>;
  /** Clock injection for tests. Returns epoch seconds. */
  now?: () => number;
}

const DEFAULT_STREAM_KEYS = [
  'time',
  'distance',
  'altitude',
  'velocity_smooth',
  'heartrate',
  'cadence',
  'grade_smooth',
];

/** Rate-limit-aware, token-refreshing Strava API v3 client. */
export class StravaClient {
  private tokens: StravaTokens;
  private readonly config: StravaConfig;
  private readonly fetchImpl: FetchLike;
  private readonly onTokensRefreshed?: (tokens: StravaTokens) => void | Promise<void>;
  private readonly now: () => number;
  private rateLimit: RateLimitStatus | undefined;

  constructor(opts: StravaClientOptions) {
    this.config = opts.config;
    this.tokens = opts.tokens;
    this.fetchImpl = opts.fetchImpl ?? fetch;
    this.onTokensRefreshed = opts.onTokensRefreshed;
    this.now = opts.now ?? (() => Math.floor(Date.now() / 1000));
  }

  getTokens(): StravaTokens {
    return this.tokens;
  }

  getRateLimitStatus(): RateLimitStatus | undefined {
    return this.rateLimit;
  }

  private async ensureFreshToken(): Promise<void> {
    if (this.now() < this.tokens.expiresAt - 60) return;
    const refreshed = await refreshTokens(this.config, this.tokens.refreshToken, this.fetchImpl);
    this.tokens = { ...refreshed, athleteId: refreshed.athleteId ?? this.tokens.athleteId };
    await this.onTokensRefreshed?.(this.tokens);
  }

  private updateRateLimit(headers: Headers): void {
    const parse = (v: string | null): [number, number] | undefined => {
      if (!v) return undefined;
      const [a, b] = v.split(',').map((n) => Number(n.trim()));
      return [a ?? 0, b ?? 0];
    };
    const limit = parse(headers.get('x-ratelimit-limit'));
    const usage = parse(headers.get('x-ratelimit-usage'));
    const readLimit = parse(headers.get('x-readratelimit-limit'));
    const readUsage = parse(headers.get('x-readratelimit-usage'));
    if (limit || usage) {
      this.rateLimit = {
        shortLimit: limit?.[0] ?? 0,
        dailyLimit: limit?.[1] ?? 0,
        shortUsage: usage?.[0] ?? 0,
        dailyUsage: usage?.[1] ?? 0,
        readShortLimit: readLimit?.[0],
        readDailyLimit: readLimit?.[1],
        readShortUsage: readUsage?.[0],
        readDailyUsage: readUsage?.[1],
      };
    }
  }

  private async request<T>(path: string, query?: Record<string, string | number>): Promise<T> {
    await this.ensureFreshToken();
    const qs = query
      ? `?${new URLSearchParams(
          Object.fromEntries(Object.entries(query).map(([k, v]) => [k, String(v)])),
        )}`
      : '';
    const res = await this.fetchImpl(`${this.config.apiBase}${path}${qs}`, {
      headers: { Authorization: `Bearer ${this.tokens.accessToken}` },
    });
    this.updateRateLimit(res.headers);
    log.debug('strava request', { path, status: res.status, usage: this.rateLimit?.shortUsage });
    if (res.status === 429) {
      log.warn('strava rate limit hit (429)', { path, status: this.rateLimit });
      throw new StravaRateLimitError(this.rateLimit);
    }
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new StravaApiError(res.status, `Strava API ${path} failed (${res.status}): ${text}`);
    }
    return (await res.json()) as T;
  }

  async getAthlete(): Promise<Record<string, any>> {
    return this.request('/athlete');
  }

  async getAthleteZones(): Promise<Record<string, any>> {
    return this.request('/athlete/zones');
  }

  /** One page of summary activities, newest first. */
  async getActivitiesPage(
    opts: {
      page?: number;
      perPage?: number;
      before?: number;
      after?: number;
      fetchedAt?: string;
    } = {},
  ): Promise<Activity[]> {
    const query: Record<string, string | number> = {
      page: opts.page ?? 1,
      per_page: opts.perPage ?? 200,
    };
    if (opts.before) query.before = opts.before;
    if (opts.after) query.after = opts.after;
    const raw = await this.request<Record<string, any>[]>('/athlete/activities', query);
    return raw.map((a) => mapActivity(a, opts.fetchedAt));
  }

  /** Page through all activities (bounded by maxPages). */
  async listAllActivities(
    opts: { after?: number; maxPages?: number; fetchedAt?: string } = {},
  ): Promise<Activity[]> {
    const perPage = 200;
    const maxPages = opts.maxPages ?? 20;
    const all: Activity[] = [];
    for (let page = 1; page <= maxPages; page++) {
      const batch = await this.getActivitiesPage({
        page,
        perPage,
        after: opts.after,
        fetchedAt: opts.fetchedAt,
      });
      all.push(...batch);
      if (batch.length < perPage) break;
    }
    return all;
  }

  async getActivity(id: string, fetchedAt?: string): Promise<Activity> {
    const raw = await this.request<Record<string, any>>(`/activities/${id}`);
    return mapActivity(raw, fetchedAt);
  }

  async getActivityStreams(
    id: string,
    keys: string[] = DEFAULT_STREAM_KEYS,
  ): Promise<ActivityStreams> {
    const raw = await this.request<Record<string, any>>(`/activities/${id}/streams`, {
      keys: keys.join(','),
      key_by_type: 'true',
      resolution: 'high',
    });
    return mapStreams(raw);
  }
}
