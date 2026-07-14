import { describe, expect, it } from 'vitest';
import { waitForOAuthCode } from '../src/commands/connect';

/**
 * Start `waitForOAuthCode` on an OS-assigned loopback port and resolve once it
 * reports the bound port via `onListening`, so tests can hit the real socket
 * without a fixed-port race.
 */
function start(opts: { state: string; timeoutMs?: number }): {
  port: Promise<number>;
  result: Promise<{ code: string }>;
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
