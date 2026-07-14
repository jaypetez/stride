# `stride analyze --demo`

Analyze a completed workout: compute the sports-science metrics and explain them.
The demo activity is a synthetic 45-minute rolling-hills run.

## Command

```bash
pnpm --filter @stride/cli dev -- analyze --demo --now 2026-07-14T12:00:00Z
```

## Output

```text
Demo Rolling-Hills Run
  2026-07-08T08:00:00 · run
  Distance             7.39 km
  Moving time          45 min
  Training load        54.9 TSS (rtss) · IF 0.855
  Avg pace             6:05/km
  Grade-adj pace       5:51/km
  Avg HR               140 bpm
  Efficiency factor    1.2
  Aerobic decoupling   1.1%
  Intensity split      100% easy / 0% mod / 0% hard

Coach
  This 45-minute run carried a training load of 54.9 TSS (via rtss). You averaged 6:05/km (grade-adjusted 5:51/km). At 140 bpm average, your efficiency factor was 1.2. Aerobic decoupling was 1.1%, indicating strong aerobic durability — pace held steady relative to heart rate. Intensity split: 100% easy / 0% moderate / 0% hard.

  Stride is for informational and educational purposes only and is not a substitute for professional medical advice. Consult a qualified healthcare provider before beginning any fitness program, especially if you have any pre-existing conditions.

  (Set ANTHROPIC_API_KEY for richer, LLM-written analysis.)
```

## Machine-readable (`--json`)

```bash
pnpm --filter @stride/cli dev -- analyze --demo --json --now 2026-07-14T12:00:00Z
```

```json
{
  "metrics": {
    "activityId": "demo-activity",
    "tss": 54.9,
    "method": "rtss",
    "intensityFactor": 0.855,
    "durationSec": 2700,
    "distanceM": 7390.7,
    "averageSpeedMps": 2.737,
    "gradeAdjustedSpeedMps": 2.849,
    "averageHr": 140,
    "efficiencyFactor": 1.2,
    "aerobicDecouplingPct": 1.1,
    "zoneDistribution": {
      "easySec": 2701,
      "moderateSec": 0,
      "hardSec": 0,
      "easyPct": 100,
      "moderatePct": 0,
      "hardPct": 0
    },
    "averagePaceSecPerKm": 365.3,
    "gradeAdjustedPaceSecPerKm": 351.1,
    "hrZoneSeconds": { "1": 1101, "2": 1600, "3": 0, "4": 0, "5": 0 },
    "paceZoneSeconds": { "1": 1701, "2": 0, "3": 992, "4": 0, "5": 8 }
  },
  "analysis": {
    "headline": "Demo Rolling-Hills Run · 7.4 km · 54.9 TSS · IF 0.855",
    "explanation": "This 45-minute run carried a training load of 54.9 TSS (via rtss). You averaged 6:05/km (grade-adjusted 5:51/km). At 140 bpm average, your efficiency factor was 1.2. Aerobic decoupling was 1.1%, indicating strong aerobic durability — pace held steady relative to heart rate. Intensity split: 100% easy / 0% moderate / 0% hard.",
    "flags": [],
    "disclaimer": "Stride is for informational and educational purposes only and is not a substitute for professional medical advice. Consult a qualified healthcare provider before beginning any fitness program, especially if you have any pre-existing conditions."
  }
}
```

The `analysis.activity` echo of the input summary is elided above for brevity.
Note the load `method` is `rtss` (the pace-based path), computed from the run's
grade-adjusted streams — not asked of the model.

## Safety note

Pass `--note` to describe how you feel; it is screened for red flags before any
model call. A STOP keyword short-circuits the analysis:

```bash
pnpm --filter @stride/cli dev -- analyze --demo --note "chest pain on the hills"
```

surfaces a "stop exercising and seek medical advice" flag and disclaimer instead
of a normal explanation.
