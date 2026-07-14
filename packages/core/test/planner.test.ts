import { WorkoutSuggestion } from '@stride/schemas';
import { describe, expect, it } from 'vitest';
import { makeSession } from '../src/coach/index';

const THRESHOLD = 3.33;

describe('makeSession — no Infinity/NaN pace for paceIf=0 types', () => {
  it('cross_training omits pace, uses distance 0, and round-trips through the schema', () => {
    const s = makeSession('cross_training', 40, THRESHOLD);

    // The bug: pace = mpsToSecPerKm(threshold * 0) = Infinity, which
    // JSON-serializes to null and fails schema re-parse. Fixed: field omitted.
    expect(s.targetPaceSecPerKm).toBeUndefined();
    expect(s.targetDistanceM).toBe(0);
    // No field is ever Infinity/NaN.
    for (const v of Object.values(s)) {
      if (typeof v === 'number') expect(Number.isFinite(v)).toBe(true);
    }
    expect(s.targetDurationSec).toBe(2400);

    // Persisted plans are JSON: Infinity -> null would make this parse throw.
    const roundTripped = WorkoutSuggestion.parse(JSON.parse(JSON.stringify(s)));
    expect(roundTripped.type).toBe('cross_training');
    expect(roundTripped.targetPaceSecPerKm).toBeUndefined();
  });

  it('still emits a finite pace for a normal running type', () => {
    const s = makeSession('easy', 45, THRESHOLD);
    expect(typeof s.targetPaceSecPerKm).toBe('number');
    expect(Number.isFinite(s.targetPaceSecPerKm ?? Number.NaN)).toBe(true);
    expect((s.targetDistanceM ?? 0) > 0).toBe(true);
    expect(() => WorkoutSuggestion.parse(JSON.parse(JSON.stringify(s)))).not.toThrow();
  });
});
