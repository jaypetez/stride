# Garmin Personal API fixtures

These are test fixtures for the `core:garmin` Kotlin client. Their shapes follow
[`docs/garmin/personal-api.md`](../../../../../../../docs/garmin/personal-api.md).

## Files in this folder are synthetic

Every file in this folder is **synthetic**. We wrote them by hand, using Garmin's
OpenAPI schema and community-observed live quirks as a guide. They contain no
real person's data:
- ids are clearly fake (`9000000xx`, `00000000-0000-4000-8000-…`)
- names are `REDACTED`
- GPS fields are `null`
- the token is an unsigned `alg: none` JWT

| File | Endpoint | Notes |
|---|---|---|
| `token.txt` | `POST /personalapitoken-service/personal-api/personal-api-key/token` | Raw JWT text body. The `exp` claim is 24 h after `iat` |
| `activities.json` | `GET /activitylist-service/activities/search/activities` | 4 runs (road, trail, a race) plus cycling and strength, newest first |
| `activity-detail.json` | `GET /activity-service/activity/{id}` | Detail for the newest run (`activityId` 900000006) |
| `dailies.json` | `GET /usersummary-service/v2/dailysummaries` | `{ "data": [...] }`, 7 days. Has RHR, stress, Body Battery, steps |
| `sleeps.json` | `GET /sleep-service/sleep/dailySleepSummariesByDate` | Timestamps are epoch ms |
| `hrv.json` | `GET /hrv-service/hrv/daily/{from}/{to}` | `{ hrvSummaries, userProfilePk }` |
| `stress.json` | `GET /usersummary-service/stats/stress/daily/{start}/{end}` | Durations in seconds, may be `null` |
| `body-battery.json` | `GET /wellness-service/wellness/bodyBattery/reports/daily` | Optional intraday data |
| `daily-heart-rate.json` | `GET /wellness-service/wellness/dailyHeartRate?date=` | RHR fallback |
| `body-comps.json` | `GET /weight-service/weight/range/{start}/{end}` | `weight` is in **grams** |
| `error-401.json` | any data endpoint without a valid token | Usually an empty object |
| `error-400.json` | an oversized range | The `ErrorBody` shape `{error, clientMessage, message, errorId}` |

## Real fixtures (`real/`)

MVP-002 ([#376](https://github.com/jaypetez/stride/issues/376)) fills `real/` by
running the probe against a real account:

```sh
GARMIN_PERSONAL_API_KEY=… node scripts/garmin/probe.mjs
```

The probe does three things:
- It writes the same file names, plus `_manifest.json` (statuses, rate-limit
  headers, token claim names and lifetime), `activity-file.meta.json` and
  `token-error.json`.
- It sanitizes every body: ids, GPS, names, emails, serials and token-like
  strings.
- It never writes the key or the token.

**Review every file by eye before committing it.** Where the real and synthetic
shapes disagree, the real shape wins: update the synthetic files and the doc.
