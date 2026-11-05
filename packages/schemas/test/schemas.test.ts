import { describe, expect, it } from 'vitest';
import { Activity, AthleteProfile, IsoDate, TrainingPlan, WorkoutSuggestion } from '../src/index';

describe('@stride/schemas', () => {
  it('parses a minimal activity and applies defaults', () => {
    const a = Activity.parse({
      id: '123',
      source: 'strava',
      sportType: 'run',
      name: 'Morning Run',
      startDate: '2026-07-01T06:00:00Z',
      distance: 10000,
      movingTime: 3000,
      elapsedTime: 3100,
    });
    expect(a.totalElevationGain).toBe(0);
    expect(a.hasHeartrate).toBe(false);
    expect(a.trainer).toBe(false);
  });

  it('applies athlete profile defaults', () => {
    const p = AthleteProfile.parse({});
    expect(p.id).toBe('me');
    expect(p.experienceLevel).toBe('intermediate');
    expect(p.injuryHistory).toEqual([]);
    expect(p.medicalClearance).toBe(false);
  });

  it('rejects an out-of-range plan day', () => {
    const bad = () =>
      TrainingPlan.parse({
        id: 'p1',
        createdAt: '2026-07-01T00:00:00Z',
        goal: { distance: '10k' },
        startDate: '2026-07-01',
        weeks: [
          {
            weekNumber: 1,
            phase: 'base',
            focus: 'aerobic',
            days: [{ day: 9, sessions: [] }],
          },
        ],
      });
    expect(bad).toThrow();
  });

  it('validates a workout suggestion', () => {
    const w = WorkoutSuggestion.parse({
      type: 'easy',
      label: 'E',
      title: 'Easy 40 min',
      description: 'Conversational pace',
      rationale: 'Aerobic base; keep it easy after yesterday.',
    });
    expect(w.type).toBe('easy');
  });
});

describe('IsoDate', () => {
  it('accepts real calendar dates, including 29 February in a leap year', () => {
    for (const d of ['2026-07-09', '2024-02-29', '2000-02-29']) {
      expect(IsoDate.safeParse(d).success, d).toBe(true);
    }
  });

  it('rejects impossible dates and other formats', () => {
    for (const d of ['2026-02-30', '2025-02-29', '1900-02-29', '2026-13-01', '07/09/2026', 'x']) {
      expect(IsoDate.safeParse(d).success, d).toBe(false);
    }
  });
});
