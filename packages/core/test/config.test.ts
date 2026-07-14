import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { expandHome, loadConfig } from '../src/config';

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
});
