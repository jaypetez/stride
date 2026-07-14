import { readdir, stat, utimes, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import type { Activity } from '@stride/schemas';
import { afterEach, describe, expect, it } from 'vitest';
import { demoActivity } from '../src/fixtures';
import { LocalStore } from '../src/store/index';
import { SyncLockError } from '../src/store/lock';

const dirs: string[] = [];
function tmpStore(): LocalStore {
  const dir = path.join(
    os.tmpdir(),
    `stride-atomic-${process.pid}-${Math.random().toString(36).slice(2)}`,
  );
  dirs.push(dir);
  return new LocalStore(dir);
}

afterEach(async () => {
  for (const d of dirs.splice(0)) {
    await new LocalStore(d).clear();
  }
});

function act(id: string): Activity {
  return { ...demoActivity(), id, source: 'strava', fetchedAt: '2026-07-08T00:00:00Z' };
}

describe('atomic writeJson', () => {
  it('leaves no leftover temp files after a successful write', async () => {
    const store = tmpStore();
    await store.saveActivities([act('a'), act('b')]);
    const entries = await readdir(store.dir);
    expect(entries).toContain('activities.json');
    expect(entries.filter((e) => e.endsWith('.tmp'))).toHaveLength(0);
  });

  it('serializes concurrent writes without corruption (in-process mutex)', async () => {
    const store = tmpStore();
    // Fire many writes at once; the mutex must serialize them so the file is
    // always valid JSON and ends on a complete write.
    await Promise.all(Array.from({ length: 20 }, (_, i) => store.saveActivities([act(`id-${i}`)])));
    const loaded = await store.loadActivities();
    expect(loaded).toHaveLength(1); // never a torn/merged write
    expect(loaded[0].id).toMatch(/^id-\d+$/);
  });

  it('leaves the original file intact when a write fails', async () => {
    const store = tmpStore();
    await store.saveActivities([act('original')]);
    // A circular structure makes JSON.stringify throw inside writeJson.
    const circular: Record<string, unknown> = {};
    circular.self = circular;
    await expect(store.saveActivities(circular as unknown as Activity[])).rejects.toThrow();
    const loaded = await store.loadActivities();
    expect(loaded.map((a) => a.id)).toEqual(['original']);
    const entries = await readdir(store.dir);
    expect(entries.filter((e) => e.endsWith('.tmp'))).toHaveLength(0);
  });

  it.skipIf(process.platform === 'win32')('creates the token file at mode 0600', async () => {
    const store = tmpStore();
    await store.saveTokens({ accessToken: 'a', refreshToken: 'r', expiresAt: 123 });
    const mode = (await stat(path.join(store.dir, 'tokens.json'))).mode & 0o777;
    expect(mode).toBe(0o600);
  });
});

describe('cross-process sync lock', () => {
  it('rejects a second concurrent acquire', async () => {
    const store = tmpStore();
    const now = Date.parse('2026-07-08T00:00:00Z');
    const lock = await store.acquireSyncLock(now);
    await expect(store.acquireSyncLock(now)).rejects.toBeInstanceOf(SyncLockError);
    await lock.release();
    // After release the lock is free again.
    const lock2 = await store.acquireSyncLock(now);
    await lock2.release();
  });

  it('reclaims a stale lock past the TTL', async () => {
    const store = tmpStore();
    const t0 = Date.parse('2026-07-08T00:00:00Z');
    await store.acquireSyncLock(t0); // held, never released
    // 11 minutes later the holder is presumed dead → reclaimed.
    const later = t0 + 11 * 60 * 1000;
    const lock = await store.acquireSyncLock(later);
    await lock.release();
  });

  it('does NOT reclaim a fresh empty lock file (another process is mid-write)', async () => {
    const store = tmpStore();
    await store.saveActivities([act('seed')]); // create the store dir
    const lockPath = path.join(store.dir, 'sync.lock');
    // A racer won the exclusive create but hasn't written its record yet.
    await writeFile(lockPath, '');
    // Real-clock acquire: the empty file is fresh, so it must NOT be reclaimed.
    await expect(store.acquireSyncLock()).rejects.toBeInstanceOf(SyncLockError);
  });

  it('reclaims an empty lock file older than the grace period', async () => {
    const store = tmpStore();
    await store.saveActivities([act('seed')]);
    const lockPath = path.join(store.dir, 'sync.lock');
    await writeFile(lockPath, '');
    // Age the empty file well past the grace period → presumed abandoned.
    const old = new Date(Date.now() - 60_000);
    await utimes(lockPath, old, old);
    const lock = await store.acquireSyncLock();
    await lock.release();
  });
});
