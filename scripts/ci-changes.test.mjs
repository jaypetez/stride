import assert from 'node:assert/strict';
import { test } from 'node:test';
import { classify } from './ci-changes.mjs';

test('docs-only changes trigger no surface jobs', () => {
  assert.deepEqual(classify(['README.md', 'docs/adr/0010-solo-garmin-mvp.md']), {
    android: false,
    cloud: false,
    web: false,
  });
});

test('android and contract changes trigger the android job', () => {
  assert.equal(classify(['android/app/build.gradle.kts']).android, true);
  assert.equal(classify(['contracts/science-golden/load.json']).android, true);
  assert.equal(classify(['.github/workflows/android.yml']).android, true);
});

test('package changes trigger web and cloud but not android', () => {
  assert.deepEqual(classify(['packages/core/src/science/load.ts']), { android: false, cloud: true, web: true });
});
