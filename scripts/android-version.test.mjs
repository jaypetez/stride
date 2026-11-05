import assert from 'node:assert/strict';
import { test } from 'node:test';
import { bump, isIncreasing } from './android-version.mjs';

test('patch bump increments versionCode and patch', () => {
  assert.deepEqual(bump({ versionName: '0.1.0', versionCode: 1 }), {
    versionName: '0.1.1',
    versionCode: 2,
  });
});

test('minor and major bumps reset lower parts', () => {
  assert.deepEqual(bump({ versionName: '0.1.7', versionCode: 9 }, 'minor'), {
    versionName: '0.2.0',
    versionCode: 10,
  });
  assert.deepEqual(bump({ versionName: '0.9.3', versionCode: 12 }, 'major'), {
    versionName: '1.0.0',
    versionCode: 13,
  });
});

test('pre-release suffixes are dropped on bump', () => {
  assert.deepEqual(bump({ versionName: '0.1.0-mvp', versionCode: 1 }), {
    versionName: '0.1.1',
    versionCode: 2,
  });
});

test('versionCode must strictly increase', () => {
  assert.equal(isIncreasing({ versionCode: 3 }, { versionCode: 4 }), true);
  assert.equal(isIncreasing({ versionCode: 3 }, { versionCode: 3 }), false);
});
