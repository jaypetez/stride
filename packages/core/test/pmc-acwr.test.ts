import type { DailyLoad } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { buildAcwrSeries } from '../src/science/acwr';
import { buildPmcSeries } from '../src/science/pmc';

const load = (date: string, tss: number): DailyLoad => ({
  date,
  tss,
  durationSec: 0,
  distanceM: 0,
  method: 'rtss',
  activityIds: [],
});

const series = [
  load('2026-06-01', 50),
  load('2026-06-03', 80),
  load('2026-06-05', 60),
  load('2026-06-10', 90),
];

describe('PMC/ACWR end at throughDate (AUD-002)', () => {
  it('buildPmcSeries truncates at a throughDate before the last load', () => {
    const cut = buildPmcSeries(series, { throughDate: '2026-06-05' });
    expect(cut.at(-1)?.date).toBe('2026-06-05');
    // Same values as the full series up to the cut (no future load leaks in).
    const full = buildPmcSeries(series);
    expect(cut).toEqual(full.slice(0, cut.length));
  });

  it('buildPmcSeries still projects past the last load', () => {
    expect(buildPmcSeries(series, { throughDate: '2026-06-12' }).at(-1)?.date).toBe('2026-06-12');
  });

  it('buildAcwrSeries truncates at a throughDate before the last load', () => {
    const cut = buildAcwrSeries(series, { throughDate: '2026-06-05' });
    expect(cut.map((p) => p.date)).toEqual([
      '2026-06-01',
      '2026-06-02',
      '2026-06-03',
      '2026-06-04',
      '2026-06-05',
    ]);
  });

  it('a throughDate before the first load yields an empty series', () => {
    expect(buildPmcSeries(series, { throughDate: '2026-05-01' })).toEqual([]);
    expect(buildAcwrSeries(series, { throughDate: '2026-05-01' })).toEqual([]);
  });
});

describe('ACWR seed over calendar days (AUD-002)', () => {
  it('averages the first 7 CALENDAR days with zeros for rest days', () => {
    // Loads only on day 0 and day 6 (2 entries, 5 rest days in between).
    const sparse = [load('2026-06-01', 70), load('2026-06-07', 70), load('2026-06-20', 40)];
    const first = buildAcwrSeries(sparse)[0];
    const seed = (70 + 70) / 7; // = 20, NOT mean of the first entries (70)
    const lambda = 2 / 8;
    expect(first.acuteLoad).toBeCloseTo(70 * lambda + (1 - lambda) * seed, 2);
    expect(first.chronicLoad).toBeCloseTo(70 * (2 / 29) + (1 - 2 / 29) * seed, 2);
  });

  it('never seeds from loads past the series end', () => {
    const cut = buildAcwrSeries([load('2026-06-01', 30), load('2026-06-04', 300)], {
      throughDate: '2026-06-02',
    });
    // Seed is mean(30, 0) = 15; the day-4 load must not be part of it.
    expect(cut[0].acuteLoad).toBeCloseTo(30 * 0.25 + 0.75 * 15, 2);
  });
});
