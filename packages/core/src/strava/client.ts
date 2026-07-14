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

export type SleepFn = (ms: number) => Promise<void>;

export interface StravaClientOptions {
  config: StravaConfig;
  tokens: StravaTokens;
  fetchImpl?: FetchLike;
  /** Called whenever tokens are refreshed so the caller can persist them. */
  onTokensRefreshed?: (tokens: StravaTokens) => void | Promise<void>;
  /** Clock injection for tests. Returns epoch seconds. */
  now?: () => number;
  /** Injectable delay (tests pass a no-op / fake). Default real setTimeout. */
  sleep?: SleepFn;
  /** Max 429 retries before degrading (throwing StravaRateLimitError). */
  maxRetries?: number;
  /** Never sleep longer than this while throttling/retrying (default ~16 min). */
  maxWaitMs?: number;
  /** Throttle proactively when usage is within this many calls of a sublimit. */
  throttleMargin?: number;
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

/** Strava rate-limit windows reset at :00/:15/:30/:45 UTC (900-second periods). */
const WINDOW_SEC = 15 * 60;
const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_MAX_WAIT_MS = 16 * 60 * 1000;
const DEFAULT_THROTTLE_MARGIN = 2;

/** Result of paging activities: partial results plus why paging stopped. */
export interface ListActivitiesResult {
  activities: Activity[];
  /** True if paging stopped because a rate limit was hit (partial results). */
  rateLimited: boolean;
  /** True if a short page proved we reached the end of available history. */
  reachedEnd: boolean;
}

/** Rate-limit-aware, token-refreshing Strava API v3 client. */
export class StravaClient {
  private tokens: StravaTokens;
  private readonly config: StravaConfig;
  private readonly fetchImpl: FetchLike;
  private readonly onTokensRefreshed?: (tokens: StravaTokens) => void | Promise<void>;
  private readonly now: () => number;
  private readonly sleep: SleepFn;
  private readonly maxRetries: number;
  private readonly maxWaitMs: number;
  private readonly throttleMargin: number;
  private rateLimit: RateLimitStatus | undefined;

  constructor(opts: StravaClientOptions) {
    this.config = opts.config;
    this.tokens = opts.tokens;
    this.fetchImpl = opts.fetchImpl ?? fetch;
    this.onTokensRefreshed = opts.onTokensRefreshed;
    this.now = opts.now ?? (() => Math.floor(Date.now() / 1000));
    this.sleep = opts.sleep ?? ((ms) => new Promise((r) => setTimeout(r, ms)));
    this.maxRetries = opts.maxRetries ?? DEFAULT_MAX_RETRIES;
    this.maxWaitMs = opts.maxWaitMs ?? DEFAULT_MAX_WAIT_MS;
    this.throttleMargin = opts.throttleMargin ?? DEFAULT_THROTTLE_MARGIN;
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

  /** Milliseconds until the next 15-min window boundary (:00/:15/:30/:45 UTC). */
  private msToNextWindow(): number {
    const nowSec = this.now();
    const into = ((nowSec % WINDOW_SEC) + WINDOW_SEC) % WINDOW_SEC;
    return (WINDOW_SEC - into) * 1000;
  }

  /** True when we're within `throttleMargin` of the daily read/overall cap. */
  private isDailyExhausted(rl: RateLimitStatus): boolean {
    const m = this.throttleMargin;
    if (rl.dailyLimit > 0 && rl.dailyUsage >= rl.dailyLimit - m) return true;
    if (
      rl.readDailyLimit !== undefined &&
      rl.readDailyUsage !== undefined &&
      rl.readDailyLimit > 0 &&
      rl.readDailyUsage >= rl.readDailyLimit - m
    ) {
      return true;
    }
    return false;
  }

  /** True when we're within `throttleMargin` of the 15-min read/overall cap. */
  private isShortNearLimit(rl: RateLimitStatus): boolean {
    const m = this.throttleMargin;
    if (rl.shortLimit > 0 && rl.shortUsage >= rl.shortLimit - m) return true;
    if (
      rl.readShortLimit !== undefined &&
      rl.readShortUsage !== undefined &&
      rl.readShortLimit > 0 &&
      rl.readShortUsage >= rl.readShortLimit - m
    ) {
      return true;
    }
    return false;
  }

  /**
   * Proactively wait out (or degrade past) a rate limit before spending a call.
   * If the daily sublimit is exhausted, or the wait to the next window would
   * exceed `maxWaitMs`, throw so callers can degrade gracefully; otherwise sleep
   * to the next window boundary.
   */
  private async throttleIfNeeded(): Promise<void> {
    const rl = this.rateLimit;
    if (!rl) return; // No headers seen yet (first request) — nothing to reason about.
    if (this.isDailyExhausted(rl)) {
      log.warn('strava daily rate limit reached; degrading', { status: rl });
      throw new StravaRateLimitError(rl);
    }
    if (!this.isShortNearLimit(rl)) return;
    const waitMs = this.msToNextWindow();
    if (waitMs > this.maxWaitMs) {
      log.warn('strava short rate limit near and wait exceeds budget; degrading', { waitMs });
      throw new StravaRateLimitError(rl);
    }
    log.warn('strava proactive throttle; sleeping to next window', {
      waitMs,
      shortUsage: rl.shortUsage,
    });
    await this.sleep(waitMs);
    // The 15-min window has rolled over; reflect the reset locally so the stale
    // snapshot doesn't immediately re-trip the throttle on the next call.
    this.rateLimit = {
      ...rl,
      shortUsage: 0,
      readShortUsage: rl.readShortUsage !== undefined ? 0 : rl.readShortUsage,
    };
  }

  /** How long to wait before retrying a 429, from Retry-After or the window. */
  private retryWaitMs(headers: Headers): number {
    const ra = headers.get('retry-after');
    if (ra) {
      const secs = Number(ra);
      if (Number.isFinite(secs) && secs >= 0) return secs * 1000;
      const when = Date.parse(ra); // HTTP-date form
      if (Number.isFinite(when)) return Math.max(0, when - this.now() * 1000);
    }
    return this.msToNextWindow();
  }

  private async request<T>(path: string, query?: Record<string, string | number>): Promise<T> {
    await this.ensureFreshToken();
    await this.throttleIfNeeded();
    const qs = query
      ? `?${new URLSearchParams(
          Object.fromEntries(Object.entries(query).map(([k, v]) => [k, String(v)])),
        )}`
      : '';
    const url = `${this.config.apiBase}${path}${qs}`;

    let attempt = 0;
    while (true) {
      const res = await this.fetchImpl(url, {
        headers: { Authorization: `Bearer ${this.tokens.accessToken}` },
      });
      this.updateRateLimit(res.headers);
      log.debug('strava request', { path, status: res.status, usage: this.rateLimit?.shortUsage });

      if (res.status === 429) {
        const waitMs = this.retryWaitMs(res.headers);
        attempt++;
        if (attempt > this.maxRetries || waitMs > this.maxWaitMs) {
          log.warn('strava rate limit hit (429); giving up after retries', {
            path,
            attempt,
            status: this.rateLimit,
          });
          throw new StravaRateLimitError(this.rateLimit);
        }
        log.warn('strava 429; backing off and retrying', { path, attempt, waitMs });
        await this.sleep(waitMs);
        continue;
      }

      if (!res.ok) {
        const text = await res.text().catch(() => '');
        throw new StravaApiError(res.status, `Strava API ${path} failed (${res.status}): ${text}`);
      }
      return (await res.json()) as T;
    }
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

  /**
   * Page through activities (bounded by `maxPages`). `after`/`before` bound the
   * time window (backfill pages an ever-older `before` cursor; incremental uses
   * `after`). Degrades on a rate limit: catch it, stop, and return the pages
   * gathered so far so the caller can merge the partial result and resume later.
   */
  async listAllActivities(
    opts: { after?: number; before?: number; maxPages?: number; fetchedAt?: string } = {},
  ): Promise<ListActivitiesResult> {
    const perPage = 200;
    const maxPages = opts.maxPages ?? 20;
    const all: Activity[] = [];
    let reachedEnd = false;
    try {
      for (let page = 1; page <= maxPages; page++) {
        const batch = await this.getActivitiesPage({
          page,
          perPage,
          after: opts.after,
          before: opts.before,
          fetchedAt: opts.fetchedAt,
        });
        all.push(...batch);
        if (batch.length < perPage) {
          reachedEnd = true;
          break;
        }
      }
    } catch (err) {
      if (err instanceof StravaRateLimitError) {
        log.warn('strava rate limit during paging; returning partial results', {
          fetched: all.length,
        });
        return { activities: all, rateLimited: true, reachedEnd: false };
      }
      throw err;
    }
    return { activities: all, rateLimited: false, reachedEnd };
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
