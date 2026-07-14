import { Command } from 'commander';
import pc from 'picocolors';
import { analyzeCommand } from './commands/analyze';
import { connectCommand } from './commands/connect';
import { doctorCommand } from './commands/doctor';
import { nextCommand } from './commands/next';
import { planCommand } from './commands/plan';
import { disconnectCommand, profileCommand } from './commands/profile';
import { syncCommand } from './commands/sync';
import { errorMsg } from './ui';

/** Global flags, populated by the preAction hook before any command runs. */
const globals = { verbose: false };

/** Wrap an async action so failures print cleanly and set a non-zero exit code. */
function run<A extends unknown[]>(fn: (...args: A) => Promise<void>) {
  return (...args: A): void => {
    fn(...args).catch((err: unknown) => {
      if (globals.verbose && err instanceof Error) {
        console.error(pc.red(`✗ ${err.name}: ${err.message}`));
        if (err.stack) console.error(pc.dim(err.stack));
        if (err.cause) console.error(pc.dim(`caused by: ${String(err.cause)}`));
      } else {
        errorMsg(err instanceof Error ? err.message : String(err));
        errorMsg('(re-run with --verbose for a stack trace)');
      }
      process.exitCode = 1;
    });
  };
}

const program = new Command();

program
  .name('stride')
  .description('Your Strava agentic coach — analyze workouts, get your next one, and build a plan.')
  .version('0.1.0')
  .option(
    '--now <iso>',
    'pin the reference clock (ISO-8601) for reproducible output; also STRIDE_NOW',
  )
  .option('--verbose', 'print full stack traces on error')
  .hook('preAction', (thisCommand) => {
    const opts = thisCommand.opts<{ now?: string; verbose?: boolean }>();
    if (opts.now) process.env.STRIDE_NOW = opts.now;
    globals.verbose = Boolean(opts.verbose);
    if (opts.verbose && !process.env.STRIDE_LOG) process.env.STRIDE_LOG = 'debug';
  });

program
  .command('connect')
  .description('Authorize Stride with your Strava account (local OAuth)')
  .action(run(connectCommand));

program
  .command('sync')
  .description('Import your Strava activities into the local store')
  .option('--pages <n>', 'number of activity pages to fetch (200/page)')
  .option('--full', 'fetch streams for more recent activities')
  .option('--rebuild', 'force a full re-download and rebuild of the training-load history')
  .option('--backfill', 'force a history backfill (older activities), ignoring the watermark')
  .option('--reconcile', 'remove locally stored activities that were deleted on Strava')
  .action(
    run(
      (opts: {
        pages?: string;
        full?: boolean;
        rebuild?: boolean;
        backfill?: boolean;
        reconcile?: boolean;
      }) => syncCommand(opts),
    ),
  );

program
  .command('analyze')
  .description('Analyze a workout (most recent by default)')
  .argument('[id]', 'activity id to analyze')
  .option('--demo', 'use bundled demo data (no credentials needed)')
  .option('--note <text>', 'how you feel (screened for safety red flags, e.g. "chest pain")')
  .option('--json', 'output machine-readable JSON')
  .action(
    run((id: string | undefined, opts: { demo?: boolean; json?: boolean; note?: string }) =>
      analyzeCommand({ ...opts, id }),
    ),
  );

program
  .command('next')
  .description('Suggest your next workout based on current form')
  .option('--demo', 'use bundled demo data (no credentials needed)')
  .option('--note <text>', 'how you feel (screened for safety red flags, e.g. "chest pain")')
  .option('--json', 'output machine-readable JSON')
  .action(run((opts: { demo?: boolean; json?: boolean; note?: string }) => nextCommand(opts)));

program
  .command('plan')
  .description('Generate a periodized training plan')
  .option('--demo', 'use bundled demo data (no credentials needed)')
  .option('--race <race>', 'goal race: 5k | 10k | half | marathon')
  .option('--weeks <n>', 'plan length in weeks (default 8)')
  .option('--start <date>', 'plan start date (YYYY-MM-DD)')
  .option('--date <date>', 'goal race date (YYYY-MM-DD)')
  .option('--note <text>', 'how you feel (screened for safety red flags, e.g. "chest pain")')
  .option('--json', 'output machine-readable JSON')
  .action(
    run(
      (opts: {
        demo?: boolean;
        race?: string;
        weeks?: string;
        start?: string;
        date?: string;
        json?: boolean;
        note?: string;
      }) => planCommand(opts),
    ),
  );

program
  .command('doctor')
  .description('Preflight: show tooling, configured credentials, and what runs offline')
  .action(run(doctorCommand));

program
  .command('profile')
  .description('Show your athlete profile and anchors')
  .option('--json', 'output machine-readable JSON')
  .option('--screen', 'run the PAR-Q readiness screening (interactive terminal only)')
  .action(run((opts: { json?: boolean; screen?: boolean }) => profileCommand(opts)));

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
