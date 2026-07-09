import { chmod, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { Activity, AthleteProfile, RaceGoal, TrainingPlan } from '@stride/schemas';
import { StravaTokens } from '../strava/types';

const MS_PER_DAY = 86_400_000;

/**
 * Local-first, single-user JSON store under `dataDir` (default `.stride/`).
 * Tokens are written with 0600 permissions. Strava-sourced activity data is
 * subject to a 7-day cache expiry per the Strava API Agreement.
 */
export class LocalStore {
  readonly dir: string;

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

  private async writeJson(name: string, data: unknown, mode?: number): Promise<void> {
    await this.ensureDir();
    const file = this.file(name);
    await writeFile(file, `${JSON.stringify(data, null, 2)}\n`, 'utf8');
    if (mode !== undefined) {
      await chmod(file, mode).catch(() => {
        /* chmod is a no-op / may fail on some filesystems (e.g. Windows) */
      });
    }
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

  // --- Activities ---
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
