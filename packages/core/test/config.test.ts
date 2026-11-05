import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { expandHome, isLoopbackHost, loadConfig } from '../src/config';

const HOME = os.homedir();

describe('expandHome', () => {
  it('expands a bare ~ to the home directory', () => {
    expect(expandHome('~')).toBe(HOME);
    expect(expandHome('~/')).toBe(HOME);
  });

  it('expands a leading ~/ segment', () => {
    expect(expandHome('~/.stride')).toBe(path.join(HOME, '.stride'));
    expect(expandHome('~/data/store')).toBe(path.join(HOME, 'data', 'store'));
  });

  it('expands $HOME and the brace form of HOME', () => {
    // Built by concatenation so the source has no literal `${...}` placeholder.
    const braced = `$${'{HOME}'}`; // the runtime string "${HOME}"
    expect(expandHome('$HOME/.stride')).toBe(`${HOME}/.stride`);
    expect(expandHome(`${braced}/data`)).toBe(`${HOME}/data`);
  });

  it('expands %USERPROFILE% (Windows-style)', () => {
    const out = expandHome('%USERPROFILE%/stride');
    expect(out).toContain(HOME);
    expect(out).not.toContain('%USERPROFILE%');
  });

  it('leaves absolute and relative paths without home markers untouched', () => {
    expect(expandHome('.stride')).toBe('.stride');
    expect(expandHome('/var/lib/stride')).toBe('/var/lib/stride');
  });

  it('does not expand $HOME when it is only a prefix of a longer name', () => {
    expect(expandHome('$HOMER/x')).toBe('$HOMER/x');
  });
});

describe('loadConfig', () => {
  it('applies expandHome to STRIDE_DATA_DIR', () => {
    const config = loadConfig({ STRIDE_DATA_DIR: '~/.stride' });
    expect(config.dataDir).toBe(path.join(HOME, '.stride'));
    expect(config.dataDir.startsWith('~')).toBe(false);
  });

  it('defaults dataDir to .stride (relative, unexpanded)', () => {
    expect(loadConfig({}).dataDir).toBe('.stride');
  });

  it('defaults webOrigin to the web UI origin and honors STRIDE_WEB_ORIGIN', () => {
    expect(loadConfig({}).webOrigin).toBe('http://localhost:5173');
    expect(loadConfig({ STRIDE_WEB_ORIGIN: 'https://stride.example' }).webOrigin).toBe(
      'https://stride.example',
    );
  });

  it('binds the API to loopback by default and honors STRIDE_API_HOST', () => {
    expect(loadConfig({}).apiHost).toBe('127.0.0.1');
    expect(loadConfig({ STRIDE_API_HOST: '0.0.0.0' }).apiHost).toBe('0.0.0.0');
    expect(loadConfig({ STRIDE_API_HOST: '  ' }).apiHost).toBe('127.0.0.1');
  });

  it('reads an optional API bearer token and extra allowed hosts', () => {
    expect(loadConfig({}).apiToken).toBeUndefined();
    expect(loadConfig({ STRIDE_API_TOKEN: '' }).apiToken).toBeUndefined();
    expect(loadConfig({ STRIDE_API_TOKEN: 's3cret' }).apiToken).toBe('s3cret');
    expect(loadConfig({}).apiAllowedHosts).toEqual([]);
    expect(
      loadConfig({ STRIDE_API_ALLOWED_HOSTS: '192.168.1.5, Stride.Lan ,' }).apiAllowedHosts,
    ).toEqual(['192.168.1.5', 'stride.lan']);
  });
});

describe('isLoopbackHost', () => {
  it('accepts loopback names and addresses', () => {
    for (const h of [
      'localhost',
      'LOCALHOST',
      'app.localhost',
      '127.0.0.1',
      '127.1.2.3',
      '::1',
      '[::1]',
    ])
      expect(isLoopbackHost(h), h).toBe(true);
  });

  it('rejects LAN, wildcard and public names', () => {
    for (const h of [
      '0.0.0.0',
      '::',
      '192.168.1.5',
      'evil.example',
      'localhost.evil.example',
      '127.0.0.1.evil.example',
    ])
      expect(isLoopbackHost(h), h).toBe(false);
  });
});
