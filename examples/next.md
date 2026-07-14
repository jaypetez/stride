# `stride next --demo`

Suggest the next workout from current form (TSB), the weekly intensity
distribution, the ACWR guardrail, and (in demo) a 10k goal. The prescription is
deterministic; only the "why" is prose.

## Command

```bash
pnpm --filter @stride/cli dev -- next --demo --now 2026-07-14T12:00:00Z
```

## Output

```text
Current form
  CTL 28.49 (fitness) · ATL 18.7 (fatigue) · TSB 7.61 (form)
  ACWR 0.22 (low)
  Last 7 days: 7.4 km · 100% easy / 0% hard

Next: Easy run (45 min)
  Duration             45 min
  Target pace          6:40/km
  Target HR zone       Z2
  Estimated load       35 TSS

  45 min at a conversational, aerobic pace.

  Why: Your last session was hard, so keep ~48h between quality days: an easy run aids recovery and preserves the 80/20 balance.

  Stride is for informational and educational purposes only and is not a substitute for professional medical advice. Consult a qualified healthcare provider before beginning any fitness program, especially if you have any pre-existing conditions.

  (Set ANTHROPIC_API_KEY for an LLM-written rationale.)
```

## Machine-readable (`--json`)

```bash
pnpm --filter @stride/cli dev -- next --demo --json --now 2026-07-14T12:00:00Z
```

```json
{
  "fitness": { "date": "2026-07-14", "ctl": 28.49, "atl": 18.7, "tsb": 7.61 },
  "acwr": {
    "date": "2026-07-14",
    "acwr": 0.22,
    "acuteLoad": 6.99,
    "chronicLoad": 31.73,
    "flag": "low"
  },
  "weeklyDistribution": {
    "easySec": 2701,
    "moderateSec": 0,
    "hardSec": 0,
    "easyPct": 100,
    "moderatePct": 0,
    "hardPct": 0
  },
  "workout": {
    "type": "easy",
    "label": "E",
    "title": "Easy run (45 min)",
    "description": "45 min at a conversational, aerobic pace.",
    "targetDistanceM": 6743,
    "targetDurationSec": 2700,
    "targetPaceSecPerKm": 400,
    "targetHrZone": 2,
    "targetTss": 35,
    "rationale": "Your last session was hard, so keep ~48h between quality days: an easy run aids recovery and preserves the 80/20 balance.",
    "disclaimer": "Stride is for informational and educational purposes only and is not a substitute for professional medical advice. Consult a qualified healthcare provider before beginning any fitness program, especially if you have any pre-existing conditions.",
    "flags": []
  }
}
```

The reference date drives the fitness/fatigue projection: the most recent demo
run is dated 2026-07-08, so fixing `--now` to `2026-07-14` gives fatigue (ATL)
several days to decay, which is why form (TSB) is positive. Change `--now` and
the numbers change deterministically.
