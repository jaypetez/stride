import { createHash } from 'node:crypto';
import {
  type Epic,
  type Issue,
  PHASE_DESCRIPTIONS,
  PHASE_TITLES,
  PHASES,
  type Workstream,
  type WorkstreamBacklog,
} from './types.ts';

export const PROJECT_PREFIX = 'Stride · ';
export const ORCHESTRATOR_TITLE = `${PROJECT_PREFIX}Factory (Orchestrator)`;

/** Labels the factory owns. `gh label create --force` style: name → [color, description]. */
export function factoryLabels(backlogs: WorkstreamBacklog[]): Record<string, [string, string]> {
  const labels: Record<string, [string, string]> = {
    factory: ['5319e7', 'Managed by tools/factory (backlog-as-code)'],
    epic: ['3e4b9e', 'Parent issue grouping sub-issues'],
    'agent:ok': ['0e8a16', 'An autonomous agent may pick this up'],
    'human-only': ['b60205', 'Needs a human: accounts, secrets, legal sign-off'],
    'status:in-progress': ['fbca04', 'Claimed by an agent or human'],
    'status:needs-human': ['d93f0b', 'Agent hit a stop condition — needs a human'],
    'priority:high': ['b60205', 'High priority'],
    'priority:medium': ['fbca04', 'Medium priority'],
    'priority:low': ['c2e0c6', 'Low priority'],
  };
  for (const p of PHASES) labels[`phase:${p}`] = ['1d76db', PHASE_TITLES[p]];
  for (const s of ['XS', 'S', 'M', 'L']) labels[`size:${s}`] = ['ededed', `Size ${s}`];
  for (const t of ['feature', 'task', 'spike', 'docs', 'test', 'chore']) {
    labels[`type:${t}`] = ['c5def5', `Type: ${t}`];
  }
  for (const { workstream: ws } of backlogs) {
    labels[`area:${ws.area}`] = ['0052cc', `${ws.title} workstream`];
  }
  return labels;
}

export function issueLabels(ws: Workstream, i: Issue): string[] {
  return [
    'factory',
    `area:${ws.area}`,
    `phase:${i.phase}`,
    `priority:${i.priority}`,
    `size:${i.size}`,
    `type:${i.type}`,
    i.agent ? 'agent:ok' : 'human-only',
  ];
}

export function epicLabels(ws: Workstream, e: Epic): string[] {
  return ['factory', 'epic', `area:${ws.area}`, `phase:${e.phase}`];
}

export const issueTitle = (i: { key: string; title: string }): string => `[${i.key}] ${i.title}`;

const KEY_RE = /<!-- factory-key: ([A-Z]+-E?\d+) -->/;
const HASH_RE = /<!-- factory-hash: ([0-9a-f]+) -->/;

export function parseKey(body: string | null | undefined): string | null {
  return body?.match(KEY_RE)?.[1] ?? null;
}
export function parseHash(body: string | null | undefined): string | null {
  return body?.match(HASH_RE)?.[1] ?? null;
}

const hash = (s: string): string => createHash('sha256').update(s).digest('hex').slice(0, 16);

/** Resolves an issue key to `#123 (KEY)` when the number is known. */
export type NumberLookup = (key: string) => number | undefined;

const ref = (key: string, num: NumberLookup): string => {
  const n = num(key);
  return n ? `#${n} \`${key}\`` : `\`${key}\``;
};

const FOOTER = (file: string) =>
  `\n---\n_Managed by \`tools/factory\`. Edit \`tools/factory/src/backlog/${file}.ts\` and run \`pnpm factory seed\` — manual edits to this body are overwritten when the backlog entry changes._`;

function stamp(key: string, content: string): string {
  return `<!-- factory-key: ${key} -->\n<!-- factory-hash: ${hash(content)} -->\n${content}`;
}

export function renderIssueBody(
  ws: Workstream,
  i: Issue,
  num: NumberLookup,
  file = ws.key.toLowerCase(),
): string {
  const meta = [
    `**Workstream:** ${ws.title}`,
    `**Phase:** ${PHASE_TITLES[i.phase]}`,
    `**Priority:** ${i.priority}`,
    `**Size:** ${i.size}`,
    `**Type:** ${i.type}`,
    `**Agent-ready:** ${i.agent ? 'yes' : 'no — human-only'}`,
  ].join(' · ');
  const parts = [
    `> ${meta}`,
    '',
    '## Context',
    i.context.trim(),
    '',
    '## Acceptance criteria',
    ...i.acceptance.map((a) => `- [ ] ${a}`),
    '',
    '## Test plan (must pass offline, in CI)',
    ...i.testPlan.map((t) => `- ${t}`),
  ];
  if (i.files?.length) parts.push('', '## Files', ...i.files.map((f) => `- \`${f}\``));
  if (i.dependsOn?.length) {
    parts.push(
      '',
      '## Depends on',
      ...i.dependsOn.map((d) => `- ${ref(d, num)}`),
      '',
      '_Native "blocked by" links are also set; the dispatcher skips this issue until they close._',
    );
  }
  parts.push(
    '',
    '## Definition of done',
    '- [ ] `pnpm check` green (and `pnpm verify` for cross-surface changes; `pnpm check:android` when `android/` changes)',
    '- [ ] Tests added for new runtime behaviour; all offline (no secrets, no network)',
    '- [ ] PR body has `Closes #<this issue>`; commits signed off (`git commit -s`)',
  );
  parts.push(FOOTER(file));
  return stamp(i.key, parts.join('\n'));
}

export function renderEpicBody(
  ws: Workstream,
  e: Epic,
  children: Issue[],
  num: NumberLookup,
  file = ws.key.toLowerCase(),
): string {
  const parts = [
    `> **Epic** · **Workstream:** ${ws.title} · **Phase:** ${PHASE_TITLES[e.phase]}`,
    '',
    e.summary.trim(),
    '',
    '## Issues',
    ...children.map((c) => `- ${ref(c.key, num)} ${c.title}`),
    '',
    '_Tracked as native sub-issues; this epic closes when all of them do._',
    FOOTER(file),
  ];
  return stamp(e.key, parts.join('\n'));
}

export function renderProjectReadme(ws: Workstream | null, backlogs: WorkstreamBacklog[]): string {
  if (!ws) {
    return [
      '# Stride Factory — Orchestrator',
      '',
      'Every factory issue across all workstreams. Agents pull work with `pnpm factory next` / the `/factory-next` skill.',
      '',
      '## Phases',
      ...PHASES.map((p) => `- **${PHASE_TITLES[p]}** — ${PHASE_DESCRIPTIONS[p]}`),
      '',
      '## Workstreams',
      ...backlogs.map((b) => `- **${b.workstream.title}** (\`area:${b.workstream.area}\`)`),
      '',
      'Runbook: `docs/factory/README.md` in jaypetez/stride.',
    ].join('\n');
  }
  return [`# ${ws.title}`, '', ws.description, '', 'Runbook: `docs/factory/README.md`.'].join('\n');
}
