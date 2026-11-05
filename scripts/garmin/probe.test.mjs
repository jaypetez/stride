// Sanitizer tests for scripts/garmin/probe.mjs. Offline: no network, no key.
//   node --test scripts/garmin/probe.test.mjs
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  assertNoSecrets,
  createSanitizer,
  decodeJwtClaims,
  describeToken,
  MAX_WINDOW_DAYS,
  parseTokenBody,
  plan,
  REDACTED,
  REDACTED_EMAIL,
  REDACTED_TOKEN,
  REDACTED_URL,
  sanitize,
  scrubSecrets,
  window,
  zipEntries,
} from './probe.mjs';

const FAKE_KEY = 'gpak_live_3f9a8c7e6d5b4a39281706f5e4d3c2b1a0ffee77';
const fakeJwt = (claims) =>
  [
    Buffer.from('{"alg":"RS256","typ":"JWT"}').toString('base64url'),
    Buffer.from(JSON.stringify(claims)).toString('base64url'),
    'c2lnbmF0dXJlLXNpZ25hdHVyZS1zaWduYXR1cmU',
  ].join('.');
const FAKE_TOKEN = fakeJwt({ sub: 'u-1', iat: 1_790_000_000, exp: 1_790_086_400 });

// Shapes captured from the Garmin OpenAPI schema / community fixtures, with
// realistic-looking (but invented) personal data.
const capturedActivity = () => ({
  activityId: 21_544_301_877,
  activityUUID: { uuid: '6f1c2b3a-9d8e-4f70-a1b2-c3d4e5f60718' },
  activityName: 'Berlin Tempelhof Running',
  description: 'Easy loop with Anna',
  locationName: 'Berlin',
  startTimeLocal: '2026-10-30 07:02:11',
  startTimeGMT: '2026-10-30 06:02:11',
  beginTimestamp: 1_793_340_131_000,
  activityType: { typeId: 1, typeKey: 'running', parentTypeId: 17 },
  eventType: { typeId: 9, typeKey: 'uncategorized' },
  distance: 10_012.4,
  duration: 3_120.5,
  averageHR: 148,
  startLatitude: 52.473_1,
  startLongitude: 13.401_9,
  endLatitude: 52.4735,
  endLongitude: 13.4022,
  ownerId: 81_234_567,
  ownerDisplayName: 'jane.runner',
  ownerFullName: 'Jane Runner',
  ownerProfileImageUrlSmall: 'https://s3.amazonaws.com/garmin-connect-prod/profile_images/abc.png',
  activityLikeFullNames: ['Bob Friend'],
  comments: [{ commentId: 55, body: 'nice', authorEmail: 'bob@friend.de' }],
  summarizedDiveInfo: { summarizedDiveGases: [] },
  splitSummaries: [{ splitType: 'INTERVAL_ACTIVE', distance: 1000, noOfSplits: 1 }],
  manufacturer: 'GARMIN',
});

const capturedDetail = () => ({
  activityId: 21_544_301_877,
  userProfileId: 81_234_567,
  activityTypeDTO: { typeId: 1, typeKey: 'running', parentTypeId: 17 },
  timeZoneUnitDTO: { unitId: 124, unitKey: 'Europe/Berlin', timeZone: 'Europe/Berlin' },
  metadataDTO: {
    fileFormat: { formatId: 7, formatKey: 'fit' },
    childIds: [21_544_301_878, 21_544_301_879],
    userInfoDto: {
      userProfilePk: 81_234_567,
      displayname: 'jane.runner',
      fullname: 'Jane Runner',
      profileImageUrlLarge: 'https://example.com/large.png',
    },
    sensors: [{ manufacturer: 'GARMIN', serialNumber: 3_391_234_567, productName: 'HRM-Pro' }],
    deviceId: 3_998_877_665,
    unitId: 3_998_877_665,
  },
  summaryDTO: {
    startTimeLocal: '2026-10-30T07:02:11.0',
    startingLatitudeInDegree: 52.4731,
    startingLongitudeInDegree: 13.4019,
    distance: 10_012.4,
  },
  geoPolylineDTO: { polyline: [{ lat: 52.1, lon: 13.1 }] },
});

const capturedSleep = () => [
  {
    id: 1_793_300_000_000,
    userProfilePK: 81_234_567,
    calendarDate: '2026-10-30',
    sleepTimeSeconds: 27_000,
    sleepStartTimestampGMT: 1_793_300_000_000,
    sleepQualityTypePK: 'good',
    userNote: 'woke up at 3am, call mum',
    sleepScores: { overall: { value: 81, qualifierKey: 'GOOD' } },
  },
];

const capturedDailies = () => ({
  data: [
    {
      uuid: '0e1d2c3b-4a59-4687-9a0b-1c2d3e4f5a6b',
      userProfilePk: 81_234_567,
      calendarDate: '2026-10-30',
      heartRate: { restingValue: 47, minValue: 44, maxValue: 171 },
      bodyBattery: {
        maxValue: 88,
        activityEvents: [
          { eventType: 'ACTIVITY', deviceId: 3_998_877_665, activityId: 21_544_301_877 },
        ],
      },
      summaryId: 'x7f3c2a1-1793300000',
      email: 'jane@runner.de',
      accessToken: FAKE_TOKEN,
    },
  ],
});

test('replaces ids with stable fake values, consistent across one sanitizer', () => {
  const s = createSanitizer();
  const list = s([capturedActivity()]);
  const detail = s(capturedDetail());
  const daily = s(capturedDailies());
  assert.notEqual(list[0].activityId, 21_544_301_877);
  assert.equal(typeof list[0].activityId, 'number');
  assert.equal(detail.activityId, list[0].activityId, 'same id in list and detail');
  assert.equal(daily.data[0].bodyBattery.activityEvents[0].activityId, list[0].activityId);
  assert.equal(detail.userProfileId, list[0].ownerId, 'owner id maps consistently');
  assert.equal(detail.metadataDTO.userInfoDto.userProfilePk, list[0].ownerId);
  assert.notEqual(detail.metadataDTO.childIds[0], 21_544_301_878);
  assert.equal(detail.metadataDTO.childIds.length, 2);
  assert.notEqual(detail.metadataDTO.deviceId, 3_998_877_665);
  assert.notEqual(detail.metadataDTO.unitId, 3_998_877_665);
  assert.notEqual(detail.metadataDTO.sensors[0].serialNumber, 3_391_234_567);
  assert.match(list[0].activityUUID.uuid, /^00000000-0000-4000-8000-\d{12}$/);
  assert.match(daily.data[0].uuid, /^00000000-0000-4000-8000-\d{12}$/);
  assert.notEqual(daily.data[0].summaryId, 'x7f3c2a1-1793300000');
});

test('is deterministic for the same input', () => {
  assert.deepEqual(sanitize(capturedDetail()), sanitize(capturedDetail()));
});

test('keeps enum-like type ids and the measurements the client needs', () => {
  const [a] = sanitize([capturedActivity()]);
  assert.deepEqual(a.activityType, { typeId: 1, typeKey: 'running', parentTypeId: 17 });
  assert.deepEqual(a.eventType, { typeId: 9, typeKey: 'uncategorized' });
  assert.equal(a.distance, 10_012.4);
  assert.equal(a.averageHR, 148);
  assert.equal(a.beginTimestamp, 1_793_340_131_000);
  assert.equal(a.startTimeLocal, '2026-10-30 07:02:11');
  assert.equal(a.manufacturer, 'GARMIN');
  assert.equal(a.splitSummaries[0].splitType, 'INTERVAL_ACTIVE');
  const d = sanitize(capturedDetail());
  assert.deepEqual(d.metadataDTO.fileFormat, { formatId: 7, formatKey: 'fit' });
  assert.equal(d.timeZoneUnitDTO.timeZone, 'Europe/Berlin');
  const [sl] = sanitize(capturedSleep());
  assert.equal(sl.sleepQualityTypePK, 'good');
  assert.equal(sl.sleepTimeSeconds, 27_000);
  assert.equal(sl.sleepScores.overall.value, 81);
});

test('removes GPS coordinates', () => {
  const [a] = sanitize([capturedActivity()]);
  for (const k of ['startLatitude', 'startLongitude', 'endLatitude', 'endLongitude']) {
    assert.equal(a[k], 0, k);
  }
  const d = sanitize(capturedDetail());
  assert.equal(d.summaryDTO.startingLatitudeInDegree, 0);
  assert.equal(d.summaryDTO.startingLongitudeInDegree, 0);
  assert.deepEqual(d.geoPolylineDTO.polyline, []);
  const text = JSON.stringify([a, d]);
  assert.ok(!text.includes('52.47') && !text.includes('13.40'));
});

test('removes names, emails, notes and profile URLs', () => {
  const [a] = sanitize([capturedActivity()]);
  assert.equal(a.activityName, REDACTED);
  assert.equal(a.description, REDACTED);
  assert.equal(a.locationName, REDACTED);
  assert.equal(a.ownerDisplayName, REDACTED);
  assert.equal(a.ownerFullName, REDACTED);
  assert.deepEqual(a.activityLikeFullNames, [REDACTED]);
  assert.equal(a.ownerProfileImageUrlSmall, REDACTED_URL);
  assert.equal(a.comments[0].authorEmail, REDACTED_EMAIL);
  const d = sanitize(capturedDetail());
  assert.equal(d.metadataDTO.userInfoDto.fullname, REDACTED);
  assert.equal(d.metadataDTO.userInfoDto.displayname, REDACTED);
  const [sl] = sanitize(capturedSleep());
  assert.equal(sl.userNote, REDACTED);
  const daily = sanitize(capturedDailies());
  assert.equal(daily.data[0].email, REDACTED_EMAIL);
  const text = JSON.stringify([a, d, sl, daily]);
  for (const pii of ['Jane', 'jane', 'Anna', 'Bob', 'Berlin Tempelhof', 'mum', 'amazonaws']) {
    assert.ok(!text.includes(pii), `${pii} leaked`);
  }
  assert.equal(sanitize({ note: 'mail me at a.b@c.org' }).note, `mail me at ${REDACTED_EMAIL}`);
});

test('the key and token never appear in sanitized output', () => {
  const secrets = [FAKE_KEY, FAKE_TOKEN];
  const hostile = {
    ...capturedDailies(),
    echo: `bad key ${FAKE_KEY}`,
    headerDump: `Authorization: Bearer ${FAKE_TOKEN}`,
    nested: [{ msg: `ApiKey ${FAKE_KEY}` }, FAKE_TOKEN],
    otherJwt: fakeJwt({ sub: 'someone-else' }),
    opaque: 'a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8',
    apiKey: 'short',
  };
  const out = JSON.stringify(sanitize(hostile, { secrets }));
  assert.ok(!out.includes(FAKE_KEY), 'key leaked');
  assert.ok(!out.includes(FAKE_TOKEN), 'token leaked');
  assert.ok(!out.includes('eyJ'), 'JWT-like string leaked');
  assert.ok(!out.includes('a1b2c3d4e5f6'), 'opaque token-like string leaked');
  assert.ok(out.includes(REDACTED_TOKEN));
  assert.doesNotThrow(() => assertNoSecrets(out, secrets));
  // And without being told the secrets, a JWT is still caught.
  assert.ok(!JSON.stringify(sanitize(hostile)).includes('eyJ'));
});

test('assertNoSecrets blocks a write that contains the key or a JWT', () => {
  assert.throws(() => assertNoSecrets(`{"x":"${FAKE_KEY}"}`, [FAKE_KEY]), /API key or token/);
  assert.throws(() => assertNoSecrets(`{"x":"${FAKE_TOKEN}"}`, []), /JWT/);
  assert.doesNotThrow(() => assertNoSecrets('{"x":1}', [FAKE_KEY]));
});

test('scrubSecrets removes the key and token from error messages', () => {
  const msg = scrubSecrets(`request with ApiKey ${FAKE_KEY} and Bearer ${FAKE_TOKEN} failed`, [
    FAKE_KEY,
  ]);
  assert.ok(!msg.includes(FAKE_KEY));
  assert.ok(!msg.includes(FAKE_TOKEN));
  assert.match(msg, /ApiKey REDACTED_TOKEN and Bearer REDACTED_TOKEN failed/);
});

test('parseTokenBody accepts raw, quoted and JSON bodies; describeToken hides values', () => {
  assert.deepEqual(parseTokenBody(` ${FAKE_TOKEN}\n`), { token: FAKE_TOKEN, kind: 'raw-text' });
  assert.equal(parseTokenBody(`"${FAKE_TOKEN}"`).token, FAKE_TOKEN);
  const j = parseTokenBody(JSON.stringify({ access_token: FAKE_TOKEN, expires_in: 86400 }));
  assert.equal(j.token, FAKE_TOKEN);
  assert.deepEqual(j.jsonKeys, ['access_token', 'expires_in']);
  assert.equal(decodeJwtClaims(FAKE_TOKEN).exp, 1_790_086_400);
  assert.equal(decodeJwtClaims('not-a-jwt'), null);
  const d = describeToken(parseTokenBody(FAKE_TOKEN), 1_790_000_000);
  assert.deepEqual(d.claimNames, ['exp', 'iat', 'sub']);
  assert.equal(d.lifetimeSeconds, 86_400);
  assert.equal(d.expiresInSeconds, 86_400);
  assert.ok(!JSON.stringify(d).includes(FAKE_TOKEN));
});

test('window never exceeds 28 days and plan uses YYYY-MM-DD dates', () => {
  const now = new Date('2026-11-05T12:00:00Z');
  assert.deepEqual(window(14, now), { start: '2026-10-23', end: '2026-11-05', days: 14 });
  assert.equal(window(400, now).days, MAX_WINDOW_DAYS);
  assert.equal(window(400, now).start, '2026-10-09');
  const p = plan(window(14, now));
  assert.ok(p.some((r) => r.path === '/hrv-service/hrv/daily/2026-10-23/2026-11-05'));
  for (const r of p) {
    for (const v of Object.values(r.query ?? {})) {
      if (typeof v === 'string') assert.match(v, /^\d{4}-\d{2}-\d{2}$/);
    }
  }
});

test('zipEntries reads names from a stored ZIP', () => {
  const name = Buffer.from('123_ACTIVITY.fit');
  const data = Buffer.from('FITDATA');
  const h = Buffer.alloc(30);
  h.writeUInt32LE(0x04034b50, 0);
  h.writeUInt32LE(data.length, 18);
  h.writeUInt32LE(data.length, 22);
  h.writeUInt16LE(name.length, 26);
  assert.deepEqual(zipEntries(Buffer.concat([h, name, data])), [
    { name: '123_ACTIVITY.fit', compressedSize: 7, uncompressedSize: 7 },
  ]);
});

test('committed synthetic fixtures contain no secrets, GPS or real-looking ids', () => {
  const dir = join(
    dirname(fileURLToPath(import.meta.url)),
    '../../android/core/garmin/src/test/resources/fixtures',
  );
  const files = readdirSync(dir).filter((f) => f.endsWith('.json'));
  assert.ok(files.length >= 8, 'synthetic fixtures present');
  for (const f of files) {
    const value = JSON.parse(readFileSync(join(dir, f), 'utf8'));
    // Sanitizing a synthetic fixture must not change any GPS value: they are already 0.
    const text = JSON.stringify(value);
    assert.ok(
      !/"(start|end)(Latitude|Longitude)":(?!(0|null)[,}])/.test(text),
      `${f}: GPS present`,
    );
    assert.doesNotThrow(() => assertNoSecrets(text, []), f);
  }
});
