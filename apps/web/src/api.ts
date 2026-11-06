import type { AppType } from '@stride/api';
import { hc, parseResponse } from 'hono/client';

// Consume the API through Hono's typed `hc` RPC client (GOAL §6): request and
// response types flow straight from the API's exported `AppType`, so there are no
// hand-maintained response interfaces to drift. `parseResponse` returns the typed
// success body and throws a structured error on any non-2xx (which React Query
// surfaces as `isError`). Demo mode is the default; the base defaults to `/api`,
// which Vite proxies to the API in dev (see vite.config.ts).
const base = import.meta.env.VITE_API_BASE ?? '/api';
const client = hc<AppType>(base);

export type RaceOption = '5k' | '10k' | 'half' | 'marathon';
/** MVP-026: a race plan, or a no-race "build my fitness" block (4–16 weeks). */
export type PlanKindOption = 'race' | 'build';

const demoQuery = (demo: boolean) => (demo ? { demo: 'true' } : {});
/** Only send a `note` when the athlete actually typed one. */
const noteQuery = (note?: string) => (note?.trim() ? { note: note.trim() } : {});

export const api = {
  health: () => parseResponse(client.health.$get()),
  profile: () => parseResponse(client.profile.$get()),
  pmc: (demo: boolean) => parseResponse(client.pmc.$get({ query: demoQuery(demo) })),
  activities: (demo: boolean) => parseResponse(client.activities.$get({ query: demoQuery(demo) })),
  analyze: (id: string, note?: string) =>
    parseResponse(client.analyze[':id'].$get({ param: { id }, query: noteQuery(note) })),
  next: (demo: boolean, note?: string) =>
    parseResponse(client.next.$get({ query: { ...demoQuery(demo), ...noteQuery(note) } })),
  plan: (body: {
    kind?: PlanKindOption;
    race?: RaceOption;
    weeks?: number;
    start?: string;
    date?: string;
    demo?: boolean;
    note?: string;
  }) => parseResponse(client.plan.$post({ json: body })),
};

/** HTTP status of a failed `parseResponse` call, if the server answered at all. */
export function errorStatus(err: unknown): number | undefined {
  const status = (err as { statusCode?: unknown } | null)?.statusCode;
  return typeof status === 'number' ? status : undefined;
}

/**
 * The human-readable reason for a failed API call. The API always answers errors
 * with `{ error, requestId }` (see apps/api), and `parseResponse` keeps that body
 * on `err.detail.data` — so show the API's own message when there is one, and
 * only fall back to the generic text when the server never answered (network
 * failure, API not running, proxy error page).
 */
export function errorMessage(err: unknown, fallback = 'API unavailable.'): string {
  const data = (err as { detail?: { data?: unknown } } | null)?.detail?.data;
  if (data && typeof data === 'object' && 'error' in data) {
    const message = (data as { error?: unknown }).error;
    if (typeof message === 'string' && message.trim()) return message;
  }
  return fallback;
}
