import { describe, expect, it } from 'vitest';
import { deauthorize, missingStravaScopes } from '../src/strava/index';

describe('deauthorize', () => {
  it('POSTs the access token to the Strava OAuth deauthorize endpoint', async () => {
    let calledUrl = '';
    let calledMethod = '';
    let calledBody = '';
    const fetchImpl = async (url: string, init?: RequestInit) => {
      calledUrl = url;
      calledMethod = init?.method ?? '';
      calledBody = String(init?.body ?? '');
      return new Response(null, { status: 200 });
    };

    await deauthorize('tok-123', fetchImpl);

    // OAuth lives on www.strava.com, not the data-plane API base.
    expect(calledUrl).toBe('https://www.strava.com/oauth/deauthorize');
    expect(calledMethod).toBe('POST');
    expect(calledBody).toContain('access_token=tok-123');
  });

  it('throws when Strava rejects the revoke', async () => {
    const fetchImpl = async () => new Response('unauthorized', { status: 401 });
    await expect(deauthorize('bad', fetchImpl)).rejects.toThrow(/deauthorize failed \(401\)/);
  });
});

describe('missingStravaScopes', () => {
  it('returns nothing when activity:read_all was granted', () => {
    expect(missingStravaScopes('read,activity:read_all,profile:read_all')).toEqual([]);
  });

  it('reports activity:read_all when the user unticked it', () => {
    expect(missingStravaScopes('read,activity:read')).toEqual(['activity:read_all']);
  });

  it('treats a missing scope parameter as nothing granted', () => {
    expect(missingStravaScopes(undefined)).toEqual(['activity:read_all']);
    expect(missingStravaScopes(null, ['read', 'activity:read_all'])).toEqual([
      'read',
      'activity:read_all',
    ]);
  });
});
