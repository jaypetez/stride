import { describe, expect, it } from 'vitest';
import { parseWeeks } from './validate';

describe('parseWeeks', () => {
  it('accepts whole numbers from 1 to 52', () => {
    expect(parseWeeks('1')).toEqual({ weeks: 1 });
    expect(parseWeeks(' 12 ')).toEqual({ weeks: 12 });
    expect(parseWeeks('52')).toEqual({ weeks: 52 });
  });

  it('rejects an empty input instead of sending weeks=0', () => {
    expect(parseWeeks('')).toHaveProperty('error');
    expect(parseWeeks('   ')).toHaveProperty('error');
  });

  it('rejects out-of-range and fractional values', () => {
    for (const raw of ['0', '-3', '53', '2.5', 'abc']) {
      expect(parseWeeks(raw)).toHaveProperty('error');
    }
  });

  it('checks a custom range (build plans are 4–16 weeks)', () => {
    const range = { min: 4, max: 16 };
    expect(parseWeeks('8', range)).toEqual({ weeks: 8 });
    expect(parseWeeks('3', range)).toEqual({ error: 'Weeks must be a whole number from 4 to 16.' });
    expect(parseWeeks('17', range)).toHaveProperty('error');
    expect(parseWeeks('', range)).toEqual({ error: 'Enter the number of weeks (4–16).' });
  });
});
