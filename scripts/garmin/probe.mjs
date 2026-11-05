#!/usr/bin/env node
// Garmin Personal API fixture probe (MVP-001, #375). See docs/garmin/personal-api.md.
//
//   GARMIN_PERSONAL_API_KEY=… node scripts/garmin/probe.mjs
//
// Exchanges the key for a token, fetches one sample of every endpoint Stride uses over a
// recent window, sanitizes each body and writes it to
// android/core/garmin/src/test/resources/fixtures/real/. The key and token are never
// printed or written; every output is checked for them before it touches disk.
// Optional env: GARMIN_PROBE_DAYS (1–28, default 14), GARMIN_PROBE_OUT,
// GARMIN_PERSONAL_API_BASE_URL.
//
// No dependencies: plain Node >= 22 (global fetch).

import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const DEFAULT_BASE_URL = 'https://personal.connectapi.garmin.com';
export const TOKEN_PATH = '/personalapitoken-service/personal-api/personal-api-key/token';
export const MAX_WINDOW_DAYS = 28;

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
export const DEFAULT_OUT_DIR = join(
  REPO_ROOT,
  'android/core/garmin/src/test/resources/fixtures/real',
);

// ---------------------------------------------------------------------------------------
// Sanitizer
// ---------------------------------------------------------------------------------------

// Enum-like identifiers that carry no personal data and that the client needs verbatim.
const ID_EXEMPT = new Set([
  'typeId',
  'sleepQualityTypePK',
  'sleepResultTypePK',
  'parentTypeId',
  'formatId',
  'sportTypeId',
  'fitProductNumber',
  'napSource',
  'index',
  'bodyBatteryValueDescriptorIndex',
]);
// Identifier keys: activityId, userProfilePk, summaryId, deviceId, samplePk, uuid,
// activityUUID, serialNumber, unitId, childIds …
const ID_KEY =
  /^(id|ids|uuid|.*(Id|ID|Ids|IDs|Pk|PK|Uuid|UUID)|serialNumber|unitId(Number)?|unitIds|deviceSerial.*)$/;
// GPS: startLatitude, endLongitude, startingLatitudeInDegree, upperLatitude, lat, lon, polyline …
const GEO_KEY = /(latitude|longitude|^lat$|^lon$|^lng$|^long$|polyline|geoPoint|gps)/i;
const NAME_KEYS = new Set([
  'activityName',
  'description',
  'locationName',
  'parentName',
  'comments',
  'userNote',
  'kidSleepNote',
  'gameName',
  'ownerDisplayName',
  'ownerFullName',
  'displayName',
  'displayname',
  'fullName',
  'fullname',
  'firstName',
  'lastName',
  'userName',
  'username',
  'nickname',
  'activityLikeDisplayNames',
  'activityLikeFullNames',
  'activityLikeAuthors',
]);
const SECRET_KEY = /(token|secret|password|apikey|api_key|authorization|cookie)/i;
const URL_KEY = /(url|image)/i;
const EMAIL_KEY = /email/i;
const EMAIL_RE = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const JWT_RE = /eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]*/g;
const JWT_TEST = new RegExp(JWT_RE.source);
// A long opaque run with letters and digits (key/token/hash-like). Dates, enum keys
// (UPPER_SNAKE) and plain words don't match.
const OPAQUE_RE = /^(?=.*[0-9])(?=.*[a-z])[A-Za-z0-9+/_=.-]{32,}$/;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const REDACTED = 'REDACTED';
export const REDACTED_TOKEN = 'REDACTED_TOKEN';
export const REDACTED_EMAIL = 'redacted@example.invalid';
export const REDACTED_URL = 'https://example.invalid/redacted';

/** Replace every occurrence of each secret (len >= 4) in `text` with REDACTED_TOKEN. */
export function scrubSecrets(text, secrets = []) {
  let out = String(text);
  for (const s of secrets) {
    if (typeof s === 'string' && s.length >= 4) out = out.split(s).join(REDACTED_TOKEN);
  }
  return out.replace(JWT_RE, REDACTED_TOKEN);
}

/**
 * Build a sanitizer. The same original id maps to the same fake id across every call
 * of one sanitizer (so an activity's id in the list matches its detail), and fake ids
 * are assigned in traversal order, so the output is deterministic for a given input.
 */
export function createSanitizer({ secrets = [] } = {}) {
  const ids = new Map();
  const secretList = secrets.filter((s) => typeof s === 'string' && s.length >= 4);

  function fakeId(value) {
    const k = `${typeof value}:${value}`;
    if (!ids.has(k)) ids.set(k, ids.size + 1);
    const n = ids.get(k);
    if (typeof value === 'number') return 900_000_000 + n;
    if (UUID_RE.test(value)) return `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
    return `fake-id-${n}`;
  }

  function cleanString(value) {
    let out = scrubSecrets(value, secretList);
    if (OPAQUE_RE.test(out)) return REDACTED_TOKEN;
    out = out.replace(EMAIL_RE, REDACTED_EMAIL);
    return out;
  }

  function walk(value, key) {
    if (value === null || value === undefined) return value;
    if (Array.isArray(value)) {
      if (key && GEO_KEY.test(key)) return [];
      return value.map((v) => walk(v, key));
    }
    if (typeof value === 'object') {
      const out = {};
      for (const [k, v] of Object.entries(value)) out[k] = walkField(k, v);
      return out;
    }
    if (typeof value === 'string') return cleanString(value);
    return value;
  }

  function walkField(k, v) {
    if (v === null || v === undefined) return v;
    if (SECRET_KEY.test(k) && typeof v !== 'object') return REDACTED_TOKEN;
    if (GEO_KEY.test(k)) {
      if (typeof v === 'number') return 0;
      if (typeof v === 'string') return REDACTED;
      if (Array.isArray(v)) return [];
    }
    if (!ID_EXEMPT.has(k) && ID_KEY.test(k)) {
      if (typeof v === 'number' || typeof v === 'string') return fakeId(v);
      if (Array.isArray(v))
        return v.map((x) => (typeof x === 'number' || typeof x === 'string' ? fakeId(x) : walk(x)));
    }
    if (NAME_KEYS.has(k)) {
      if (typeof v === 'string') return REDACTED;
      if (Array.isArray(v)) return v.map((x) => (typeof x === 'string' ? REDACTED : walk(x)));
    }
    if (EMAIL_KEY.test(k) && typeof v === 'string') return REDACTED_EMAIL;
    if (URL_KEY.test(k) && typeof v === 'string' && /^https?:/i.test(v)) return REDACTED_URL;
    return walk(v, k);
  }

  return (value) => walk(value);
}

/** One-shot sanitize with a fresh id mapping. */
export function sanitize(value, options = {}) {
  return createSanitizer(options)(value);
}

/** Throws if any secret appears in `text`. Last line of defence before a write. */
export function assertNoSecrets(text, secrets = []) {
  for (const s of secrets) {
    if (typeof s === 'string' && s.length >= 4 && text.includes(s)) {
      throw new Error('refusing to write output: it contains the API key or token');
    }
  }
  if (JWT_TEST.test(text)) {
    throw new Error('refusing to write output: it contains a JWT-like string');
  }
}

// ---------------------------------------------------------------------------------------
// Token helpers
// ---------------------------------------------------------------------------------------

/** Parse the token-exchange body (raw JWT text, a quoted string, or a JSON object). */
export function parseTokenBody(body) {
  const text = String(body).trim();
  if (text.startsWith('{')) {
    const obj = JSON.parse(text);
    const token = obj.access_token ?? obj.accessToken ?? obj.token;
    return {
      token: typeof token === 'string' ? token : '',
      kind: 'json',
      jsonKeys: Object.keys(obj),
    };
  }
  if (text.startsWith('"') && text.endsWith('"') && text.length >= 2) {
    return { token: text.slice(1, -1), kind: 'quoted-string' };
  }
  return { token: text, kind: 'raw-text' };
}

/** Decode a JWT payload without verifying it. Returns null when it isn't a JWT. */
export function decodeJwtClaims(token) {
  const parts = String(token).split('.');
  if (parts.length < 2) return null;
  try {
    return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
  } catch {
    return null;
  }
}

/** Token shape for the manifest: claim names and lifetime only, never values. */
export function describeToken(parsed, nowSec = Math.floor(Date.now() / 1000)) {
  const claims = decodeJwtClaims(parsed.token);
  return {
    bodyKind: parsed.kind,
    jsonKeys: parsed.jsonKeys,
    isJwt: claims !== null,
    claimNames: claims ? Object.keys(claims).sort() : [],
    lifetimeSeconds:
      claims && typeof claims.exp === 'number' && typeof claims.iat === 'number'
        ? claims.exp - claims.iat
        : null,
    expiresInSeconds: claims && typeof claims.exp === 'number' ? claims.exp - nowSec : null,
  };
}

// ---------------------------------------------------------------------------------------
// Request plan
// ---------------------------------------------------------------------------------------

export function isoDate(d) {
  return d.toISOString().slice(0, 10);
}

/** Date window [start, end] ending today (UTC), at most MAX_WINDOW_DAYS days inclusive. */
export function window(days, now = new Date()) {
  const n = Math.min(Math.max(Math.trunc(Number(days)) || 14, 1), MAX_WINDOW_DAYS);
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const start = new Date(end.getTime() - (n - 1) * 86_400_000);
  return { start: isoDate(start), end: isoDate(end), days: n };
}

/** The JSON endpoints the probe samples (activity detail/FIT are added once an id is known). */
export function plan({ start, end }) {
  return [
    {
      file: 'activities.json',
      path: '/activitylist-service/activities/search/activities',
      query: { startDate: start, endDate: end, start: 0, limit: 20 },
    },
    {
      file: 'dailies.json',
      path: '/usersummary-service/v2/dailysummaries',
      query: { startDate: start, endDate: end },
    },
    {
      file: 'sleeps.json',
      path: '/sleep-service/sleep/dailySleepSummariesByDate',
      query: { startDate: start, endDate: end },
    },
    { file: 'hrv.json', path: `/hrv-service/hrv/daily/${start}/${end}` },
    { file: 'stress.json', path: `/usersummary-service/stats/stress/daily/${start}/${end}` },
    {
      file: 'body-battery.json',
      path: '/wellness-service/wellness/bodyBattery/reports/daily',
      query: { startDate: start, endDate: end },
    },
    {
      file: 'daily-heart-rate.json',
      path: '/wellness-service/wellness/dailyHeartRate',
      query: { date: end },
    },
    {
      file: 'body-comps.json',
      path: `/weight-service/weight/range/${start}/${end}`,
      query: { includeAll: true },
    },
  ];
}

/** Read entry names and sizes from a ZIP's local file headers (no decompression). */
export function zipEntries(buf) {
  const out = [];
  let i = 0;
  while (i + 30 <= buf.length && buf.readUInt32LE(i) === 0x04034b50) {
    const compressed = buf.readUInt32LE(i + 18);
    const uncompressed = buf.readUInt32LE(i + 22);
    const nameLen = buf.readUInt16LE(i + 26);
    const extraLen = buf.readUInt16LE(i + 28);
    const name = buf.subarray(i + 30, i + 30 + nameLen).toString('utf8');
    out.push({ name, compressedSize: compressed, uncompressedSize: uncompressed });
    if (compressed === 0) break; // data-descriptor streaming; stop rather than guess
    i += 30 + nameLen + extraLen + compressed;
  }
  return out;
}

// ---------------------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------------------

const INTERESTING_HEADER = /(rate|limit|retry-after|content-type|content-length)/i;
// Every secret seen at runtime (key, then token), so even the top-level error handler
// can scrub them.
const liveSecrets = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function pickHeaders(headers) {
  const out = {};
  for (const [k, v] of headers) if (INTERESTING_HEADER.test(k)) out[k] = v;
  return out;
}

function url(base, path, query) {
  const u = new URL(base + path);
  for (const [k, v] of Object.entries(query ?? {})) u.searchParams.set(k, String(v));
  return u;
}

async function main() {
  const key = (process.env.GARMIN_PERSONAL_API_KEY ?? '').trim();
  if (!key) {
    process.stderr.write(
      'GARMIN_PERSONAL_API_KEY is not set. Create a key at ' +
        'https://connect.garmin.com/app/settings/personalApiToken\n',
    );
    process.exit(2);
  }
  const secrets = liveSecrets;
  secrets.push(key);
  const log = (msg) => process.stderr.write(`${scrubSecrets(msg, secrets)}\n`);
  const base = (process.env.GARMIN_PERSONAL_API_BASE_URL ?? DEFAULT_BASE_URL).replace(/\/$/, '');
  const outDir = resolve(process.env.GARMIN_PROBE_OUT ?? DEFAULT_OUT_DIR);
  const win = window(process.env.GARMIN_PROBE_DAYS ?? 14);
  const sanitizer = createSanitizer({ secrets });
  const manifest = {
    generatedBy: 'scripts/garmin/probe.mjs',
    generatedAt: new Date().toISOString(),
    apiHost: base,
    window: win,
    token: null,
    requests: [],
  };

  await mkdir(outDir, { recursive: true });
  const write = async (file, value) => {
    const text = `${JSON.stringify(value, null, 2)}\n`;
    assertNoSecrets(text, secrets);
    await writeFile(join(outDir, file), text);
    log(`  wrote ${file}`);
  };

  // 1. Token exchange.
  log(`exchanging the key at ${base}${TOKEN_PATH}`);
  const tokenRes = await fetch(base + TOKEN_PATH, {
    method: 'POST',
    headers: { Authorization: `ApiKey ${key}`, Accept: '*/*', 'User-Agent': 'stride-probe' },
  });
  const tokenText = await tokenRes.text();
  manifest.requests.push({
    name: 'token',
    method: 'POST',
    path: TOKEN_PATH,
    status: tokenRes.status,
    headers: pickHeaders(tokenRes.headers),
  });
  if (!tokenRes.ok) {
    throw new Error(`token exchange failed: HTTP ${tokenRes.status} (key invalid or expired?)`);
  }
  const parsed = parseTokenBody(tokenText);
  if (!parsed.token) throw new Error('token exchange returned no token');
  secrets.push(parsed.token);
  manifest.token = describeToken(parsed);
  log(`  token ok (${parsed.kind}, claims: ${manifest.token.claimNames.join(', ')})`);

  const auth = { Authorization: `Bearer ${parsed.token}`, 'User-Agent': 'stride-probe' };
  const get = async (name, path, query, accept = 'application/json', headers = auth) => {
    await sleep(700); // stay far below the ~100 req/min community-reported limit
    const t0 = Date.now();
    const res = await fetch(url(base, path, query), { headers: { ...headers, Accept: accept } });
    const buf = Buffer.from(await res.arrayBuffer());
    const entry = {
      name,
      method: 'GET',
      path,
      query: query ?? {},
      status: res.status,
      ms: Date.now() - t0,
      headers: pickHeaders(res.headers),
      bytes: buf.length,
    };
    manifest.requests.push(entry);
    log(`  GET ${path} -> ${res.status} (${buf.length} B)`);
    return { res, buf, entry };
  };
  const json = (buf) => {
    const text = buf.toString('utf8').trim();
    if (!text) return null;
    try {
      return JSON.parse(text);
    } catch {
      return { nonJsonBody: text.slice(0, 200) };
    }
  };

  // 2. JSON endpoints.
  let activities = [];
  for (const req of plan(win)) {
    const { buf } = await get(req.file, req.path, req.query);
    const body = json(buf);
    if (req.file === 'activities.json' && Array.isArray(body)) activities = body;
    await write(req.file, sanitizer(body));
  }

  // 3. Activity detail + FIT metadata (prefer a run).
  const pick =
    activities.find((a) => /running/.test(a?.activityType?.typeKey ?? '')) ?? activities[0];
  if (pick?.activityId) {
    const { buf } = await get(
      'activity-detail.json',
      `/activity-service/activity/${pick.activityId}`,
    );
    await write('activity-detail.json', sanitizer(json(buf)));
    const fit = await get(
      'activity-file',
      `/download-service/files/activity/${pick.activityId}`,
      undefined,
      'application/x-zip-compressed',
    );
    await write('activity-file.meta.json', {
      status: fit.res.status,
      contentType: fit.res.headers.get('content-type'),
      bytes: fit.buf.length,
      zipEntries: fit.res.ok
        ? zipEntries(fit.buf).map((e) => ({ ...e, name: e.name.replace(/\d{5,}/g, 'ID') }))
        : [],
    });
    // Path ids in the manifest are replaced too.
    for (const r of manifest.requests) r.path = r.path.replaceAll(String(pick.activityId), '{id}');
  } else {
    log('  no activities in the window; skipping detail and FIT download');
  }

  // 4. Error shapes: no auth, invalid key, oversized range.
  const noAuth = await get(
    'error-401.json',
    '/activitylist-service/activities/search/activities',
    { start: 0, limit: 1 },
    'application/json',
    { 'User-Agent': 'stride-probe' },
  );
  await write('error-401.json', sanitizer(json(noAuth.buf)));
  await sleep(700);
  const badKey = await fetch(base + TOKEN_PATH, {
    method: 'POST',
    headers: { Authorization: 'ApiKey not-a-real-key', Accept: '*/*' },
  });
  manifest.requests.push({ name: 'token-invalid-key', status: badKey.status });
  await write('token-error.json', {
    status: badKey.status,
    contentType: badKey.headers.get('content-type'),
    body: sanitizer(json(Buffer.from(await badKey.arrayBuffer()))),
  });
  const longStart = isoDate(new Date(Date.parse(win.end) - 40 * 86_400_000));
  const tooLong = await get(
    'error-400.json',
    `/usersummary-service/stats/stress/daily/${longStart}/${win.end}`,
  );
  await write('error-400.json', sanitizer(json(tooLong.buf)));

  await write('_manifest.json', sanitizer(manifest));
  log(`done: ${outDir}\nReview every file by eye before committing it.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    const secrets = [...liveSecrets, (process.env.GARMIN_PERSONAL_API_KEY ?? '').trim()];
    process.stderr.write(`probe failed: ${scrubSecrets(err?.message ?? err, secrets)}\n`);
    process.exit(1);
  });
}
