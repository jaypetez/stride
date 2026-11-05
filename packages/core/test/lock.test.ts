import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  acquireSyncLock,
  SYNC_LOCK_HEARTBEAT_MS,
  SYNC_LOCK_TTL_MS,
  SyncLockError,
} from '../src/store/lock';

const dirs: string[] = [];
async function lockFile(): Promise<string> {
  const dir = path.join(
    os.tmpdir(),
    `stride-lock-${process.pid}-${Math.random().toString(36).slice(2)}`,
  );
  await mkdir(dir, { recursive: true });
  dirs.push(dir);
  return path.join(dir, 'sync.lock');
}

afterEach(async () => {
  vi.useRealTimers();
  for (const d of dirs.splice(0)) await rm(d, { recursive: true, force: true });
});

describe('sync lock heartbeat (AUD-006)', () => {
  it('heartbeats well inside the TTL', () => {
    expect(SYNC_LOCK_HEARTBEAT_MS * 3).toBeLessThan(SYNC_LOCK_TTL_MS);
  });

  it('a sync that runs longer than the TTL keeps its lock', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
    const t0 = Date.parse('2026-07-08T00:00:00Z');
    vi.setSystemTime(t0);
    const file = await lockFile();
    const lock = await acquireSyncLock(file, Date.now());

    // Sleep (e.g. Strava 429 back-off) for twice the TTL.
    vi.advanceTimersByTime(2 * SYNC_LOCK_TTL_MS);
    const record = JSON.parse(await readFile(file, 'utf8'));
    expect(record.token).toBe(lock.token);
    expect(Date.parse(record.at)).toBeGreaterThan(t0 + SYNC_LOCK_TTL_MS);

    // Another process arriving now must NOT see the lock as stale.
    await expect(acquireSyncLock(file, Date.now())).rejects.toBeInstanceOf(SyncLockError);

    await lock.release();
    const next = await acquireSyncLock(file, Date.now());
    await next.release();
  });

  it('without heartbeats (a dead holder) the lock is reclaimed after the TTL', async () => {
    const file = await lockFile();
    const t0 = Date.parse('2026-07-08T00:00:00Z');
    // A record written by a process that died before any heartbeat.
    await writeFile(
      file,
      JSON.stringify({ pid: 1, at: new Date(t0).toISOString(), token: 'dead' }),
    );
    const lock = await acquireSyncLock(file, t0 + SYNC_LOCK_TTL_MS + 1);
    await lock.release();
  });
});

describe('sync lock owner token (AUD-006)', () => {
  it('release() from a non-owner is a no-op', async () => {
    const file = await lockFile();
    const t0 = Date.parse('2026-07-08T00:00:00Z');
    const stalled = await acquireSyncLock(file, t0);
    // Simulate the stalled holder's lock being reclaimed by another process.
    const later = t0 + SYNC_LOCK_TTL_MS + 60_000;
    const owner = await acquireSyncLock(file, later);
    expect(owner.token).not.toBe(stalled.token);

    await stalled.release(); // must NOT delete the new owner's lock
    const record = JSON.parse(await readFile(file, 'utf8'));
    expect(record.token).toBe(owner.token);
    await expect(acquireSyncLock(file, later)).rejects.toBeInstanceOf(SyncLockError);

    await owner.release();
    const free = await acquireSyncLock(file, later);
    await free.release();
  });

  it('release() is idempotent', async () => {
    const file = await lockFile();
    const lock = await acquireSyncLock(file);
    await lock.release();
    await lock.release();
    const again = await acquireSyncLock(file);
    await again.release();
  });
});
