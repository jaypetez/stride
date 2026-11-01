# 0006. Data sources for Stride Groups, and the accepted Strava risk

- Status: Accepted
- Date: 2026-11-01

## Context
Stride Groups needs workouts from the tools runners already use: Strava and
Garmin first. Research on 2026-11-01 found:

- **Strava API Agreement / Policy (2026-06-01):**
  - A user's Strava data may only be displayed to that user.
  - Policy §5.3 bans use "in connection with … any AI Application", including
    "ingestion into a context window".
  - §6.2 caps the cache at 7 days.
  - New apps get 1 athlete and can self-upgrade to 10.
  - These terms bind any API app. Distributing outside the Play Store does not
    change them.
- **Garmin Connect Developer Program:** paused new sign-ups in 2026, with no reopening date.
- **Android Health Connect:**
  - Garmin Connect writes exercise sessions, heart rate, distance, speed,
    elevation and cadence to it.
  - Strava writes time, distance and calories.
  - A third-party app can read these with per-type user permission.
- **.FIT / .GPX files:** always available from both ecosystems.

The maintainer reviewed these findings and chose to integrate Strava directly,
share activities within groups, and use the AI coach on all data, as a
**knowingly accepted risk** for a small private friends group.

## Decision
Sources, in order of build:
1. **Strava** OAuth + webhooks, server-side on the Worker. This reuses
   `packages/core/src/strava/{oauth,client}.ts`, keeps the host configurable for
   `api-v3.strava.com` (2027-01-04), and prepares for header-only tokens and
   `oauth/revoke` (2027-06-01).
2. **Health Connect** on Android. Garmin data arrives this way until a direct
   Garmin API is possible (parked).
3. **.FIT / .GPX import.**

All sources normalize into one ingestion API and dedupe across sources.

Two server flags make strict compliance a configuration change, not a rewrite:

| Flag | `group` / `on` (default) | `owner` / `off` (strict) |
|---|---|---|
| `STRAVA_VISIBILITY` | Strava-sourced activities visible to the owner's groups | visible to the owner only |
| `STRAVA_AI` | Strava-sourced data may enter coach context | excluded via `filterForAi()` |

Both are enforced in the authorization policy module (ADR 0009) and tested
under both values. The 7-day raw-cache sweep and the durable derived
daily-load series (ADR 0002) still apply. "View on Strava" / "Powered by
Strava" attribution is shown on Strava-sourced activities.

## Consequences
- Strava may revoke API access. Flipping both flags to strict mode is the
  first response; Health Connect and FIT remain as sources.
- Before any public or larger-scale launch, re-verify the live terms and ask
  Strava for written guidance (parked issue).
- This is not legal advice.
