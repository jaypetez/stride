/** Shared numeric helpers for the sports-science engine. */

export const SECONDS_PER_HOUR = 3600;
export const METERS_PER_KM = 1000;

export function clamp(x: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, x));
}

export function mean(values: number[]): number {
  if (values.length === 0) return 0;
  let sum = 0;
  for (const v of values) sum += v;
  return sum / values.length;
}

export function sum(values: number[]): number {
  let s = 0;
  for (const v of values) s += v;
  return s;
}

/** Convert speed (m/s) to running pace (seconds per km). Returns Infinity at 0. */
export function mpsToSecPerKm(mps: number): number {
  return mps > 0 ? METERS_PER_KM / mps : Number.POSITIVE_INFINITY;
}

/** Convert pace (seconds per km) to speed (m/s). */
export function secPerKmToMps(secPerKm: number): number {
  return secPerKm > 0 ? METERS_PER_KM / secPerKm : 0;
}

/**
 * Minetti (2002) energy cost of running as a function of gradient (as a
 * fraction, e.g. 0.10 = 10% uphill). Returns J/kg/m. Flat cost is ~3.6.
 * Valid roughly for gradients in [-0.45, 0.45]; we clamp to that range.
 */
export function minettiCost(gradeFraction: number): number {
  const i = clamp(gradeFraction, -0.45, 0.45);
  return 155.4 * i ** 5 - 30.4 * i ** 4 - 43.3 * i ** 3 + 46.3 * i ** 2 + 19.5 * i + 3.6;
}

const FLAT_COST = minettiCost(0);

/**
 * Grade-adjustment factor: how much faster an equivalent flat effort would be
 * at the same metabolic cost. gap_speed = raw_speed * gradeAdjustFactor(grade).
 */
export function gradeAdjustFactor(gradeFraction: number): number {
  return minettiCost(gradeFraction) / FLAT_COST;
}

/**
 * Time-windowed rolling mean. For each index, averages values whose timestamps
 * fall within `windowSec` before it. `times` are seconds-from-start.
 */
export function rollingMeanByTime(values: number[], times: number[], windowSec: number): number[] {
  const n = values.length;
  const out = new Array<number>(n);
  let start = 0;
  let windowSum = 0;
  for (let end = 0; end < n; end++) {
    windowSum += values[end];
    while (times[end] - times[start] > windowSec && start < end) {
      windowSum -= values[start];
      start++;
    }
    out[end] = windowSum / (end - start + 1);
  }
  return out;
}

/**
 * Duration-weighted 4th-power norm (the Normalized-Power algorithm's core):
 * (mean(x^4))^(1/4), weighted by each sample's dt. Weighting by dt keeps the
 * result correct when samples are unevenly spaced.
 */
export function fourthPowerNorm(values: number[], dts: number[]): number {
  let weighted = 0;
  let totalDt = 0;
  for (let i = 0; i < values.length; i++) {
    const dt = dts[i] ?? 0;
    weighted += values[i] ** 4 * dt;
    totalDt += dt;
  }
  if (totalDt === 0) return 0;
  return (weighted / totalDt) ** 0.25;
}

/**
 * Per-sample time deltas (seconds) from a monotonic time stream. The last
 * sample inherits the previous delta so total ≈ elapsed time.
 */
export function timeDeltas(times: number[]): number[] {
  const n = times.length;
  if (n === 0) return [];
  const dts = new Array<number>(n);
  for (let i = 0; i < n - 1; i++) {
    dts[i] = Math.max(0, times[i + 1] - times[i]);
  }
  dts[n - 1] = n > 1 ? dts[n - 2] : 1;
  return dts;
}

/** Linear interpolation over a sorted (x, y) table, clamped at the ends. */
export function interpolate(x: number, table: ReadonlyArray<readonly [number, number]>): number {
  if (table.length === 0) return 0;
  if (x <= table[0][0]) return table[0][1];
  const last = table[table.length - 1];
  if (x >= last[0]) return last[1];
  for (let i = 0; i < table.length - 1; i++) {
    const [x0, y0] = table[i];
    const [x1, y1] = table[i + 1];
    if (x >= x0 && x <= x1) {
      const t = (x - x0) / (x1 - x0);
      return y0 + t * (y1 - y0);
    }
  }
  return last[1];
}

/** Format seconds-per-km as m:ss/km. */
export function formatPace(secPerKm: number): string {
  if (!Number.isFinite(secPerKm) || secPerKm <= 0) return '—';
  const m = Math.floor(secPerKm / 60);
  const s = Math.round(secPerKm % 60);
  const ss = s === 60 ? '00' : String(s).padStart(2, '0');
  const mm = s === 60 ? m + 1 : m;
  return `${mm}:${ss}/km`;
}
