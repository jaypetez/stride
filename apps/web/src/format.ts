/** Small pure formatting helpers (kept local so the web bundle stays browser-only). */

export function formatPace(secPerKm: number | undefined): string {
  if (!secPerKm || !Number.isFinite(secPerKm) || secPerKm <= 0) return '—';
  const m = Math.floor(secPerKm / 60);
  const s = Math.round(secPerKm % 60);
  return `${s === 60 ? m + 1 : m}:${String(s === 60 ? 0 : s).padStart(2, '0')}/km`;
}

export function formatDuration(sec: number | undefined): string {
  if (!sec) return '—';
  const m = Math.round(sec / 60);
  if (m < 60) return `${m} min`;
  return `${Math.floor(m / 60)}h${String(m % 60).padStart(2, '0')}`;
}

export function formatKm(meters: number | undefined): string {
  if (meters === undefined) return '—';
  return `${(meters / 1000).toFixed(1)} km`;
}
