import { describe, expect, it } from 'vitest';
import { Activity, AthleteProfile, TrainingPlan, WorkoutSuggestion } from '../src/index';

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
