# 0010. Solo Garmin MVP: a local-only Android app on the Garmin Personal API

- Status: Accepted
- Date: 2026-11-01

## Context
Before building group training (ADRs 0005–0009), the maintainer wants a
simplified MVP to share with a few friends. In it:
- each person installs the Android app, connects **their own Garmin account**,
  and sees what Stride can do with their data
- there are no groups, no accounts and no backend

On **2026-10-21** Garmin reopened its Connect Developer Program with a free
**Personal API** tier (https://developer.garmin.com/gc-developer-program/personal/personal-api):
- **Getting a key:** the user creates a key in Garmin Connect web settings. It
  is valid for 30, 90 or 180 days or 1 year, and it is shown once.
- **Using it:** the key is exchanged at
  `POST https://personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token`
  (`Authorization: ApiKey <key>`) for a **24-hour OAuth2 access token**. There
  is no refresh token; you exchange the key again.
- **Access:** pull-only and read-only. It covers Activity (JSON + FIT), Health
  (JSON: daily summaries, sleep, HRV, resting HR, stress, Body Battery,
  weight) and Women's Health.
- **Not exposed:** VO2max, training status, training readiness and personal
  records.
- **Query window:** ranged queries span about 28–31 days.
- **Intended use:** "not intended for commercial applications serving multiple
  end users".
- **AI clause:** the developer terms reportedly bar using Garmin data to
  "create or maintain any AI model, AI system or AI generated knowledge base".
  We have not read the clause first-hand.

Unofficial libraries are not an option. garth is deprecated, and
python-garminconnect regularly runs into Cloudflare blocks and account
lockouts.

## Decision
- **Local-only Android:** no server, no Stride account and no telemetry.
  - Each user pastes their own Personal API key.
  - The key is stored on the device in DataStore + Tink and is never logged.
  - The app exchanges the key for the 24-hour token as needed.
  - Every request goes from the device directly to Garmin. This is exactly the
    personal use Garmin intends.
- **Data:** Room on the device holds activities, laps, FIT-derived streams and
  daily wellness. WorkManager syncs incrementally.
- **Science on the device:**
  - The engine in `packages/core` is TypeScript, so the MVP ports it to a
    Kotlin `core:science` module: load, PMC, ACWR, zones, VDOT/VO2max
    estimate, race predictions, anchors, efficiency, and the next-workout
    planner and guardrail.
  - **Parity is enforced.** A TS script writes golden outputs from
    `packages/core` to `contracts/science-golden/`, and Kotlin tests must
    match them. CI fails when the goldens are stale.
  - Compute-in-code (GOAL.md §3) is preserved.
  - We compute the metrics Garmin doesn't expose (VO2max estimate, load,
    form) ourselves.
- **AI coach:** optional, **off by default**, and bring-your-own Anthropic
  key. It receives only computed facts and shows the Garmin-terms caveat.
- **Distribution:**
  - a signed APK on GitHub Releases, with an Obtainium guide
  - a **demo mode** with synthetic data, for friends without a key and for
    screenshot and E2E tests
- **Factory:**
  - A new `MVP` phase is ordered before P0.
  - `tools/factory/src/focus.ts` keeps agents on it.
  - The MVP pulls in the reusable Android foundation issues (ANDF/GOV/QA/REL/SEC).
  - Group work (P0–P6) waits.

## Consequences
- A second implementation of the science exists. The parity test keeps the
  Kotlin and TS versions in lockstep. When group mode lands, metrics can move
  server-side or the Kotlin module can remain the on-device engine.
- **What carries over to group mode:** the Android foundation, design system,
  local data layer and screens.
- **What group mode will need from Garmin:** the Starter tier, not Personal
  keys (see ADR 0006 addendum).
- **Risk:** the Personal API's endpoint list and rate limits are unverified.
  The first MVP issue is a spike that documents them in
  `docs/garmin/personal-api.md`.
