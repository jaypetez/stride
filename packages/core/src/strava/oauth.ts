import type { StravaConfig } from '../config';
import type { StravaTokens } from './types';

export type FetchLike = (url: string, init?: RequestInit) => Promise<Response>;

const OAUTH_BASE = 'https://www.strava.com';

interface RawTokenResponse {
  access_token: string;
  refresh_token: string;
  expires_at: number;
  scope?: string;
  athlete?: { id?: number };
}

/** Build the Strava OAuth authorize URL the user visits to grant access. */
export function buildAuthorizeUrl(config: StravaConfig, state?: string): string {
  const params = new URLSearchParams({
    client_id: String(config.clientId ?? ''),
    redirect_uri: config.redirectUri,
    response_type: 'code',
    approval_prompt: 'auto',
    scope: config.scopes,
  });
  if (state) params.set('state', state);
  return `${OAUTH_BASE}/oauth/authorize?${params.toString()}`;
}

async function postToken(
  body: Record<string, string>,
  fetchImpl: FetchLike,
): Promise<StravaTokens> {
  const res = await fetchImpl(`${OAUTH_BASE}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(body).toString(),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Strava token request failed (${res.status}): ${text}`);
  }
  const raw = (await res.json()) as RawTokenResponse;
  return {
    accessToken: raw.access_token,
    refreshToken: raw.refresh_token,
    expiresAt: raw.expires_at,
    scope: raw.scope,
    athleteId: raw.athlete?.id,
  };
}

/** Exchange an authorization code for tokens (initial connect). */
export function exchangeCodeForTokens(
  config: StravaConfig,
  code: string,
  fetchImpl: FetchLike = fetch,
): Promise<StravaTokens> {
  return postToken(
    {
      client_id: String(config.clientId ?? ''),
      client_secret: config.clientSecret ?? '',
      code,
      grant_type: 'authorization_code',
    },
    fetchImpl,
  );
}

/** Exchange a refresh token for a fresh access token. Strava rotates the
 * refresh token, so always persist the returned one. */
export function refreshTokens(
  config: StravaConfig,
  refreshToken: string,
  fetchImpl: FetchLike = fetch,
): Promise<StravaTokens> {
  return postToken(
    {
      client_id: String(config.clientId ?? ''),
      client_secret: config.clientSecret ?? '',
      grant_type: 'refresh_token',
      refresh_token: refreshToken,
    },
    fetchImpl,
  );
}

/** Deauthorize (revoke) an access token. */
export async function deauthorize(
  accessToken: string,
  fetchImpl: FetchLike = fetch,
): Promise<void> {
  const res = await fetchImpl(`${OAUTH_BASE}/oauth/deauthorize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ access_token: accessToken }).toString(),
  });
  if (!res.ok) {
    throw new Error(`Strava deauthorize failed (${res.status})`);
  }
}
