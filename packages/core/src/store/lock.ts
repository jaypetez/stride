import { randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { open, readFile, rm, stat } from 'node:fs/promises';
import { createLogger } from '../log';

const log = createLogger('sync.lock');

/** Thrown when another process already holds the sync lock. */
export class SyncLockError extends Error {
  constructor(message = 'A Strava sync is already in progress.') {
    super(message);
    this.name = 'SyncLockError';
  }
}

/**
 * How long a lock may go WITHOUT a heartbeat before it is presumed stale and
 * reclaimed. A live holder refreshes its record every
 * {@link SYNC_LOCK_HEARTBEAT_MS}, so a sync that legitimately runs (or sleeps
 * through Strava rate-limit back-off, up to ~16 min per retry) far longer than
 * this keeps its lock; only a dead process stops beating.
 */
export const SYNC_LOCK_TTL_MS = 10 * 60 * 1000;

/** How often a held lock refreshes its `at` timestamp (well inside the TTL). */
export const SYNC_LOCK_HEARTBEAT_MS = 60 * 1000;

/**
 * Grace period for an empty/unparseable lock file. A racing process creates the
 * file (exclusive `open`) and only THEN writes its record, so a just-created
 * empty file means another process is mid-write — not a dead lock. We only
 * reclaim an empty file once it has sat untouched longer than this.
 */
export const SYNC_LOCK_EMPTY_GRACE_MS = 5 * 1000;

export interface SyncLock {
  /**
   * Release the lock (idempotent, best-effort). Only removes the lock file while
   * it still carries THIS holder's owner token: if the lock was reclaimed by
   * another process (e.g. after this one stalled past the TTL), release is a
   * no-op and never deletes someone else's lock.
   */
  release(): Promise<void>;
  /** The owner token written into the lock record. */
  readonly token: string;
}

interface LockRecord {
  pid: number;
  at: string;
  /** Random per-acquire owner token; `release`/heartbeat only act on a match. */
  token?: string;
}

function readRecordSync(file: string): Partial<LockRecord> | undefined {
  try {
    return JSON.parse(readFileSync(file, 'utf8')) as Partial<LockRecord>;
  } catch {
    return undefined;
  }
}

/**
 * Keep a held lock fresh: every {@link SYNC_LOCK_HEARTBEAT_MS} rewrite its `at`
 * while the file still carries our token. Synchronous fs calls keep each beat
 * atomic with respect to the event loop (and deterministic under fake timers).
 * The timer is `unref`'d so it never keeps the process alive.
 */
function startHeartbeat(file: string, token: string): () => void {
  const timer = setInterval(() => {
    const record = readRecordSync(file);
    if (record?.token !== token) {
      log.warn('sync lock lost to another process; stopping heartbeat', { file });
      clearInterval(timer);
      return;
    }
    const next: LockRecord = { pid: process.pid, at: new Date().toISOString(), token };
    try {
      writeFileSync(file, `${JSON.stringify(next)}\n`);
    } catch (err) {
      log.warn('sync lock heartbeat failed', { file, err: String(err) });
    }
  }, SYNC_LOCK_HEARTBEAT_MS);
  timer.unref?.();
  return () => clearInterval(timer);
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
    // `file` is a fixed path inside the user's private, single-user Stride data
    // dir (e.g. ~/.stride/sync.lock) — NOT the shared OS temp dir — and 'wx' is
    // an exclusive create that refuses to follow a pre-planted symlink. The
    // CodeQL "insecure temporary file" heuristic assumes a shared temp dir,
    // which does not apply here.
    const handle = await open(file, 'wx'); // codeql[js/insecure-temporary-file]
    const token = randomUUID();
    const record: LockRecord = { pid: process.pid, at: new Date(nowMs).toISOString(), token };
    try {
      await handle.writeFile(`${JSON.stringify(record)}\n`);
    } finally {
      await handle.close();
    }
    const stopHeartbeat = startHeartbeat(file, token);
    let released = false;
    return {
      token,
      release: async () => {
        if (released) return;
        released = true;
        stopHeartbeat();
        // Owner check: never delete a lock another process has since reclaimed.
        if (readRecordSync(file)?.token !== token) return;
        await rm(file, { force: true }).catch(() => {});
      },
    };
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw err;

    let stale = false;
    try {
      // Advisory read to decide staleness only. Any TOCTOU between this read and
      // the reclaim below is benign: the authoritative step is the exclusive
      // `open(..., 'wx')` on retry, which exactly one racer can win. Bounded
      // retries prevent a reclaim loop. (Accepted tradeoff for a dependency-free
      // single-user lock; OS-level locking would need a native dependency.)
      const raw = await readFile(file, 'utf8'); // codeql[js/file-system-race]
      if (!raw.trim()) throw new Error('empty lock file'); // route to the grace check
      const record = JSON.parse(raw) as Partial<LockRecord>;
      const heldAt = record.at ? Date.parse(record.at) : Number.NaN;
      stale = !Number.isFinite(heldAt) || nowMs - heldAt > SYNC_LOCK_TTL_MS;
    } catch {
      // Empty or unparseable lock file: another process may be mid-write (it
      // created the file before writing its record). Don't reclaim immediately —
      // only reclaim if the file has been untouched past the short grace period;
      // a fresh one means a live concurrent writer, so we must not steal it.
      try {
        const { mtimeMs } = await stat(file);
        stale = nowMs - mtimeMs > SYNC_LOCK_EMPTY_GRACE_MS;
      } catch {
        // The file vanished between the failed read and here (released
        // concurrently) → reclaim so the bounded retry re-attempts the create.
        stale = true;
      }
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
