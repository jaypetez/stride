import * as clack from '@clack/prompts';
import {
  deauthorize,
  formatPace,
  mpsToSecPerKm,
  PARQ_QUESTIONS,
  screenReadiness,
} from '@stride/core';
import { AthleteProfile } from '@stride/schemas';
import type { App } from '../app';
import { getProfile, loadApp, todayIso } from '../app';
import { dim, heading, info, success, warn } from '../ui';

export async function profileCommand(
  opts: { json?: boolean; screen?: boolean } = {},
): Promise<void> {
  const app = loadApp();
  const profile = await getProfile(app.store);
  const goal = await app.store.loadGoal();

  if (opts.json) {
    console.log(JSON.stringify({ profile, goal }, null, 2));
    return;
  }

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
  info(`  Medical clearance: ${profile.medicalClearance ? 'yes' : 'not screened'}`);
  if (profile.healthFlags.length > 0)
    warn(`Health screening flags: ${profile.healthFlags.join(', ')}`);
  if (goal) info(`  Goal: ${goal.name ?? goal.distance}${goal.date ? ` on ${goal.date}` : ''}`);
  if (!profile.thresholdSpeedMps)
    warn('No threshold anchor yet — analysis will use HR/duration fallbacks until you sync.');
  dim(
    '\n  Anchors are estimated from your history on `stride sync`. Edit .stride/profile.json to override.',
  );

  await maybeScreen(app, profile, opts);
}

/**
 * Offer the PAR-Q readiness screening (GOAL §8) and persist the outcome onto the
 * profile (`medicalClearance` / `healthFlags`), which then feeds `detectRedFlags`
 * on every later request. Interactive only: skips cleanly when stdin is not a TTY
 * or `--json` was passed, so scripts and tests never block on a prompt.
 */
async function maybeScreen(
  app: App,
  profile: AthleteProfile,
  opts: { json?: boolean; screen?: boolean },
): Promise<void> {
  const interactive = Boolean(process.stdin.isTTY) && !opts.json;
  if (!interactive) {
    if (opts.screen) dim('\n  (Screening needs an interactive terminal — skipped.)');
    return;
  }

  const start =
    opts.screen ||
    (await clack.confirm({
      message: 'Run the PAR-Q readiness screening now? (7 quick yes/no questions)',
      initialValue: false,
    }));
  if (clack.isCancel(start) || !start) return;

  clack.intro('PAR-Q readiness screening');
  const answers: boolean[] = [];
  for (const question of PARQ_QUESTIONS) {
    const ans = await clack.confirm({ message: question, initialValue: false });
    if (clack.isCancel(ans)) {
      clack.cancel('Screening cancelled — profile unchanged.');
      return;
    }
    answers.push(ans);
  }

  const result = screenReadiness(answers);
  const updated = AthleteProfile.parse({
    ...profile,
    medicalClearance: result.cleared,
    healthFlags: result.healthFlags,
    updatedAt: todayIso(app.config),
  });
  await app.store.saveProfile(updated);

  if (result.cleared) {
    clack.outro('No flags raised — marked as medically cleared to begin.');
    success('Readiness screening complete: cleared.');
  } else {
    clack.outro(`Flags raised: ${result.healthFlags.join(', ')}.`);
    warn('Get medical clearance before hard training; these flags now constrain coaching.');
  }
}

export async function disconnectCommand(opts: { purge?: boolean }): Promise<void> {
  const app = loadApp();

  // Revoke the grant on Strava's side first, best-effort: a failed or offline
  // revoke must never block the local cleanup below (both normal and --purge).
  const tokens = await app.store.loadTokens();
  if (tokens) {
    try {
      await deauthorize(tokens.accessToken);
    } catch (err) {
      warn(
        `Could not revoke access on Strava (${(err as Error).message}). Removing local data ` +
          'anyway — you can also revoke Stride at https://www.strava.com/settings/apps.',
      );
    }
  }

  if (opts.purge) {
    await app.store.clear();
    success('Removed all local Stride data (tokens, activities, profile, plan).');
  } else {
    await app.store.deleteTokens();
    success('Cleared local Strava tokens. Use `--purge` to delete all local data.');
  }
}
