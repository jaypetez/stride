import { formatPace, mpsToSecPerKm } from '@stride/core';
import { getProfile, loadApp } from '../app';
import { dim, heading, info, success, warn } from '../ui';

export async function profileCommand(): Promise<void> {
  const app = loadApp();
  const profile = await getProfile(app.store);
  const goal = await app.store.loadGoal();

  heading('Athlete profile');
  info(`  Name: ${profile.name ?? '(unset)'}`);
  info(`  Experience: ${profile.experienceLevel}`);
  if (profile.age) info(`  Age: ${profile.age}`);
  info(
    `  Threshold speed: ${profile.thresholdSpeedMps ? `${profile.thresholdSpeedMps} m/s (${formatPace(mpsToSecPerKm(profile.thresholdSpeedMps))})` : '(unset — run `stride sync`)'}`,
  );
  info(
    `  LTHR: ${profile.lthr ?? '(unset)'}   MaxHR: ${profile.maxHr ?? '(unset)'}   VDOT: ${profile.vdot ?? '(unset)'}`,
  );
  if (goal) info(`  Goal: ${goal.name ?? goal.distance}${goal.date ? ` on ${goal.date}` : ''}`);
  if (!profile.thresholdSpeedMps)
    warn('No threshold anchor yet — analysis will use HR/duration fallbacks until you sync.');
  dim(
    '\n  Anchors are estimated from your history on `stride sync`. Edit .stride/profile.json to override.',
  );
}

export async function disconnectCommand(opts: { purge?: boolean }): Promise<void> {
  const app = loadApp();
  if (opts.purge) {
    await app.store.clear();
    success('Removed all local Stride data (tokens, activities, profile, plan).');
  } else {
    await app.store.deleteTokens();
    success('Cleared local Strava tokens. Use `--purge` to delete all local data.');
  }
}
