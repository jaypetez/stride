import { formatPace } from '@stride/core';
import { describe, expect, it } from 'vitest';
// `apps/cli/src/ui.ts` owns formatDuration and re-uses core's formatPace for the
// terminal UI.
import { formatDuration } from '../src/ui';

describe('formatPace', () => {
  it('formats seconds-per-km as m:ss/km', () => {
    expect(formatPace(300)).toBe('5:00/km');
    expect(formatPace(270)).toBe('4:30/km');
    expect(formatPace(305)).toBe('5:05/km');
  });

  it('carries seconds that round up to a full minute', () => {
    expect(formatPace(359.6)).toBe('6:00/km');
  });

  it('returns a dash for non-positive or non-finite input', () => {
    expect(formatPace(0)).toBe('—');
    expect(formatPace(-30)).toBe('—');
    expect(formatPace(Number.NaN)).toBe('—');
    expect(formatPace(undefined as unknown as number)).toBe('—');
  });
});

describe('formatDuration', () => {
  it('formats sub-hour durations in minutes', () => {
    expect(formatDuration(300)).toBe('5 min');
    expect(formatDuration(90)).toBe('2 min');
    expect(formatDuration(0)).toBe('0 min');
  });

  it('formats hour-plus durations as HhMM', () => {
    expect(formatDuration(3600)).toBe('1h00');
    expect(formatDuration(5400)).toBe('1h30');
    expect(formatDuration(7200)).toBe('2h00');
  });
});
