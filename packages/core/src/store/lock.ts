import { open, readFile, rm } from 'node:fs/promises';

/** Thrown when another process already holds the sync lock. */
export class SyncLockError extends Error {
  constructor(message = 'A Strava sync is already in progress.') {
    super(message);
    this.name = 'SyncLockError';
  }
}

/** How long a lock may be held before it is presumed stale and reclaimed. */
export const SYNC_LOCK_TTL_MS = 10 * 60 * 1000;

export interface SyncLock {
  /** Release the lock (idempotent, best-effort). */
  release(): Promise<void>;
}

interface LockRecord {
  pid: number;
  at: string;
}

/**
 * Acquire a cross-process advisory lock by exclusively creating `file`
 * (`open(..., 'wx')`). If the file already exists the holder is inspected: a
 * lock older than {@link SYNC_LOCK_TTL_MS} (or unreadable/corrupt) is presumed
 * dead and reclaimed; otherwise a {@link SyncLockError} is thrown. Only advisory
 * — it coordinates well-behaved Stride processes, not arbitrary writers.
 */
export async function acquireSyncLock(
  file: string,
  nowMs: number = Date.now(),
  attempt = 0,
): Promise<SyncLock> {
  try {
    const handle = await open(file, 'wx');
    const record: LockRecord = { pid: process.pid, at: new Date(nowMs).toISOString() };
    try {
      await handle.writeFile(`${JSON.stringify(record)}\n`);
    } finally {
      await handle.close();
    }
    return { release: async () => rm(file, { force: true }).catch(() => {}) };
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err;

    let stale = false;
    try {
      const raw = await readFile(file, 'utf8');
      const record = JSON.parse(raw) as Partial<LockRecord>;
      const heldAt = record.at ? Date.parse(record.at) : Number.NaN;
      stale = !Number.isFinite(heldAt) || nowMs - heldAt > SYNC_LOCK_TTL_MS;
    } catch {
      // Unreadable or corrupt lock file → treat as stale and reclaim.
      stale = true;
    }

    if (!stale || attempt >= 3) {
      throw new SyncLockError(
        'A Strava sync is already in progress. Wait for it to finish, or remove the ' +
          'stale lock file if you are sure no sync is running.',
      );
    }
    // Reclaim the stale lock and retry (bounded, to avoid a reclaim race loop).
    await rm(file, { force: true }).catch(() => {});
    return acquireSyncLock(file, nowMs, attempt + 1);
  }
}
