import { describe, expect, it } from 'vitest';
import { BACKLOG } from '../src/backlog/index.ts';
import { FOCUS_PHASE } from '../src/focus.ts';
import type { GhClient } from '../src/gh.ts';
import { type Candidate, isReady, pick, rank } from '../src/next.ts';
import { factoryLabels, parseHash, parseKey, renderIssueBody } from '../src/render.ts';
import { projectFieldSpecs, projectKeys, seed, topoOrder } from '../src/seed.ts';
import type { Issue, WorkstreamBacklog } from '../src/types.ts';
import { ANCHORS, phaseIndex, validateBacklog } from '../src/validate.ts';

const issue = (key: string, extra: Partial<Issue> = {}): Issue => ({
  key,
  epic: `${key.split('-')[0]}-E1`,
  title: `Do ${key}`,
  phase: 'P0',
  priority: 'medium',
  size: 'S',
  type: 'task',
  agent: true,
  context: 'ctx',
  acceptance: ['a'],
  testPlan: ['t'],
  ...extra,
});

const mini = (issues: Issue[]): WorkstreamBacklog[] => [
  {
    workstream: { key: 'AAA', title: 'A', area: 'a', description: 'd' },
    epics: [{ key: 'AAA-E1', title: 'E', phase: 'P0', summary: 's' }],
    issues,
  },
];

const noAnchors = (errors: string[]) => errors.filter((e) => !e.startsWith('missing anchor'));

describe('the real backlog', () => {
  const result = validateBacklog(BACKLOG);

  it('is valid (keys, epics, deps resolve, no cycles, no phase inversions, anchors present)', () => {
    expect(result.errors).toEqual([]);
  });

  it('defines every cross-workstream anchor', () => {
    const keys = new Set(BACKLOG.flatMap((b) => b.issues.map((i) => i.key)));
    for (const a of ANCHORS) expect(keys.has(a), a).toBe(true);
  });

  it('has agent-startable work in P0 (no deps, agent:ok)', () => {
    const starters = BACKLOG.flatMap((b) => b.issues).filter(
      (i) => i.phase === 'P0' && i.agent && !(i.dependsOn ?? []).length,
    );
    expect(starters.length).toBeGreaterThan(0);
  });

  it('keeps every issue dependency inside its phase or earlier', () => {
    const all = new Map(BACKLOG.flatMap((b) => b.issues).map((i) => [i.key, i]));
    for (const i of all.values()) {
      for (const d of i.dependsOn ?? []) {
        expect(phaseIndex(all.get(d)!.phase)).toBeLessThanOrEqual(phaseIndex(i.phase));
      }
    }
  });

  it('only parks work in the LATER workstream as phase Later', () => {
    const later = BACKLOG.find((b) => b.workstream.key === 'LATER')!;
    expect(later.issues.every((i) => i.phase === 'Later')).toBe(true);
  });

  it('produces labels for every area', () => {
    const labels = factoryLabels(BACKLOG);
    for (const b of BACKLOG) expect(labels[`area:${b.workstream.area}`]).toBeDefined();
  });

  it('declares a Workstream field only on the orchestrator', () => {
    expect(projectFieldSpecs(BACKLOG, true).map((f) => f.name)).toContain('Workstream');
    expect(projectFieldSpecs(BACKLOG, false).map((f) => f.name)).not.toContain('Workstream');
  });
});

describe('validateBacklog', () => {
  it('detects unknown deps', () => {
    const { errors } = validateBacklog(mini([issue('AAA-001', { dependsOn: ['AAA-999'] })]));
    expect(noAnchors(errors)).toEqual(['AAA-001: depends on unknown AAA-999']);
  });

  it('detects cycles', () => {
    const { errors } = validateBacklog(
      mini([
        issue('AAA-001', { dependsOn: ['AAA-002'] }),
        issue('AAA-002', { dependsOn: ['AAA-001'] }),
      ]),
    );
    expect(noAnchors(errors).some((e) => e.startsWith('dependency cycle'))).toBe(true);
  });

  it('detects phase inversions', () => {
    const { errors } = validateBacklog(
      mini([issue('AAA-001', { dependsOn: ['AAA-002'] }), issue('AAA-002', { phase: 'P3' })]),
    );
    expect(noAnchors(errors)).toEqual(['AAA-001 (P0) depends on later-phase AAA-002 (P3)']);
  });

  it('requires acceptance criteria and a test plan', () => {
    const { errors } = validateBacklog(mini([issue('AAA-001', { acceptance: [], testPlan: [] })]));
    expect(noAnchors(errors)).toEqual(['AAA-001: no acceptance criteria', 'AAA-001: no test plan']);
  });
});

describe('render', () => {
  const [b] = mini([issue('AAA-001', { dependsOn: ['AAA-002'] })]);
  it('embeds a parseable key and a stable hash', () => {
    const body1 = renderIssueBody(b!.workstream, b!.issues[0]!, () => undefined);
    const body2 = renderIssueBody(b!.workstream, b!.issues[0]!, () => undefined);
    expect(parseKey(body1)).toBe('AAA-001');
    expect(parseHash(body1)).toBe(parseHash(body2));
  });
  it('changes the hash when a dependency number becomes known', () => {
    const before = renderIssueBody(b!.workstream, b!.issues[0]!, () => undefined);
    const after = renderIssueBody(b!.workstream, b!.issues[0]!, () => 42);
    expect(after).toContain('#42');
    expect(parseHash(after)).not.toBe(parseHash(before));
  });
});

describe('dispatcher', () => {
  const c = (key: string, labels: string[], extra: Partial<Candidate> = {}): Candidate => ({
    number: Number(key.split('-')[1]),
    key,
    title: key,
    url: '',
    labels: ['factory', 'agent:ok', ...labels],
    assignees: 0,
    openBlockers: 0,
    openPrs: 0,
    ...extra,
  });

  it('skips epics, blocked, claimed, PR-in-flight and human-only issues', () => {
    expect(isReady(c('A-001', ['epic']))).toBe(false);
    expect(isReady(c('A-002', [], { openBlockers: 1 }))).toBe(false);
    expect(isReady(c('A-003', [], { assignees: 1 }))).toBe(false);
    expect(isReady(c('A-004', ['status:in-progress']))).toBe(false);
    expect(isReady(c('A-005', [], { openPrs: 1 }))).toBe(false);
    expect(isReady({ ...c('A-006', []), labels: ['factory', 'human-only'] })).toBe(false);
    expect(isReady(c('A-007', []))).toBe(true);
  });

  it('ranks by phase, then priority, then key', () => {
    const order = rank([
      c('B-002', ['phase:P1', 'priority:high']),
      c('B-001', ['phase:P0', 'priority:low']),
      c('A-009', ['phase:P0', 'priority:high']),
      c('A-001', ['phase:P0', 'priority:low']),
    ]).map((x) => x.key);
    expect(order).toEqual(['A-009', 'A-001', 'B-001', 'B-002']);
  });

  it('filters by workstream area or key prefix and by max phase', () => {
    const cands = [
      c('CLOUD-001', ['area:cloud', 'phase:P0']),
      c('ANDF-001', ['area:android', 'phase:P0']),
      c('CLOUD-009', ['area:cloud', 'phase:P3']),
    ];
    expect(pick(cands, { workstream: 'cloud' }).map((x) => x.key)).toEqual([
      'CLOUD-001',
      'CLOUD-009',
    ]);
    expect(pick(cands, { workstream: 'ANDF' }).map((x) => x.key)).toEqual(['ANDF-001']);
    expect(pick(cands, { maxPhase: 'P1' }).map((x) => x.key)).toEqual(['ANDF-001', 'CLOUD-001']);
  });
});

describe('topoOrder', () => {
  it('puts every real-backlog issue after its dependencies', () => {
    const order = topoOrder(BACKLOG.flatMap((b) => b.issues));
    const pos = new Map(order.map((i, n) => [i.key, n]));
    expect(order.length).toBe(BACKLOG.reduce((n, b) => n + b.issues.length, 0));
    for (const i of order) {
      for (const d of i.dependsOn ?? []) expect(pos.get(d)!).toBeLessThan(pos.get(i.key)!);
    }
  });
});

describe('seed (dry run against an empty repo)', () => {
  it('plans one create per epic and issue and never mutates', () => {
    const mutations: string[] = [];
    const fake: GhClient = {
      graphql(query: string): any {
        if (/^\s*mutation/.test(query)) mutations.push(query);
        if (query.includes('user(login:$o){ id }'))
          return { repository: { id: 'R' }, user: { id: 'U' } };
        if (query.includes('projectsV2')) return { user: { projectsV2: { nodes: [] } } };
        return {
          repository: { issues: { nodes: [], pageInfo: { hasNextPage: false, endCursor: null } } },
        };
      },
      rest(method: string, path: string): any {
        if (method !== 'GET') mutations.push(`${method} ${path}`);
        return [];
      },
    };
    const stats = seed(fake, BACKLOG, { owner: 'o', repo: 'r', dryRun: true, log: () => {} });
    const epics = BACKLOG.reduce((n, b) => n + b.epics.length, 0);
    const issues = BACKLOG.reduce((n, b) => n + b.issues.length, 0);
    expect(stats['issue.create']).toBe(epics + issues);
    expect(stats['project.create']).toBe(BACKLOG.length + 1);
    expect(stats['subissue.add']).toBe(issues);
    expect(mutations).toEqual([]);
  });
});

describe('MVP focus', () => {
  const all = BACKLOG.flatMap((b) => b.issues);

  it('focuses the factory on the MVP phase, ordered first', () => {
    expect(FOCUS_PHASE).toBe('MVP');
    expect(phaseIndex('MVP')).toBe(0);
  });

  it('keeps MVP issues free of dependencies on any later phase', () => {
    const byKey = new Map(all.map((i) => [i.key, i]));
    for (const i of all.filter((x) => x.phase === 'MVP')) {
      for (const d of i.dependsOn ?? [])
        expect(byKey.get(d)!.phase, `${i.key} -> ${d}`).toBe('MVP');
    }
  });

  it('has agent-startable MVP work with no dependencies', () => {
    const starters = all.filter((i) => i.phase === 'MVP' && i.agent && !(i.dependsOn ?? []).length);
    expect(starters.length).toBeGreaterThan(0);
  });

  it('collects every MVP-phase issue into the MVP Project via includePhases', () => {
    const mvp = BACKLOG.find((b) => b.workstream.key === 'MVP')!;
    const keys = new Set(projectKeys(BACKLOG, mvp));
    for (const i of all.filter((x) => x.phase === 'MVP')) expect(keys.has(i.key), i.key).toBe(true);
    // ...and nothing from later phases in other workstreams.
    expect(keys.has('CLOUD-001')).toBe(false);
  });

  it('does not pull other phases into ordinary workstream Projects', () => {
    const cloud = BACKLOG.find((b) => b.workstream.key === 'CLOUD')!;
    expect(projectKeys(BACKLOG, cloud).every((k) => k.startsWith('CLOUD-'))).toBe(true);
  });
});

describe('seed: stale blocked-by links', () => {
  it('removes factory-owned links that left dependsOn, keeps human-added ones', () => {
    const mutations: string[] = [];
    const ws = { key: 'AAA', title: 'A', area: 'a', description: 'd' };
    const backlog: WorkstreamBacklog[] = [
      {
        workstream: ws,
        epics: [{ key: 'AAA-E1', title: 'E', phase: 'P0', summary: 's' }],
        issues: [
          {
            key: 'AAA-001',
            epic: 'AAA-E1',
            title: 'one',
            phase: 'P0',
            priority: 'high',
            size: 'S',
            type: 'task',
            agent: true,
            context: 'c',
            acceptance: ['a'],
            testPlan: ['t'],
          },
        ],
      },
    ];
    const node = (key: string, extra: object = {}) => ({
      id: `N-${key}`,
      number: 1,
      title: `[${key}] x`,
      body: `<!-- factory-key: ${key} -->`,
      url: '',
      labels: { nodes: [] },
      milestone: null,
      parent: null,
      blockedBy: { nodes: [] },
      ...extra,
    });
    const fake: GhClient = {
      graphql(query: string): any {
        if (/^\s*mutation/.test(query)) {
          mutations.push(query);
          return {};
        }
        if (query.includes('user(login:$o){ id }'))
          return { repository: { id: 'R' }, user: { id: 'U' } };
        return {
          repository: {
            issues: {
              pageInfo: { hasNextPage: false, endCursor: null },
              nodes: [
                node('AAA-E1'),
                node('AAA-001', {
                  parent: { id: 'N-AAA-E1' },
                  blockedBy: {
                    nodes: [
                      { id: 'N-CLOUD-004', body: '<!-- factory-key: CLOUD-004 -->' },
                      { id: 'N-human', body: 'a human-filed issue' },
                    ],
                  },
                }),
              ],
            },
          },
        };
      },
      rest(): any {
        return [];
      },
    };
    const stats = seed(fake, backlog, {
      owner: 'o',
      repo: 'r',
      dryRun: false,
      skipProjects: true,
      log: () => {},
    });
    expect(stats['blockedby.remove']).toBe(1);
    const removals = mutations.filter((m) => m.includes('removeBlockedBy'));
    expect(removals).toHaveLength(1);
  });
});
