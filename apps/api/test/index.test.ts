import os from 'node:os';
import path from 'node:path';
import { LocalStore, loadConfig } from '@stride/core';
import { afterEach, describe, expect, it, vi } from 'vitest';

/**
 * The entrypoint binds `STRIDE_API_HOST` and prints a startup banner. `serve`
 * is mocked so no socket is opened; `pnpm verify` boots the real server.
 */

const boot = vi.hoisted(() => ({
  env: {} as Record<string, string>,
  llm: null as unknown,
  served: undefined as undefined | { port: number; hostname: string },
}));

vi.mock('@hono/node-server', () => ({
  serve: (opts: { port: number; hostname: string }, cb: (info: { port: number }) => void) => {
    boot.served = opts;
    cb({ port: opts.port });
  },
}));

vi.mock('../src/state', () => ({
  loadApiState: () => ({
    config: loadConfig(boot.env),
    store: new LocalStore(path.join(os.tmpdir(), 'stride-api-index-test')),
    llm: boot.llm,
  }),
}));

afterEach(() => {
  vi.restoreAllMocks();
  vi.resetModules();
  boot.served = undefined;
  boot.llm = null;
});

async function start(env: Record<string, string>, llm: unknown = null) {
  boot.env = env;
  boot.llm = llm;
  const log = vi.spyOn(console, 'log').mockImplementation(() => {});
  const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
  await import('../src/index');
  return {
    out: log.mock.calls.map((c) => String(c[0])).join('\n'),
    warned: warn.mock.calls.map((c) => String(c[0])).join('\n'),
  };
}

describe('API entrypoint', () => {
  it('binds loopback by default and prints an open-auth banner without a warning', async () => {
    const { out, warned } = await start({});
    expect(boot.served?.hostname).toBe('127.0.0.1');
    expect(out).toMatch(/listening on http:\/\/127\.0\.0\.1:\d+/);
    expect(out).toMatch(/Claude: disabled/);
    expect(out).toMatch(/Auth: none \(loopback trust\)/);
    expect(warned).toBe('');
  });

  it('brackets an IPv6 bind host and reports an enabled coach and bearer auth', async () => {
    const { out, warned } = await start(
      { STRIDE_API_HOST: '::1', STRIDE_API_TOKEN: 's3cret' },
      { stub: true },
    );
    expect(boot.served?.hostname).toBe('::1');
    expect(out).toMatch(/http:\/\/\[::1\]:/);
    expect(out).toMatch(/Claude: enabled/);
    expect(out).toMatch(/Auth: bearer token required/);
    expect(warned).toBe('');
  });

  it('warns when bound to a non-loopback host without a token', async () => {
    const { warned } = await start({ STRIDE_API_HOST: '0.0.0.0' });
    expect(warned).toMatch(/WARNING: bound to non-loopback 0\.0\.0\.0 without STRIDE_API_TOKEN/);
  });

  it('does not warn on a non-loopback host when a token is set', async () => {
    const { warned } = await start({ STRIDE_API_HOST: '0.0.0.0', STRIDE_API_TOKEN: 's3cret' });
    expect(warned).toBe('');
  });
});
