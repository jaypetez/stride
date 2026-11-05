import { describe, expect, it } from 'vitest';
import { scopeWarning, waitForOAuthCode } from '../src/commands/connect';

/**
 * Start `waitForOAuthCode` on an OS-assigned loopback port and resolve once it
 * reports the bound port via `onListening`, so tests can hit the real socket
 * without a fixed-port race.
 */
function start(opts: { state: string; timeoutMs?: number }): {
  port: Promise<number>;
  result: Promise<{ code: string; scope?: string }>;
} {
  let resolvePort!: (p: number) => void;
  const port = new Promise<number>((r) => {
    resolvePort = r;
  });
  const result = waitForOAuthCode({
    port: 0,
    host: '127.0.0.1',
    path: '/callback',
    state: opts.state,
    timeoutMs: opts.timeoutMs,
    onListening: ({ port: bound }) => resolvePort(bound),
  });
  return { port, result };
}

describe('waitForOAuthCode', () => {
  it('resolves with the code when state matches on the callback', async () => {
    const state = 'state-abc';
    const { port, result } = start({ state });
    // Attach the assertion (and its handler) before triggering the request so
    // the settled promise is never momentarily unhandled.
    const settled = expect(result).resolves.toEqual({ code: 'abc' });
    const bound = await port;
    await (await fetch(`http://127.0.0.1:${bound}/callback?code=abc&state=${state}`)).text();
    await settled;
  });

  it('rejects when the returned state does not match (CSRF guard)', async () => {
    const state = 'state-abc';
    const { port, result } = start({ state });
    const settled = expect(result).rejects.toThrow(/state mismatch/i);
    const bound = await port;
    await (await fetch(`http://127.0.0.1:${bound}/callback?code=abc&state=WRONG`)).text();
    await settled;
  });

  it('rejects with a timeout error when no callback arrives', async () => {
    const { result } = start({ state: 'state-abc', timeoutMs: 50 });
    await expect(result).rejects.toThrow(/timed out/i);
  });
});

describe('waitForOAuthCode granted scope', () => {
  it('returns the scope Strava echoes on the callback', async () => {
    const state = 'state-scope';
    const { port, result } = start({ state });
    const settled = expect(result).resolves.toEqual({ code: 'abc', scope: 'read,activity:read' });
    const bound = await port;
    await (
      await fetch(
        `http://127.0.0.1:${bound}/callback?code=abc&state=${state}&scope=read,activity:read`,
      )
    ).text();
    await settled;
  });
});

describe('scopeWarning', () => {
  it('is null when activity:read_all was granted', () => {
    expect(scopeWarning('read,activity:read_all,profile:read_all')).toBeNull();
  });

  it('names the missing scope when the user unticked private activities', () => {
    const msg = scopeWarning('read,activity:read');
    expect(msg).toMatch(/activity:read_all/);
    expect(msg).toMatch(/stride connect/);
  });

  it('warns when Strava returned no scope at all', () => {
    expect(scopeWarning(undefined)).toMatch(/activity:read_all/);
  });
});
