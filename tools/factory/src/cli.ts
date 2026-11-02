#!/usr/bin/env -S npx tsx
/**
 * Stride factory CLI.
 *
 *   pnpm factory validate                       # check the backlog (offline)
 *   pnpm factory stats                          # counts by workstream / phase (offline)
 *   pnpm factory seed [--dry-run] [--skip-projects] [--private]
 *   pnpm factory next [--workstream cloud] [--max-phase P1 | --any-phase] [--all] [--json] [--include-human]
 *     (defaults to --max-phase FOCUS_PHASE from src/focus.ts)
 *   pnpm factory claim <issue#>
 *   pnpm factory release <issue#> [--needs-human]
 *   pnpm factory status <issue#> "<Status>"     # Backlog | Ready | In progress | In review | Done
 *
 * Repo defaults to jaypetez/stride; override with FACTORY_REPO=owner/name.
 */
import { BACKLOG } from './backlog/index.ts';
import { FOCUS_PHASE } from './focus.ts';
import { createCliGh } from './gh.ts';
import { claim, loadCandidates, pick, release, setStatus } from './next.ts';
import { seed } from './seed.ts';
import { PHASES } from './types.ts';
import { validateBacklog } from './validate.ts';

const [cmd = 'help', ...rest] = process.argv.slice(2);
const flag = (name: string) => rest.includes(`--${name}`);
const opt = (name: string) => {
  const i = rest.indexOf(`--${name}`);
  return i >= 0 ? rest[i + 1] : undefined;
};
const [owner, repo] = (process.env.FACTORY_REPO ?? 'jaypetez/stride').split('/') as [
  string,
  string,
];
const out = (s: string) => process.stdout.write(`${s}\n`);

function requireValid(): void {
  const { errors, warnings } = validateBacklog(BACKLOG);
  for (const w of warnings) process.stderr.write(`warn: ${w}\n`);
  if (errors.length) {
    for (const e of errors) process.stderr.write(`error: ${e}\n`);
    process.exit(1);
  }
}

function issueNumber(): number {
  const n = Number(rest.find((a) => /^\d+$/.test(a)));
  if (!n) {
    process.stderr.write('issue number required\n');
    process.exit(2);
  }
  return n;
}

switch (cmd) {
  case 'validate': {
    requireValid();
    const issues = BACKLOG.reduce((n, b) => n + b.issues.length, 0);
    const epics = BACKLOG.reduce((n, b) => n + b.epics.length, 0);
    out(`backlog valid: ${BACKLOG.length} workstreams, ${epics} epics, ${issues} issues`);
    break;
  }
  case 'stats': {
    requireValid();
    const rows = BACKLOG.map((b) => {
      const byPhase = PHASES.map((p) => b.issues.filter((i) => i.phase === p).length);
      const human = b.issues.filter((i) => !i.agent).length;
      return `${b.workstream.key.padEnd(7)} ${String(b.issues.length).padStart(3)}  ${byPhase.map((n) => String(n).padStart(3)).join(' ')}   human:${human}`;
    });
    out(`ws      tot  ${PHASES.map((p) => p.padStart(3)).join(' ')}`);
    for (const r of rows) out(r);
    break;
  }
  case 'seed': {
    requireValid();
    const gh = createCliGh();
    const stats = seed(gh, BACKLOG, {
      owner,
      repo,
      dryRun: flag('dry-run'),
      skipProjects: flag('skip-projects'),
      publicProjects: !flag('private'),
    });
    out(JSON.stringify(stats, null, 2));
    break;
  }
  case 'next': {
    const gh = createCliGh();
    const picked = pick(loadCandidates(gh, owner, repo), {
      workstream: opt('workstream'),
      maxPhase: flag('any-phase') ? undefined : ((opt('max-phase') as any) ?? FOCUS_PHASE),
      includeHuman: flag('include-human'),
    });
    const list = flag('all') ? picked : picked.slice(0, 1);
    if (flag('json')) out(JSON.stringify(list, null, 2));
    else if (!list.length)
      out(
        `No ready issues up to phase ${flag('any-phase') ? 'any' : (opt('max-phase') ?? FOCUS_PHASE)}. (All blocked, claimed, or done — try --any-phase.)`,
      );
    else for (const c of list) out(`#${c.number}\t${c.title}\t${c.url}`);
    break;
  }
  case 'claim':
    claim(createCliGh(), owner, repo, issueNumber());
    out('claimed');
    break;
  case 'release':
    release(createCliGh(), owner, repo, issueNumber(), flag('needs-human'));
    out('released');
    break;
  case 'status': {
    const n = issueNumber();
    const status = rest.find((a) => !/^\d+$/.test(a) && !a.startsWith('--'));
    if (!status) {
      process.stderr.write('status value required\n');
      process.exit(2);
    }
    out(`updated ${setStatus(createCliGh(), owner, repo, n, status)} project item(s)`);
    break;
  }
  default:
    out('usage: pnpm factory <validate|stats|seed|next|claim|release|status> — see src/cli.ts');
}
