# Garmin Connect Personal API — what Stride uses

Spike for [#375](https://github.com/jaypetez/stride/issues/375) (MVP-001), following
[ADR 0010](../adr/0010-solo-garmin-mvp.md). The Kotlin client in `android/core/garmin`
is written from **this page**. Researched **2026-11-05**.

Each fact is tagged:

- **[V]** Verified first-hand in Garmin's own material: the Personal API page, the
  FAQ, or the OpenAPI document embedded in Garmin's Swagger page (see
  [Sources](#sources)).
- **[C]** Community-observed. Two independent open-source Personal API clients
  agree on it, or one client documents it from live calls. Not confirmed by Garmin.
- **[A]** Assumed by Stride. Nobody has confirmed it. MVP-002 ([#376](https://github.com/jaypetez/stride/issues/376))
  must confirm it with `scripts/garmin/probe.mjs` against a real account.

We have **not** called the API with a real key yet. Every response shape below
comes from Garmin's OpenAPI schema [V], corrected where the community saw the
live API differ [C].

## Terms of use (read this first)

- **Personal use only [V].** The FAQ says the Personal API is for individual users
  accessing their own Garmin Connect data. It is "not intended for commercial
  applications serving multiple end users". Integrations for multiple users or
  businesses belong to the separate Connect Developer Program (Starter or
  Enterprise tiers).
  - Stride's MVP fits this. Each person pastes their **own** key, and their
    phone calls Garmin directly. Stride runs no server and never sees the data.
  - A hosted or group product would **not** fit and needs the Starter tier
    (ADR 0006/0010).
- **AI clause (reported, not verified).** Reports say Garmin's developer terms bar
  using Garmin data to "create or maintain any AI model, AI system or AI generated
  knowledge base".
  - **We have not read this clause first-hand.** The Personal API terms are only
    shown when you create a key in Garmin Connect. The public Developer Program
    Agreement PDF we fetched (2023 template) gave us no readable text.
  - Third-party commentary (Sahha, Terra, 2026) reads the revised business
    agreement this way: training or fine-tuning models on Garmin data is barred,
    while running an existing model on one user's data, for that user, with
    consent, is tolerated.
  - **Stride's posture:**
    - The AI coach stays **off by default** and uses bring-your-own Anthropic key.
    - It receives only Stride-computed facts, never raw Garmin payloads.
    - Nothing is persisted outside the device, and nothing trains a model.
    - The UI shows the Garmin-terms caveat.
  - Re-check this when a maintainer reads the terms shown at key creation.
- Strava-style attribution rules don't apply here. Garmin's own brand guidelines
  do (the Developer API Brand Guidelines PDF). Show "Garmin Connect" as the source
  of synced data.

## Base URL and auth

| Item | Value | |
|---|---|---|
| Base URL (global) | `https://personal.connectapi.garmin.com` | [V] OpenAPI `servers[0]` |
| Base URL (China accounts) | `https://personal.connectapi.garmin.cn` | [V] OpenAPI `servers[1]`. Not supported by the MVP |
| Key creation page | `https://connect.garmin.com/app/settings/personalApiToken` | [V] |
| Data request auth | `Authorization: Bearer <accessToken>` | [C] Both clients send this. The OpenAPI document declares no security scheme |
| Accept | `application/json`. For FIT download, `application/x-zip-compressed` | [C] The download answers **406** to `application/octet-stream` |

The Kotlin client pins `BASE_URL = "https://personal.connectapi.garmin.com"`, with
every path below relative to it. It should also send a `User-Agent` such as
`Stride-Android/<version>` [A].

### Token exchange

```
POST https://personal.connectapi.garmin.com/personalapitoken-service/personal-api/personal-api-key/token
Authorization: ApiKey <personal-api-key>
Accept: */*
(no body)
```

- **[V]** Endpoint, method and header format come from the Personal API page.
- **[V]** The token lives **24 hours, or until the key expires, whichever comes
  first**. There are **no refresh tokens**: you POST the key again. Deleting the
  key revokes its tokens.
- **[V]** The key is valid for 30, 90 or 180 days or 1 year, and it is shown
  once. Garmin emails a reminder about a week before it expires.
- **[C] Response body: the raw access token as text, a JWT, not a JSON object.**
  Both clients read the body as a string.
  - The PHP SDK `trim()`s it.
  - The Python SDK strips whitespace and one layer of surrounding `"` quotes.
  - **Kotlin client rule:** read the body as text, `trim()`, and strip one pair of
    surrounding `"` if present. If the result starts with `{`, parse it as JSON
    and read `access_token` (or `accessToken`) as a defensive fallback [A].
- **[C] Expiry: the JWT `exp` claim**, in epoch seconds. Decode the payload
  segment (base64url) and read `exp`. Neither client got an `expires_in`, so the
  Kotlin client must not expect one.
  - If `exp` is missing, assume `now + 24h` [C, Python fallback].
  - Re-exchange about 5 minutes before `exp` [C, Python uses 5 min, PHP 60 s].
- **[C] Errors:**
  - `401` or `403` means the key is invalid, expired, deleted or mistyped. Tell
    the user to create a new key; don't retry.
  - Other `4xx`/`5xx` are generic API errors.
  - We have no confirmed error-body shape for the token endpoint [A]. Treat it
    as opaque, and **never log it verbatim** because it could echo the key.
- **Data endpoint `401` [C]:** the token is invalid or expired, or the key was
  revoked. Re-exchange the key **once** and retry. If that still fails with
  `401`, the key is dead: mark it invalid and ask the user for a new one.

Stored-secret rules (ADR 0010):
- The key lives in DataStore + Tink.
- The token may be cached in memory, or encrypted the same way.
- Neither is ever logged. The OkHttp logging interceptor must redact
  `Authorization`.

### Error bodies [C]

Most failures carry an **empty body or `{}`**. Some carry this JSON:

```json
{ "error": "NotAcceptableException", "clientMessage": "…", "message": "…", "errorId": "…" }
```

Every field is optional. Show `clientMessage ?: message ?: error`, and add
`errorId` when it is present. Fixtures: `error-401.json` (empty) and
`error-400.json` (ErrorBody).

### Rate limits and retries

- **[V]** Garmin publishes no rate limit for the Personal API. The FAQ and the
  OpenAPI document are silent.
- **[C]** One client says Garmin allows a key **at most about 100 requests a
  minute**. It retries `408, 429, 500, 502, 503, 504` with exponential backoff
  (0.5 s doubling, capped at 8 s, ±25 % jitter, at most 2 retries). It honors
  `Retry-After` (in seconds or as an HTTP date) when the wait is 60 s or less.
- **Stride default [A]:**
  - Do at most 1 request in flight per account. Cap at 60 requests a minute.
  - Use the retry policy above.
  - Treat `429` with no `Retry-After` as "back off 60 s".
  - The probe records any `*rate*`/`*limit*`/`Retry-After` response headers into
    `real/_manifest.json`, so MVP-002 can confirm the real limits.

## Range limits and paging

All ranged endpoints take **calendar dates `YYYY-MM-DD`** in the user's local
calendar, inclusive at both ends. None takes epoch seconds [V]. Some take dates as
query params, others as path segments. See each endpoint below.

| Endpoint | Garmin-documented max | Community-observed | **Stride window** |
|---|---|---|---|
| Activity search | no date limit; `limit` defaults to 99 | `limit` > ~45 → **HTTP 500** [C] | page with `limit=20` |
| Daily summaries v2 | 1 year [V] | > ~90 days → HTTP 500; clients use 31 [C] | **28 days** |
| Sleep summaries | 31 days [V] | 31 [C] | **28 days** |
| Body Battery reports | 31 days [V] | 31 [C] | **28 days** |
| Stress daily | 28 days [V] | 28 [C] | **28 days** |
| HRV daily | not stated | 365 days [C] | **28 days** |
| Weight range | not stated | about 1 year [C] | **28 days** |

The rule for the Kotlin client: **every ranged call spans ≤ 28 days.** Split
longer spans into consecutive windows (oldest first for backfill) and concatenate
the results. An oversized range typically fails with `400` (or `500` for daily
summaries) [C].

Activity paging works like this [V]:
- `start` is a zero-based offset and `limit` is the page size.
- Results sort by `startLocal` descending by default.
- Request `start=0,20,40…` until a page returns fewer than `limit` items. An
  empty array means no results.
- Always send an explicit `limit` (≤ 45 [C]).

## Endpoints Stride uses

All are `GET` against the base URL, with Bearer auth. "Empty" means the HTTP
status or body when there's no data.

### 1. Activity list: `GET /activitylist-service/activities/search/activities` [V]

| Query param | Value Stride sends |
|---|---|
| `startDate` | `YYYY-MM-DD`. Inclusive, against the activity's **local** start date |
| `endDate` | `YYYY-MM-DD`. Inclusive |
| `start` | offset, `0`, `20`, … |
| `limit` | `20` |
| `sortOrder` | `desc` (the default) |
| `excludeChildren` | `true` (the default, which hides multisport legs) |

Optional: `activityType=running` keeps only parent type `running` and its subtypes
`trail_running`, `treadmill_running`, `track_running`. **Stride does not filter**,
because it needs every activity for load. Empty means `200 []`.

The response is a `SummarizedActivityDTO[]` (a JSON array). Fields Stride reads:

| Field | Type / unit |
|---|---|
| `activityId` | int64 |
| `activityName` | string (PII) |
| `startTimeLocal` | string `"yyyy-MM-dd HH:mm:ss"`, local, no offset |
| `startTimeGMT` | string `"yyyy-MM-dd HH:mm:ss"`, UTC, no `Z` |
| `beginTimestamp` | int64 **epoch ms** (UTC). Prefer this for instants |
| `activityType` | `{ typeId:int, typeKey:string, parentTypeId:int }`. `parentTypeId` = 17 marks a top-level type [V]. Running means `typeKey` is `running`, or `parentTypeId` is running's `typeId` (1 in Garmin Connect [A]). Compare on `typeKey` |
| `eventType` | `{ typeId, typeKey }`. `typeKey` is one of `race`, `training`, `recreation`, `fitness`, `specialEvent`, `uncategorized` |
| `distance` | double, **meters** |
| `duration` | double, **seconds** (timer time) |
| `elapsedDuration` | double, seconds, including pauses |
| `movingDuration` | double, seconds |
| `elevationGain`, `elevationLoss` | double, meters |
| `averageSpeed`, `maxSpeed` | double, **m/s** |
| `avgGradeAdjustedSpeed` | double, m/s |
| `averageHR`, `maxHR` | double, bpm |
| `averageRunningCadenceInStepsPerMinute`, `maxRunningCadenceInStepsPerMinute` | double, spm |
| `calories` | double, kcal |
| `avgPower`, `normPower` | double, W |
| `aerobicTrainingEffect`, `anaerobicTrainingEffect` | double, 0–5 |
| `activityTrainingLoad` | double (Garmin's EPOC load) |
| `vO2MaxValue` | double, **often null**. See [Gaps](#known-gaps) |
| `lactateThresholdBpm` | double, bpm |
| `hrTimeInZone_1` … `hrTimeInZone_5` | double, seconds |
| `lapCount` | int |
| `steps` | int |
| `manufacturer` | string |
| `isManualActivity`, `isParent`, `parentId` | bool / bool / int64 |
| `startLatitude`, `startLongitude`, `endLatitude`, `endLongitude` | double, degrees (**PII, Stride does not store**) |
| `ownerId`, `ownerDisplayName`, `ownerFullName`, `ownerProfileImageUrl*` | PII, ignored |

The live response contains **extra undocumented fields** [C], such as `endTimeGMT`,
`activityUUID`, `parent`, `pr`, `autoCalcCalories`, `elevationCorrected`,
`qualifyingDive`. Configure kotlinx.serialization with
`ignoreUnknownKeys = true` and make every field nullable with a default.

### 2. Activity detail: `GET /activity-service/activity/{activityId}` [V]

Empty or failure responses: `404` for an unknown id, `403` for someone else's
activity.

The response is an `ActivitySummaryDTO` object:

| Field | Type / unit |
|---|---|
| `activityId` | int64 |
| `activityUUID` | **object** `{ "uuid": string }` in the live API [C]. The schema says string |
| `activityName`, `description`, `locationName` | string (PII) |
| `userProfileId` | int64 (PII) |
| `activityTypeDTO` | `{ typeId, typeKey, parentTypeId }`. Note the **`DTO` suffix**, unlike the list |
| `eventTypeDTO` | `{ typeId, typeKey }` |
| `timeZoneUnitDTO` | `{ unitId:int64, unitKey:string, timeZone:string }`. IANA, for example `"Europe/Berlin"` |
| `metadataDTO.lapCount` | int |
| `metadataDTO.fileFormat` | `{ formatId:int, formatKey:string }`. 7 is `fit` |
| `metadataDTO.manualActivity` | bool |
| `metadataDTO.sensors[]` | `{ manufacturer, serialNumber:int64 (PII), sku, productName, sourceType, antplusDeviceType, … }` |
| `metadataDTO.userInfoDto` | `{ userProfilePk, displayname, fullname, profileImageUrl* }` (PII) |
| `summaryDTO.startTimeLocal`, `summaryDTO.startTimeGMT` | string ISO-8601 **without offset**, for example `"2026-10-30T07:02:11.0"` |
| `summaryDTO.distance` | double, m |
| `summaryDTO.duration`, `summaryDTO.movingDuration`, `summaryDTO.elapsedDuration` | double, s |
| `summaryDTO.averageSpeed`, `summaryDTO.averageMovingSpeed`, `summaryDTO.maxSpeed`, `summaryDTO.avgGradeAdjustedSpeed` | double, m/s |
| `summaryDTO.averageHR`, `summaryDTO.maxHR`, `summaryDTO.minHR` | double, bpm |
| `summaryDTO.averageRunCadence`, `summaryDTO.maxRunCadence` | double, spm. Note the different name from the list endpoint |
| `summaryDTO.elevationGain`, `summaryDTO.elevationLoss` | double, m |
| `summaryDTO.calories` | double, kcal |
| `summaryDTO.trainingEffect` | double. The aerobic TE |
| `summaryDTO.anaerobicTrainingEffect`, `summaryDTO.activityTrainingLoad` | double |
| `summaryDTO.groundContactTime` | double, ms |
| `summaryDTO.strideLength` | double, **cm** |
| `summaryDTO.verticalOscillation` | double, cm |
| `summaryDTO.lactateThresholdHeartRate` | double, bpm |
| `summaryDTO.recoveryHeartRate` | int, bpm |
| `summaryDTO.directWorkoutRpe`, `summaryDTO.directWorkoutFeel` | int. User-entered RPE and feel |
| `summaryDTO.startLatitude`, `summaryDTO.startLongitude`, `summaryDTO.endLatitude`, `summaryDTO.endLongitude` | degrees (PII) |
| `splitSummaries[]` | `{ splitType, noOfSplits, distance, duration, averageSpeed, … }`. Aggregates per split *type*, **not laps** |

**There is no JSON lap list or per-second stream endpoint** [V], so laps and streams
come from the FIT file (section 3).

### 3. Activity file (FIT): `GET /download-service/files/activity/{activityId}` [V]

- **[V]** Downloads "an activity file in its original format".
- **[C]** Send `Accept: application/x-zip-compressed`. The response is a **ZIP**
  archive holding one file, usually `<activityId>_ACTIVITY.fit` (or `.tcx`/`.gpx`
  for uploads that weren't FIT; check `metadataDTO.fileFormat`).
- Guard against zip bombs: one community client refuses more than 512 MB.
- **[C, unconfirmed]** The5krunner reports that Garmin strips some
  Firstbeat-derived fields from API-delivered FITs, such as VO2max, recovery time
  and performance condition. Laps, records (HR, speed, cadence, power) and GPS
  should remain.
- The probe records only the ZIP's metadata (size, entry names), never the bytes,
  because a FIT holds GPS.

### 4. Daily summaries (resting HR, stress, Body Battery, steps): `GET /usersummary-service/v2/dailysummaries` [V]

| Query param | Value |
|---|---|
| `startDate` | `YYYY-MM-DD` |
| `endDate` | `YYYY-MM-DD` (≤ 28-day window) |

The response is **an object wrapping the list**:
`{ "data": UserDailySummaryV2DTO[] }`.

The schema's own conventions [V]:
- Timestamps are ISO-8601 **without zone**.
- **Durations are integer milliseconds.**
- Decimals are rounded to 2 places.

| Field | Type / unit |
|---|---|
| `calendarDate` | `"YYYY-MM-DD"` |
| `uuid`, `userProfilePk` | ids (PII) |
| `source` | `garmin` or `fitbit` |
| `includesWellnessData` | bool |
| `movement.steps` | `{ value:int, goal:int, distanceInMeters:double }` |
| `heartRate` | `{ restingValue:int, minValue:int, maxValue:int }`, bpm. **`restingValue` is the day's RHR** |
| `stress` | `{ avgLevel:int, maxLevel:int, qualifier:string }`. Level 0–100. Also `*DurationInMillis:int` (ms), `*Proportion:double` (0–1) |
| `bodyBattery` | `{ minValue:int, maxValue:int, chargedValue:int, drainedValue:int, latestValue:int, endOfDayValue:int }`, scale 5–100 |
| `intensityMinutes` | `{ moderate:int, vigorous:int, goal:int }`, minutes. `goal` is weekly |
| `calories` | `{ burnedActive, burnedResting, burnedTotal }`, int kcal |
| `respiration` | `{ avgValue, minValue, maxValue }`, breaths/min |
| `bodyBattery.activityEvents[]` | contain `deviceId` and `activityId` (ids) |

A day with no data may be missing from `data` or have mostly null fields [A].

### 5. Sleep: `GET /sleep-service/sleep/dailySleepSummariesByDate` [V]

| Query param | Value |
|---|---|
| `startDate` | `YYYY-MM-DD` |
| `endDate` | `YYYY-MM-DD` (≤ 28 days. Garmin documents a 31-day maximum) |

The response is a `DailySleepDTO[]` (a bare array):

| Field | Type / unit |
|---|---|
| `calendarDate` | `"YYYY-MM-DD"`. The wake-up day |
| `id` | int64 |
| `userProfilePK` | int64, PII. Note the capital `PK` |
| `sleepTimeSeconds` | int, **s**, excluding naps |
| `napTimeSeconds` | int, s |
| `deepSleepSeconds`, `lightSleepSeconds`, `remSleepSeconds`, `awakeSleepSeconds`, `unmeasurableSleepSeconds` | int, s |
| `sleepStartTimestampGMT`, `sleepEndTimestampGMT` | **int64 epoch ms** in the live API [C]. The schema says `date-time` "in ms" |
| `sleepStartTimestampLocal`, `sleepEndTimestampLocal` | int64. Epoch ms *shifted to local wall time* [C] |
| `avgHeartRate` | double, bpm |
| `averageRespirationValue` | double, br/min |
| `avgSleepStress` | double |
| `awakeCount` | int |
| `averageSpO2Value` | double, % |
| `sleepScores.overall.value` | int, 0–100 |
| `sleepScores.overall.qualifierKey` | string (`EXCELLENT`, `GOOD`, `FAIR`, `POOR`) |
| `sleepWindowConfirmed` | bool |
| `sleepFromDevice` | bool |

`sleepScores` is an untyped object in the schema [V]. Its keys `overall`,
`totalDuration`, `stress`, `awakeCount`, `remPercentage`, `lightPercentage`,
`deepPercentage` and `restlessness` were observed live [C]. A night with no sleep
recorded may still appear, with zero or null seconds [A].

### 6. HRV: `GET /hrv-service/hrv/daily/{fromDate}/{toDate}` [V]

The dates are **path** segments, `YYYY-MM-DD`. Empty means **`204 No Content`**
[V], so handle an empty body.

The response is a `HrvDailySummaryDTO` object: `{ "hrvSummaries": HrvSummaryDTO[], "userProfilePk": int64 }`.

| Field | Type / unit |
|---|---|
| `calendarDate` | `"YYYY-MM-DD"` |
| `lastNightAvg` | int, **ms** (RMSSD). Overnight average |
| `lastNight5MinHigh` | int, ms |
| `weeklyAvg` | int, ms, over 7 days |
| `baseline` | `{ lowUpper:int, balancedLow:int, balancedUpper:int, markerValue:float }`, ms |
| `status` | `NONE`, `POOR`, `LOW`, `UNBALANCED`, `BALANCED` or `INVALID` |
| `feedbackPhrase` | string |
| `createTimeStamp` | ISO string without offset |

The unit "ms" is not stated in the schema. It follows Garmin's HRV feature (RMSSD
in ms) [A].

### 7. Stress (daily): `GET /usersummary-service/stats/stress/daily/{startDate}/{endDate}` [V]

The dates are **path** segments, with a **28-day maximum** [V].

The response is a `MultiValueWellnessChartItemDTO[]`, items of shape
`{ calendarDate:"YYYY-MM-DD", values:{ overallStressLevel:int (0–100), highStressDuration:int, mediumStressDuration:int, lowStressDuration:int, restStressDuration:int } }`.
The durations are in **seconds** [V] and may be `null` [C].

This duplicates part of `dailysummaries.stress`. Stride uses daily summaries as
its primary source. This endpoint is a fallback or cross-check.

### 8. Resting HR (single day): `GET /wellness-service/wellness/dailyHeartRate?date=YYYY-MM-DD` [V]

The response is a `DailyHeartRateDTO`:
- `calendarDate`, a string [C] (the schema says an object)
- `restingHeartRate`: int, bpm
- `lastSevenDaysAvgRestingHeartRate`: int, bpm
- `minHeartRate`, `maxHeartRate`: int, bpm
- `heartRateValues`: `[[epochMs, bpm|null], …]`, with
  `heartRateValueDescriptors = [{index:0,key:"timestamp"},{index:1,key:"heartrate"}]`
- `userProfilePK` (PII)

Stride reads RHR from **daily summaries** (`heartRate.restingValue`). This
endpoint is used only when the summary value is null.

### 9. Body Battery: `GET /wellness-service/wellness/bodyBattery/reports/daily` [V]

Query: `startDate`, `endDate` (`YYYY-MM-DD`, with a 31-day maximum, ≤ 28 for
Stride). `chunks` is optional.

The response is a `BodyBatteryDailyReportDTO[]`:
- `date` (`YYYY-MM-DD`)
- `charged`, `drained`: int, 0–100
- `startTimestampGMT`, `endTimestampGMT`: ISO strings without offset
- `bodyBatteryValuesArray`: `[[epochMs, level], …]`, described by
  `bodyBatteryValueDescriptorDTOList`
- `bodyBatteryActivityEvent[]`: `{ eventType, eventStartTimeGmt, durationInMilliseconds, bodyBatteryImpact }`

The MVP needs only the daily min/max/charged/drained, and daily summaries already
have them. This endpoint is **optional** and is used for the intraday chart.

### 10. Weight / body composition: `GET /weight-service/weight/range/{startDate}/{endDate}?includeAll=false` [V]

The dates are path segments.

The response is a `WeightRangeDTO`:
- `dailyWeightSummaries[]`: `{ summaryDate:"YYYY-MM-DD", numOfWeightEntries, minWeight, maxWeight, latestWeight:DateWeightDTO, allWeightMetrics:DateWeightDTO[] }`
- `totalAverage`, `previousDateWeight`, `nextDateWeight`

`DateWeightDTO` fields:
- `samplePk` (int64, id [C])
- `date`, `timestampGMT`: **int64 epoch ms** live [C]. The schema says date-time strings
- `calendarDate`
- `weight`: **grams** live [C]. The schema says kilograms
- `bmi`
- `bodyFat`, `bodyWater`: %
- `boneMass`, `muscleMass`: grams [C]
- `sourceType` (for example `INDEX_SCALE`, `MANUAL`)
- `weightDelta`

**Unit rule for Kotlin:** treat `weight` as grams. If a value is below 1000,
assume kilograms and log a warning [A].

`GET /weight-service/weight/all/{startDate}/{endDate}` exists [V], but one client
reports it **returns 400 for every input** [C]. Don't use it.

### Not used by the MVP (exist in the spec [V])

- `GET /activity-service/activity/{activityId}/workouts`
- `GET /biometric-service/heartRateZones/v2` (user HR zones. Possibly useful later)
- `GET /fitnessage-service/fitnessage/v2/{calendarDate}`
- `GET /usersummary-service/usersummary/daily?calendarDate=`
- `GET /usersummary-service/stats/steps/daily/{s}/{e}` (28 days)
- `GET /usersummary-service/stats/hydration/daily/{s}/{e}` (31 days)
- `GET /sleep-service/sleep/dailySleep?date=`
- `GET /hrv-service/hrv/{calendarDate}` (intraday HRV, `204` when empty)
- `GET /wellness-service/wellness/daily/spo2acclimation/{calendarDate}`
- `GET /wellnessactivity-service/activity/summary/range/{fromDate}/{untilDate}`
- `GET /goal-service/goal/user/effective/weightgoal/{s}/{e}`
- `GET /nutrition-service/food/logs/{date}`
- `GET /userprofile-service/userprofile/settings`
- `GET /periodichealth-service/menstrualcycle/summary/{calendarDate}`
- `GET /periodichealth-service/menstrualcycle/dayview/{calendarDate}` (Women's Health)

In total, the spec has **26 GET endpoints** plus the token POST [V].

## Stride needs → endpoint

| Stride need (Room entity / feature) | Endpoint | Fields | Fixture |
|---|---|---|---|
| Key → token, expiry | `POST …/personal-api-key/token` | body = JWT, `exp` claim | `token.txt` |
| Activity list (incremental sync, load) | `GET /activitylist-service/activities/search/activities` | `activityId`, `activityType.typeKey`, `beginTimestamp`, `startTimeLocal`, `distance`, `duration`, `movingDuration`, `averageHR`, `maxHR`, `elevationGain`, `averageSpeed`, `activityTrainingLoad` | `activities.json` |
| Activity detail (time zone, cadence, RPE) | `GET /activity-service/activity/{id}` | `timeZoneUnitDTO.timeZone`, `summaryDTO.*`, `metadataDTO.lapCount` | `activity-detail.json` |
| Laps and streams (HR/pace/cadence) | `GET /download-service/files/activity/{id}` | ZIP → FIT `lap`/`record` messages | none (binary; MVP-002) |
| Resting HR | `GET /usersummary-service/v2/dailysummaries` (fallback: `/wellness-service/wellness/dailyHeartRate`) | `heartRate.restingValue` (`restingHeartRate`) | `dailies.json`, `daily-heart-rate.json` |
| Stress | daily summaries (fallback: `/usersummary-service/stats/stress/daily/{s}/{e}`) | `stress.avgLevel`, `stress.maxLevel` (`values.overallStressLevel`) | `dailies.json`, `stress.json` |
| Body Battery | daily summaries (detail: `/wellness-service/wellness/bodyBattery/reports/daily`) | `bodyBattery.maxValue`, `minValue`, `chargedValue`, `drainedValue` | `dailies.json`, `body-battery.json` |
| Steps, intensity minutes | daily summaries | `movement.steps.value`, `intensityMinutes.*` | `dailies.json` |
| Sleep | `GET /sleep-service/sleep/dailySleepSummariesByDate` | `sleepTimeSeconds`, stage seconds, `sleepScores.overall.value`, `avgHeartRate` | `sleeps.json` |
| HRV | `GET /hrv-service/hrv/daily/{s}/{e}` | `lastNightAvg`, `weeklyAvg`, `baseline`, `status` | `hrv.json` |
| Weight | `GET /weight-service/weight/range/{s}/{e}` | `dailyWeightSummaries[].latestWeight.weight` (g) | `body-comps.json` |
| Error handling | any | empty body or `{error, clientMessage, message, errorId}` | `error-401.json`, `error-400.json` |

## Known gaps

- **Not exposed by the Personal API** (ADR 0010; the spec has no endpoint for
  them [V]):
  - VO2max as a daily metric
  - training status
  - training readiness
  - personal records
  - race predictions
  - Courses and Training (workout push)

  Stride computes VO2max estimate, load and form itself (`core:science`).
  - `SummarizedActivityDTO.vO2MaxValue` exists in the schema [V]. Treat it as
    **opportunistic** and expect `null` [A]. Never depend on it.
  - `isPR` / `metadataDTO.personalRecord` are flags only.
- **No JSON laps or streams.** Laps, splits and time series come only from FIT
  parsing.
- **API-delivered FIT may be stripped** of some Firstbeat fields [C, unconfirmed].
- **Schema vs live drift** [C]:
  - `activityUUID` is an object.
  - Sleep and weight timestamps are epoch-ms integers.
  - `weight` is in grams.
  - `dailyHeartRate.calendarDate` is a string.
  - There are undocumented extra fields.

  Parse leniently: nullable fields, `ignoreUnknownKeys`, and accept a number
  *or* a string for timestamps.
- **No published rate limit** (see above).
- **No webhooks or push.** It's pull-only [V], so sync runs on a WorkManager
  schedule.
- **China (`.cn`) accounts** are not supported by the MVP.
- **Token response shape**, **error bodies** and **rate-limit headers** are
  community-observed or assumed. MVP-002 confirms them with the probe.

## Probe: `scripts/garmin/probe.mjs`

```sh
GARMIN_PERSONAL_API_KEY=… node scripts/garmin/probe.mjs            # last 14 days
GARMIN_PROBE_DAYS=28 GARMIN_PERSONAL_API_KEY=… node scripts/garmin/probe.mjs
```

The probe:
- exchanges the key
- calls every endpoint in the mapping table above over a recent window
- runs every body through `sanitize()`
- writes JSON to `android/core/garmin/src/test/resources/fixtures/real/`, plus
  `_manifest.json` (status, content type, rate-limit headers, token claim
  *names* and lifetime)

It never prints or writes the key or the token. Error messages are scrubbed, and
the output is checked for both before anything is written. For the FIT download
it records only the ZIP's size and entry names.

**Review the files by eye before committing them**, because the sanitizer is a
safety net, not a guarantee. The sanitizer's tests run in CI:
`pnpm test:scripts`.

## Sources

All were accessed on **2026-11-05**.

- Garmin, *Personal API*: https://developer.garmin.com/gc-developer-program/personal/personal-api — key creation and token exchange [V]
- Garmin, *Personal API FAQ*: https://developer.garmin.com/gc-developer-program/personal/personal-faq/ — 24 h token, no refresh, key lifetimes, personal-use statement [V]
- Garmin, *Personal API Swagger Docs*: https://developer.garmin.com/gc-developer-program/personal/swagger-docs/ — the OpenAPI 3 document ("Garmin Connect Personal API" 1.0.0) is embedded in the page bundle `component---src-pages-gc-developer-program-personal-swagger-docs-js-715115a6210d45afc282.js`. We extracted it and read it for servers, all 26 paths, parameters, range limits and schemas [V]
- `gcpapi-sync` (MIT), a Python client generated from the same OpenAPI document and corrected against live calls: https://github.com/thomascamminady/gcpapi-sync (`_constants.py`, `_auth.py`, `_core.py`, `_endpoints.py`, `tests/fixtures/*`) — range limits seen in practice, page-size 500, 100 req/min, raw-JWT token body, ZIP `Accept`, schema drift [C]
- `tomrichmond/garmin-personal-api` (PHP SDK): https://github.com/tomrichmond/garmin-personal-api (`src/Auth/TokenManager.php`) — raw-JWT token body, `exp` claim [C]
- `garmin-personal-mcp` (PyPI): https://pypi.org/project/garmin-personal-mcp/ — 28/31-day windows, values in grams/meters/seconds [C]
- the5krunner, *Garmin personal API gives athletes their own data back* (2026-10-22): https://the5krunner.com/2026/10/22/garmin-personal-api/ — stripped Firstbeat fields in API FITs [C, unconfirmed]
- Sahha, *Health API AI restrictions* (2026-08-28): https://sahha.ai/blog/health-api-ai-restrictions/, and Terra: https://tryterra.co/blog/garmin-connect-developer-program-pause — commentary on the AI clause [reported]
- Garmin Connect Developer Program Agreement (template PDF, 2023 metadata): https://developerportal.garmin.com/sites/default/files/Garmin%20Connect%20Developer%20Program%20Agreement.pdf — fetched, but no readable text; we could not quote the AI clause
