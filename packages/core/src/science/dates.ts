/** Calendar-date helpers operating on YYYY-MM-DD keys (UTC, DST-safe). */

const MS_PER_DAY = 86_400_000;

/** Extract a YYYY-MM-DD key from an ISO datetime (or date) string. */
export function toDateKey(iso: string): string {
  return iso.slice(0, 10);
}

function keyToMs(key: string): number {
  const [y, m, d] = key.split('-').map(Number);
  return Date.UTC(y, (m ?? 1) - 1, d ?? 1);
}

function msToKey(ms: number): string {
  const dt = new Date(ms);
  const y = dt.getUTCFullYear();
  const m = String(dt.getUTCMonth() + 1).padStart(2, '0');
  const d = String(dt.getUTCDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

export function addDays(key: string, n: number): string {
  return msToKey(keyToMs(key) + n * MS_PER_DAY);
}

export function daysBetween(a: string, b: string): number {
  return Math.round((keyToMs(b) - keyToMs(a)) / MS_PER_DAY);
}

/** Inclusive list of date keys from start to end. */
export function eachDay(start: string, end: string): string[] {
  const out: string[] = [];
  const total = daysBetween(start, end);
  for (let i = 0; i <= total; i++) out.push(addDays(start, i));
  return out;
}
