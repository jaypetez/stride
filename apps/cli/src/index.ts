import { Command } from 'commander';
import pc from 'picocolors';
import { analyzeCommand } from './commands/analyze';
import { connectCommand } from './commands/connect';
import { nextCommand } from './commands/next';
import { planCommand } from './commands/plan';
import { disconnectCommand, profileCommand } from './commands/profile';
import { syncCommand } from './commands/sync';
import { errorMsg } from './ui';

/** Wrap an async action so failures print cleanly and set a non-zero exit code. */
function run<A extends unknown[]>(fn: (...args: A) => Promise<void>) {
  return (...args: A): void => {
    fn(...args).catch((err: unknown) => {
      errorMsg(err instanceof Error ? err.message : String(err));
      process.exitCode = 1;
    });
  };
}

const program = new Command();

program
  .name('stride')
  .description('Your Strava agentic coach — analyze workouts, get your next one, and build a plan.')
  .version('0.1.0');

program
  .command('connect')
  .description('Authorize Stride with your Strava account (local OAuth)')
  .action(run(connectCommand));

program
  .command('sync')
  .description('Import your Strava activities into the local store')
  .option('--pages <n>', 'number of activity pages to fetch (200/page)')
  .option('--full', 'fetch streams for more recent activities')
  .action(run((opts: { pages?: string; full?: boolean }) => syncCommand(opts)));

program
  .command('analyze')
  .description('Analyze a workout (most recent by default)')
  .argument('[id]', 'activity id to analyze')
  .option('--demo', 'use bundled demo data (no credentials needed)')
  .action(
    run((id: string | undefined, opts: { demo?: boolean }) => analyzeCommand({ ...opts, id })),
  );

program
  .command('next')
  .description('Suggest your next workout based on current form')
  .option('--demo', 'use bundled demo data (no credentials needed)')
  .action(run((opts: { demo?: boolean }) => nextCommand(opts)));

program
  .command('plan')
  .description('Generate a periodized training plan')
  .option('--demo', 'use bundled demo data (no credentials needed)')
  .option('--race <race>', 'goal race: 5k | 10k | half | marathon')
  .option('--weeks <n>', 'plan length in weeks (default 8)')
  .option('--start <date>', 'plan start date (YYYY-MM-DD)')
  .option('--date <date>', 'goal race date (YYYY-MM-DD)')
  .action(
    run((opts: { demo?: boolean; race?: string; weeks?: string; start?: string; date?: string }) =>
      planCommand(opts),
    ),
  );

program
  .command('profile')
  .description('Show your athlete profile and anchors')
  .action(run(profileCommand));

program
  .command('disconnect')
  .description('Remove local Strava tokens')
  .option('--purge', 'also delete all local activities, profile, and plan')
  .action(run((opts: { purge?: boolean }) => disconnectCommand(opts)));

program.parseAsync(process.argv).catch((err: unknown) => {
  errorMsg(err instanceof Error ? err.message : String(err));
  process.exitCode = 1;
});

if (process.argv.length <= 2) {
  console.log(
    pc.dim('Run `stride --help` to see available commands, or try `stride analyze --demo`.'),
  );
}
