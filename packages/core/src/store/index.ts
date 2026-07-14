import { randomUUID } from 'node:crypto';
import { chmod, mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import {
  Activity,
  AthleteProfile,
  DailyLoad,
  RaceGoal,
  SyncState,
  TrainingPlan,
} from '@stride/schemas';
import { StravaTokens } from '../strava/types';
import { acquireSyncLock, type SyncLock } from './lock';

export { type SyncLock, SyncLockError } from './lock';

const MS_PER_DAY = 86_400_000;

/**
 * Local-first, single-user JSON store under `dataDir` (default `.stride/`).
 *
 * Tokens are written with 0600 permissions. Raw Strava-sourced activity data is
 * subject to a 7-day cache expiry per the Strava API Agreement (see
 * `pruneExpiredStrava`). The derived `daily-loads.json` series is a scientific
 * aggregate (no raw Strava content) and persists durably as the PMC/ACWR source
 * of truth (GOAL.md §7); see `upsertDailyLoads` for how it stays compliant.
 *
 * Writes are atomic (temp file + rename) and serialized through an in-process
 * mutex so a sync's multi-file read-modify-write cannot interleave with another
 * writer. A cross-process advisory lock (`sync.lock`) guards the same across
 * processes.
 */
export class LocalStore {
  readonly dir: string;
  /** In-process write mutex: all writes chain off this promise, serialized. */
  private writeChain: Promise<unknown> = Promise.resolve();

  constructor(dataDir: string) {
    this.dir = path.resolve(dataDir);
  }

  private file(name: string): string {
    return path.join(this.dir, name);
  }

  private async ensureDir(): Promise<void> {
    await mkdir(this.dir, { recursive: true });
  }

  private async readJson<T>(name: string): Promise<T | null> {
    try {
      const raw = await readFile(this.file(name), 'utf8');
      return JSON.parse(raw) as T;
    } catch (err) {
      if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null;
      throw err;
    }
  }

  /** Serialize a write behind any in-flight writes (see `writeChain`). */
  private enqueueWrite<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.writeChain.then(fn, fn);
    // Keep the chain alive regardless of outcome, without leaking rejections.
    this.writeChain = run.then(
      () => undefined,
      () => undefined,
    );
    return run;
  }

  /**
   * Atomic write: create a unique temp file in the same directory (with the
   * requested mode UP FRONT, so sensitive files are 0600 from creation — no
   * chmod-after race), fsync-free rename over the target (atomic on the same
   * filesystem), and clean up the temp on failure.
   */
  private writeJson(name: string, data: unknown, mode?: number): Promise<void> {
    return this.enqueueWrite(async () => {
      await this.ensureDir();
      const file = this.file(name);
      const tmp = this.file(`.${name}.${process.pid}.${randomUUID()}.tmp`);
      const json = `${JSON.stringify(data, null, 2)}\n`;
      try {
        // Exclusive create ('wx') — the random name can't collide, and 'wx'
        // refuses to follow a pre-planted symlink at the temp path. Restrictive
        // mode is applied up front so sensitive files are never briefly loose.
        await writeFile(tmp, json, {
          encoding: 'utf8',
          flag: 'wx',
          ...(mode !== undefined ? { mode } : {}),
        });
        if (mode !== undefined) {
          // Belt-and-suspenders: umask can loosen the create mode; force it.
          await chmod(tmp, mode).catch(() => {
            /* chmod may be a no-op on some filesystems (e.g. Windows) */
          });
        }
        await rename(tmp, file);
      } finally {
        // No-op after a successful rename; cleans up a leftover temp on failure.
        await rm(tmp, { force: true }).catch(() => {});
      }
    });
  }

  /**
   * Acquire the cross-process sync lock (`sync.lock`). Wrap a sync body in
   * `acquire`/`finally`-release so two processes cannot sync concurrently.
   */
  async acquireSyncLock(nowMs: number = Date.now()): Promise<SyncLock> {
    await this.ensureDir();
    return acquireSyncLock(this.file('sync.lock'), nowMs);
  }

  // --- Tokens (sensitive: 0600) ---
  async saveTokens(tokens: StravaTokens): Promise<void> {
    await this.writeJson('tokens.json', tokens, 0o600);
  }

  async loadTokens(): Promise<StravaTokens | null> {
    const raw = await this.readJson('tokens.json');
    return raw ? StravaTokens.parse(raw) : null;
  }

  async deleteTokens(): Promise<void> {
    await rm(this.file('tokens.json'), { force: true });
  }

  // --- Athlete profile ---
  async saveProfile(profile: AthleteProfile): Promise<void> {
    await this.writeJson('profile.json', profile);
  }

  async loadProfile(): Promise<AthleteProfile | null> {
    const raw = await this.readJson('profile.json');
    return raw ? AthleteProfile.parse(raw) : null;
  }

  // --- Activities (raw Strava-sourced data: 7-day cache limit) ---
  async saveActivities(activities: Activity[]): Promise<void> {
    await this.writeJson('activities.json', activities);
  }

  async loadActivities(): Promise<Activity[]> {
    const raw = await this.readJson<unknown[]>('activities.json');
    if (!raw) return [];
    return raw.map((a) => Activity.parse(a));
  }

  /**
   * Enforce the Strava 7-day cache limit: drop Strava-sourced activities whose
   * `fetchedAt` is older than `maxAgeDays`. Upload/manual data is retained.
   * Returns the number of activities removed.
   */
  async pruneExpiredStrava(nowMs: number = Date.now(), maxAgeDays = 7): Promise<number> {
    const activities = await this.loadActivities();
    const cutoff = nowMs - maxAgeDays * MS_PER_DAY;
    const kept = activities.filter((a) => {
      if (a.source !== 'strava') return true;
      if (!a.fetchedAt) return false;
      return Date.parse(a.fetchedAt) >= cutoff;
    });
    if (kept.length !== activities.length) await this.saveActivities(kept);
    return activities.length - kept.length;
  }

  // --- Durable daily-load series (GOAL.md §7 source of truth) ---
  async saveDailyLoads(loads: DailyLoad[]): Promise<void> {
    await this.writeJson('daily-loads.json', loads);
  }

  async loadDailyLoads(): Promise<DailyLoad[]> {
    const raw = await this.readJson<unknown[]>('daily-loads.json');
    if (!raw) return [];
    return raw.map((d) => DailyLoad.parse(d));
  }

  /**
   * Merge a freshly recomputed daily-load series into the durable one:
   * recomputed dates overwrite existing entries; all other historical days are
   * preserved (this is what lets the PMC outlive the 7-day raw cache).
   *
   * `authoritativeDates` marks dates for which `recomputed` is the source of
   * truth: if a recompute produced NO entry for such a date, the day genuinely
   * has no load now (e.g. its only activity was deleted upstream during a
   * reconciliation), so the stale durable entry is DELETED rather than
   * preserved. A normal merge can only ever overwrite a date it contains, never
   * remove one — so without this the phantom load would linger until a rebuild.
   *
   * Compliance: once a day freezes past the raw-retention window
   * (`date < retentionCutoffDate`) its raw Strava activities no longer exist
   * locally, so we drop the `activityIds` we can no longer back with retained
   * data. The derived scalar aggregate (tss/duration/distance) stays. Returns
   * the merged, date-sorted series that was written.
   */
  async upsertDailyLoads(
    recomputed: DailyLoad[],
    retentionCutoffDate: string,
    authoritativeDates?: Iterable<string>,
  ): Promise<DailyLoad[]> {
    const existing = await this.loadDailyLoads();
    const byDate = new Map<string, DailyLoad>(existing.map((d) => [d.date, d]));
    for (const d of recomputed) byDate.set(d.date, d);
    if (authoritativeDates) {
      const recomputedDates = new Set(recomputed.map((d) => d.date));
      for (const date of authoritativeDates) {
        if (!recomputedDates.has(date)) byDate.delete(date);
      }
    }
    const merged = [...byDate.values()]
      .map((d) =>
        d.date < retentionCutoffDate && d.activityIds.length > 0 ? { ...d, activityIds: [] } : d,
      )
      .sort((a, b) => a.date.localeCompare(b.date));
    await this.saveDailyLoads(merged);
    return merged;
  }

  // --- Sync bookkeeping (durable process metadata, no Strava content) ---
  async saveSyncState(state: SyncState): Promise<void> {
    await this.writeJson('meta.json', state);
  }

  async loadSyncState(): Promise<SyncState | null> {
    const raw = await this.readJson('meta.json');
    return raw ? SyncState.parse(raw) : null;
  }

  // --- Training plan & goal ---
  async savePlan(plan: TrainingPlan): Promise<void> {
    await this.writeJson('plan.json', plan);
  }

  async loadPlan(): Promise<TrainingPlan | null> {
    const raw = await this.readJson('plan.json');
    return raw ? TrainingPlan.parse(raw) : null;
  }

  async saveGoal(goal: RaceGoal): Promise<void> {
    await this.writeJson('goal.json', goal);
  }

  async loadGoal(): Promise<RaceGoal | null> {
    const raw = await this.readJson('goal.json');
    return raw ? RaceGoal.parse(raw) : null;
  }

  /** Delete all locally stored data (deauthorization / right-to-delete). */
  async clear(): Promise<void> {
    await rm(this.dir, { recursive: true, force: true });
  }
}
