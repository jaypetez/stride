/**
 * Hardening middleware for the LOCAL Stride API (AUD-010/011/012).
 *
 * The API serves the owner's own data on their machine, so the threat model is
 * the browser and the LAN, not the internet:
 *  - a malicious page that cross-site POSTs to `localhost` (CSRF) — blocked by
 *    requiring `Content-Type: application/json`, which forces a CORS preflight;
 *  - DNS rebinding (attacker hostname re-resolved to 127.0.0.1) — blocked by a
 *    `Host` allowlist of loopback names plus the configured bind host;
 *  - other LAN hosts — blocked by the loopback default bind and an optional
 *    bearer token (`STRIDE_API_TOKEN`).
 *
 * Every rejection uses the standard `{ error, requestId }` envelope.
 */
import { randomUUID, timingSafeEqual } from 'node:crypto';
import { isLoopbackHost, type StrideConfig } from '@stride/core';
import type { Context, MiddlewareHandler } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { secureHeaders } from 'hono/secure-headers';

/** Request ids we accept from the client; anything else is replaced. */
const REQUEST_ID_RE = /^[\w-]{1,64}$/;

/** Default cap on request bodies. Stride's JSON bodies are tiny. */
export const DEFAULT_BODY_LIMIT_BYTES = 64 * 1024;

/** Methods that change state and therefore must be JSON (CSRF guard). */
const STATE_CHANGING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Bind addresses meaning "every interface": they are not a usable Host name. */
const WILDCARD_HOSTS = new Set(['0.0.0.0', '::', '[::]']);

function reject(c: Context, status: 401 | 403 | 413 | 415 | 429, error: string) {
  return c.json({ error, requestId: c.get('requestId') }, status);
}

/**
 * Assign a request id: the client's `x-request-id` if it is a short token
 * (`^[\w-]{1,64}$`), else a fresh UUID — never echo arbitrary header content
 * into responses and logs.
 */
export function requestId(): MiddlewareHandler {
  return async (c, next) => {
    const supplied = c.req.header('x-request-id');
    const id = supplied && REQUEST_ID_RE.test(supplied) ? supplied : randomUUID();
    c.set('requestId', id);
    c.header('x-request-id', id);
    await next();
  };
}

/** Normalize a host name for comparison: lower-case, IPv6 in brackets. */
function normalizeHost(host: string): string {
  const h = host.trim().toLowerCase();
  return h.includes(':') && !h.startsWith('[') ? `[${h}]` : h;
}

/** The hostname (no port) from a `Host` header value, or null if unparseable. */
function hostnameOf(hostHeader: string): string | null {
  try {
    return new URL(`http://${hostHeader}`).hostname.toLowerCase();
  } catch {
    return null;
  }
}

/**
 * DNS-rebinding guard: accept only requests whose `Host` names this machine
 * (loopback), the configured bind host, or an explicit `STRIDE_API_ALLOWED_HOSTS`
 * entry. The Vite dev proxy rewrites `Host` to `localhost:<port>`
 * (`changeOrigin: true`), so the web UI keeps working.
 */
export function hostGuard(
  config: Pick<StrideConfig, 'apiHost' | 'apiAllowedHosts'>,
): MiddlewareHandler {
  const allowed = new Set(config.apiAllowedHosts.map(normalizeHost));
  const bind = normalizeHost(config.apiHost);
  if (!WILDCARD_HOSTS.has(bind)) allowed.add(bind);

  return async (c, next) => {
    const header = c.req.header('host') ?? new URL(c.req.url).host;
    const hostname = header ? hostnameOf(header) : null;
    if (!hostname || !(isLoopbackHost(hostname) || allowed.has(hostname))) {
      return reject(c, 403, 'Forbidden host');
    }
    await next();
  };
}

/**
 * Constant-time string comparison. Only the length can leak (the token's
 * length is not secret material); on a length mismatch we still run a
 * same-length compare so timing does not depend on the content.
 */
function safeEqual(actual: string, expected: Buffer): boolean {
  const got = Buffer.from(actual, 'utf8');
  if (got.length !== expected.length) {
    timingSafeEqual(expected, expected);
    return false;
  }
  return timingSafeEqual(got, expected);
}

/**
 * Optional bearer auth (`STRIDE_API_TOKEN`). A no-op when no token is
 * configured. Compared in constant time.
 */
export function bearerToken(token: string | undefined): MiddlewareHandler {
  if (!token) return async (_c, next) => next();
  const expected = Buffer.from(token, 'utf8');
  return async (c, next) => {
    const auth = c.req.header('authorization') ?? '';
    const match = /^Bearer\s+(.+)$/i.exec(auth);
    if (!match || !safeEqual(match[1].trim(), expected)) {
      c.header('www-authenticate', 'Bearer realm="stride"');
      return reject(c, 401, 'Unauthorized');
    }
    await next();
  };
}

/**
 * CSRF guard: state-changing requests must declare `application/json`. A
 * cross-site page can only send "simple" requests (no content type,
 * `text/plain`, form-encoded, multipart) without a CORS preflight, and the
 * preflight is refused for any origin but `STRIDE_WEB_ORIGIN`.
 */
export function requireJson(): MiddlewareHandler {
  return async (c, next) => {
    if (STATE_CHANGING.has(c.req.method)) {
      const type = (c.req.header('content-type') ?? '').split(';')[0].trim().toLowerCase();
      if (type !== 'application/json') {
        return reject(c, 415, 'Unsupported Media Type — send Content-Type: application/json');
      }
    }
    await next();
  };
}

/** Request-body size cap; oversize bodies get 413 in the standard envelope. */
export function limitBody(maxSize = DEFAULT_BODY_LIMIT_BYTES): MiddlewareHandler {
  return bodyLimit({
    maxSize,
    onError: (c) => reject(c, 413, `Payload Too Large — limit is ${maxSize} bytes`),
  });
}

/** JSON-API security headers (no HTML is ever served). */
export function apiSecureHeaders(): MiddlewareHandler {
  return secureHeaders({
    contentSecurityPolicy: { defaultSrc: ["'none'"], frameAncestors: ["'none'"] },
  });
}

export interface RateLimitOptions {
  /** Requests allowed per window. */
  limit: number;
  /** Window length in milliseconds. */
  windowMs: number;
  /** Only count these methods (default: all). */
  methods?: string[];
  /** Clock, injectable for tests. */
  now?: () => number;
}

/**
 * A fixed-window limiter for expensive routes (`/sync` burns Strava quota, the
 * coach routes burn LLM tokens). The API is single-user and loopback-bound, so
 * one shared bucket per limiter (not per client IP) is the right granularity.
 */
export function rateLimit(opts: RateLimitOptions): MiddlewareHandler {
  const { limit, windowMs, methods, now = Date.now } = opts;
  const counted = methods ? new Set(methods.map((m) => m.toUpperCase())) : null;
  let windowStart = 0;
  let used = 0;
  return async (c, next) => {
    if (counted && !counted.has(c.req.method)) return next();
    const t = now();
    if (t - windowStart >= windowMs) {
      windowStart = t;
      used = 0;
    }
    if (used >= limit) {
      const retryAfter = Math.max(1, Math.ceil((windowStart + windowMs - t) / 1000));
      c.header('retry-after', String(retryAfter));
      return reject(c, 429, 'Too Many Requests — slow down and retry later');
    }
    used++;
    await next();
  };
}

/** Default limits: Strava sync is quota-bound; coach routes may call the LLM. */
export const DEFAULT_RATE_LIMITS = {
  sync: { limit: 5, windowMs: 60_000 },
  llm: { limit: 30, windowMs: 60_000 },
} satisfies Record<string, RateLimitOptions>;
