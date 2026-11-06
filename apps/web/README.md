# @stride/web

Vite + React dashboard for Stride. Consumes the HTTP API via Hono's typed `hc`
RPC client (`src/api.ts`), so request/response types come straight from the API's
exported `AppType` — no hand-maintained interfaces. Pace/duration formatting is
reused from `@stride/core/science` (a pure, browser-safe subpath).

## Run

```bash
pnpm --filter @stride/web dev   # http://localhost:5173 (demo mode by default)
```

The dashboard reads everything (demo data included) from the API, so run it too
(`pnpm --filter @stride/api dev`, offline for demo mode); the dev server proxies
`/api` to it. Set `VITE_API_BASE` to point at a non-default API origin.

## Test

```bash
pnpm --filter @stride/web test                             # Vitest + Testing Library (happy-dom)
pnpm --filter @stride/web exec playwright install chromium # once
pnpm --filter @stride/web e2e                              # Playwright + axe-core
```

`e2e` starts the real API on `:8720` (offline demo, pinned `STRIDE_NOW`, an empty
temp store for "My data") and Vite on `:5173`, then asserts demo data renders,
red-flag notes raise STOP/WARN alerts, and axe finds no serious or critical
violations in any view (light and dark). Locally it reuses servers already
running on those ports; in CI (`CI=true`) it never does, retries twice, records a
trace on the first retry, and CI uploads `playwright-report/` on failure.

## Safety and accessibility

- Coach output (`/next`, `/analyze`, `/plan`) always shows its `disclaimer`, and
  any safety `flags` render above the result as `role="alert"` banners: STOP
  first, then warnings. The "How do you feel today?" check-in sends a `note`
  that core screens for red flags, like the CLI's `--note`.
- Text colours meet WCAG AA 4.5:1 in both colour schemes; the PMC chart has a
  `<desc>`, axis labels, dashed/dotted series and a visually hidden data table.

## Strava attribution — compliance TODO

Per Strava's brand guidelines (GOAL §4), any UI displaying Strava data must show a
compliant **"Powered by Strava"** badge and **"View on Strava"** deep links
(Strava orange `#FC5200`; in light mode the "View on Strava" link text uses
the darker `#C2410C`, bold and underlined, to meet WCAG AA contrast), kept
distinct from and no more prominent than our own brand.

The current `components/PoweredByStrava.tsx` is a **self-contained styled
placeholder** (orange pill, exact required text, no fabricated Strava logo mark).

- [ ] **Before any public / hosted deployment:** replace the placeholder with the
      official **"Powered by Strava"** raster badge from Strava's brand assets
      (<https://developers.strava.com/guidelines/>). Do not recreate Strava's logo
      by hand.
