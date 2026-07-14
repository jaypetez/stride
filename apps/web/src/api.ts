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

const demoQuery = (demo: boolean) => (demo ? { demo: 'true' } : {});

export const api = {
  health: () => parseResponse(client.health.$get()),
  profile: () => parseResponse(client.profile.$get()),
  pmc: (demo: boolean) => parseResponse(client.pmc.$get({ query: demoQuery(demo) })),
  activities: (demo: boolean) => parseResponse(client.activities.$get({ query: demoQuery(demo) })),
  analyze: (id: string) => parseResponse(client.analyze[':id'].$get({ param: { id }, query: {} })),
  next: (demo: boolean) => parseResponse(client.next.$get({ query: demoQuery(demo) })),
  plan: (body: {
    race?: RaceOption;
    weeks?: number;
    start?: string;
    date?: string;
    demo?: boolean;
  }) => parseResponse(client.plan.$post({ json: body })),
};
