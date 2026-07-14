# @stride/web

Vite + React dashboard for Stride. Consumes the HTTP API via Hono's typed `hc`
RPC client (`src/api.ts`), so request/response types come straight from the API's
exported `AppType` — no hand-maintained interfaces. Pace/duration formatting is
reused from `@stride/core/science` (a pure, browser-safe subpath).

## Run

```bash
pnpm --filter @stride/web dev   # http://localhost:5173 (demo mode by default)
```

Demo mode needs nothing else. For live data, run the API too
(`pnpm --filter @stride/api dev`); the dev server proxies `/api` to it. Set
`VITE_API_BASE` to point at a non-default API origin.

## Strava attribution — compliance TODO

Per Strava's brand guidelines (GOAL §4), any UI displaying Strava data must show a
compliant **"Powered by Strava"** badge and **"View on Strava"** deep links
(Strava orange `#FC5200`), kept distinct from and no more prominent than our own
brand.

The current `components/PoweredByStrava.tsx` is a **self-contained styled
placeholder** (orange pill, exact required text, no fabricated Strava logo mark).

- [ ] **Before any public / hosted deployment:** replace the placeholder with the
      official **"Powered by Strava"** raster badge from Strava's brand assets
      (<https://developers.strava.com/guidelines/>). Do not recreate Strava's logo
      by hand.
