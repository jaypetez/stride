import { describe, expect, it } from 'vitest';
import { addDays, daysBetween, eachDay, toDateKey } from '../src/science/dates';

describe('date keys accept full ISO datetimes (AUD-002)', () => {
  it('daysBetween works on YYYY-MM-DD keys', () => {
    expect(daysBetween('2026-07-09', '2026-09-06')).toBe(59);
    expect(daysBetween('2026-09-06', '2026-07-09')).toBe(-59);
  });

  it('daysBetween accepts full ISO datetimes and returns integers', () => {
    const n = daysBetween('2026-07-09T23:59:00Z', '2026-09-06T00:00:00.000Z');
    expect(n).toBe(59);
    expect(Number.isInteger(n)).toBe(true);
    expect(daysBetween('2026-07-09', '2026-09-06T18:30:00+02:00')).toBe(59);
  });

  it('addDays / eachDay accept a datetime and return keys', () => {
    expect(addDays('2026-07-09T06:00:00Z', 1)).toBe('2026-07-10');
    expect(eachDay('2026-07-09T06:00:00Z', '2026-07-11T23:00:00Z')).toEqual([
      '2026-07-09',
      '2026-07-10',
      '2026-07-11',
    ]);
  });

  it('crosses month/DST boundaries by whole calendar days', () => {
    expect(daysBetween('2026-03-28', '2026-03-30')).toBe(2);
    expect(daysBetween('2026-10-24T12:00:00', '2026-10-26T01:00:00')).toBe(2);
    expect(toDateKey('2026-10-26T01:00:00')).toBe('2026-10-26');
  });
});
