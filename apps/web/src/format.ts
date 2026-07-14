// Pace and duration are computed and formatted in one place — `@stride/core` — so
// every surface renders them identically (compute-in-code, format-in-one-place).
// Import via the pure `/science` subpath so the browser bundle never pulls core's
// node-only code (store, Strava client, Anthropic SDK). Only genuinely web-only
// formatters live here.
export { formatDuration, formatPace } from '@stride/core/science';

export function formatKm(meters: number | undefined): string {
  if (meters === undefined) return '—';
  return `${(meters / 1000).toFixed(1)} km`;
}
