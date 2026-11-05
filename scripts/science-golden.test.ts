import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_NOW,
  GOLDEN_DIR,
  generateGoldens,
  stableStringify,
  staleFiles,
} from './science-golden.mts';

describe('science goldens', () => {
  it('two runs are byte-identical', () => {
    const a = generateGoldens(DEFAULT_NOW);
    const b = generateGoldens(DEFAULT_NOW);
    expect(Object.keys(a).sort()).toEqual(Object.keys(b).sort());
    for (const name of Object.keys(a)) expect(b[name]).toBe(a[name]);
  });

  it('check mode passes on the committed files (run `pnpm science:golden` if this fails)', () => {
    expect(staleFiles(generateGoldens(DEFAULT_NOW), GOLDEN_DIR)).toEqual([]);
  });

  it('check mode reports edited, missing and unexpected files', () => {
    const generated = generateGoldens(DEFAULT_NOW);
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'science-golden-'));
    try {
      for (const [name, content] of Object.entries(generated)) {
        fs.writeFileSync(path.join(dir, name), content.replace(/\n/g, '\r\n'));
      }
      expect(staleFiles(generated, dir)).toEqual([]); // CRLF checkouts are not stale
      fs.writeFileSync(path.join(dir, 'vdot.json'), '{}\n');
      fs.rmSync(path.join(dir, 'pmc.json'));
      fs.writeFileSync(path.join(dir, 'extra.json'), '{}\n');
      expect(staleFiles(generated, dir)).toEqual([
        'extra.json (unexpected)',
        'pmc.json',
        'vdot.json',
      ]);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  });

  it('every file has meta + well-formed cases', () => {
    for (const [name, content] of Object.entries(generateGoldens(DEFAULT_NOW))) {
      expect(content.endsWith('}\n'), name).toBe(true);
      const doc = JSON.parse(content);
      expect(doc.meta.now).toBe(DEFAULT_NOW);
      expect(doc.meta.caseCount).toBe(doc.cases.length);
      expect(doc.cases.length).toBeGreaterThan(0);
      for (const c of doc.cases) {
        expect(Object.keys(c).sort()).toEqual(['fn', 'input', 'name', 'output']);
        expect(Object.keys(doc.meta.functions)).toContain(c.fn);
      }
    }
  });

  it('stableStringify sorts keys, drops undefined and rejects non-finite numbers', () => {
    expect(stableStringify({ b: 1, a: { d: undefined, c: [1, -0] } })).toBe(
      '{\n  "a": {\n    "c": [1, 0]\n  },\n  "b": 1\n}',
    );
    expect(stableStringify({ z: 'x', y: null, w: undefined })).toBe('{"y": null, "z": "x"}');
    expect(() => stableStringify({ x: Number.POSITIVE_INFINITY })).toThrow(/non-finite/);
    expect(() => stableStringify([Number.NaN])).toThrow(/non-finite/);
  });
});
