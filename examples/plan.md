# `stride plan --demo --race 10k --weeks 8`

Generate a periodized 8-week plan toward a 10k. The structure follows base →
build → peak → taper with a recovery week every 4th week; every duration, pace,
HR zone, and load is computed in code, then checked by the deterministic
guardrail (ramp, rest, back-to-back-hard, long-run caps).

## Command

```bash
pnpm --filter @stride/cli dev -- plan --demo --race 10k --weeks 8 --now 2026-07-14T12:00:00Z
```

## Output

```text
8-week plan → 10k
  A 8-week 10k plan: base → build → peak → taper, with a recovery week every 4th week.

  Week 1 [base] ~192 TSS · ~36 km
     Easy run · Easy run · Easy run · Easy run · Long run
  Week 2 [base] ~198 TSS · ~37.2 km
     Easy run · Easy run · Easy run · Easy run · Long run
  Week 3 [base] ~205 TSS · ~38.3 km
     Easy run · Easy run · Easy run · Easy run · Long run
  Week 4 [recovery] ~130 TSS · ~24.4 km
     Easy run · Easy run · Easy run · Long run
  Week 5 [build] ~254 TSS · ~42.9 km
     Threshold session · Easy run · Easy run · Tempo run · Long run
  Week 6 [peak] ~263 TSS · ~43.8 km
     Interval session · Easy run · Tempo run · Easy run · Long run
  Week 7 [taper] ~112 TSS · ~20.1 km
     Threshold session · Easy run · Easy run · Easy run
  Week 8 [taper] ~112 TSS · ~20.1 km
     Threshold session · Easy run · Easy run · Easy run

  ✓ Plan passes all guardrails (ramp, rest, back-to-back, long-run caps).

  Stride is for informational and educational purposes only and is not a substitute for professional medical advice. Consult a qualified healthcare provider before beginning any fitness program, especially if you have any pre-existing conditions.

  (Set ANTHROPIC_API_KEY for an LLM-written plan overview.)
```

The one-line-per-week view lists only the non-rest sessions; each week also
includes rest days (the guardrail requires at least one).

## Machine-readable (`--json`)

`--json` emits the full plan with every day and session. It is large (all 8
weeks × 7 days), so only week 1 is shown here to illustrate the shape — note that
each session carries computed `targetPaceSecPerKm`, `targetHrZone`, `targetTss`,
and `targetDistanceM`:

```bash
pnpm --filter @stride/cli dev -- plan --demo --race 10k --weeks 8 --json --now 2026-07-14T12:00:00Z
```

```json
{
  "plan": {
    "id": "plan-2026-07-14-8w",
    "createdAt": "2026-07-14T12:00:00Z",
    "goal": { "distance": "10k", "name": "10k" },
    "startDate": "2026-07-14",
    "endDate": "2026-09-07",
    "summary": "A 8-week 10k plan: base → build → peak → taper, with a recovery week every 4th week.",
    "weeks": [
      {
        "weekNumber": 1,
        "phase": "base",
        "focus": "Aerobic base — mostly easy volume, a light dose of intensity.",
        "targetTss": 192,
        "targetDistanceKm": 36,
        "days": [
          {
            "day": 1,
            "date": "2026-07-14",
            "sessions": [
              {
                "type": "rest",
                "title": "Rest day",
                "description": "Full rest. Recovery is when adaptation happens.",
                "date": "2026-07-14",
                "targetDurationSec": 0,
                "targetTss": 0,
                "rationale": "Rest days let fitness consolidate and reduce injury risk."
              }
            ]
          },
          {
            "day": 2,
            "date": "2026-07-15",
            "sessions": [
              {
                "type": "easy",
                "label": "E",
                "title": "Easy run (45 min)",
                "description": "45 min at a conversational, aerobic pace.",
                "date": "2026-07-15",
                "targetDistanceM": 6743,
                "targetDurationSec": 2700,
                "targetPaceSecPerKm": 400,
                "targetHrZone": 2,
                "targetTss": 35,
                "rationale": "Most weekly volume should be easy (the ~80/20 principle) to build aerobic fitness while staying fresh."
              }
            ]
          }
        ]
      }
    ]
  },
  "validation": { "valid": true, "violations": [], "repaired": false }
}
```

## Guardrail in action

Without an `ANTHROPIC_API_KEY` the plan is built by the deterministic skeleton,
which is guardrail-clean by construction — hence `validation.valid: true` and no
violations. With a key, the LLM instead proposes the week/day *structure*, code
materializes the numbers, and the same guardrail validates/repairs the result
(see [ADR 0003](../docs/adr/0003-option-a-plan-generation.md)). Either way the
numbers are computed in code, never by the model.
