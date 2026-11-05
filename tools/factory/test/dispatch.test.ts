import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createCliGh, type GhClient } from '../src/gh.ts';
import { claim, loadCandidates, release, setStatus } from '../src/next.ts';

// `createCliGh` shells out to `gh`; replace the child process with a plain
// recording function so no network or gh binary is touched. (A plain function,
// not `vi.fn`: Vitest fails a test when a `vi.fn` implementation throws, and
// the error path is exactly what we want to exercise.)
const fakeExec = vi.hoisted(() => ({
  calls: [] as unknown[][],
  impl: (..._args: unknown[]): unknown => '',
}));
vi.mock('node:child_process', () => ({
  execFileSync: (...args: unknown[]) => {
    fakeExec.calls.push(args);
    return fakeExec.impl(...args);
  },
}));

interface Call {
  kind: 'graphql' | 'rest';
  method?: string;
  path?: string;
  query?: string;
  variables?: Record<string, unknown>;
  body?: unknown;
}

/** Project items as returned by the Status lookup query. */
const PROJECT_ITEMS = [
  {
    id: 'item-1',
    project: {
      id: 'proj-1',
      field: {
        id: 'field-1',
        options: [
          { id: 'opt-backlog', name: 'Backlog' },
          { id: 'opt-ready', name: 'Ready' },
          { id: 'opt-progress', name: 'In progress' },
        ],
      },
    },
  },
  {
    id: 'item-2',
    project: {
      id: 'proj-2',
      field: {
        id: 'field-2',
        options: [
          { id: 'opt2-ready', name: 'Ready' },
          { id: 'opt2-progress', name: 'In Progress' },
        ],
      },
    },
  },
  // A project without a Status field is skipped, never crashes.
  { id: 'item-3', project: { id: 'proj-3', field: null } },
];

/** A recording fake `GhClient` with canned responses. */
function fakeGh(opts: { failLabelDelete?: boolean; pages?: any[] } = {}) {
  const calls: Call[] = [];
  let page = 0;
  const gh: GhClient = {
    graphql(query, variables = {}) {
      calls.push({ kind: 'graphql', query, variables });
      if (/^\s*mutation/.test(query)) return { updateProjectV2ItemFieldValue: {} } as any;
      if (query.includes('projectItems')) {
        return { repository: { issue: { projectItems: { nodes: PROJECT_ITEMS } } } } as any;
      }
      if (query.includes('issues(first:100')) return opts.pages?.[page++] as any;
      throw new Error(`unexpected query: ${query.slice(0, 60)}`);
    },
    rest(method, path, body) {
      calls.push({ kind: 'rest', method, path, body });
      if (method === 'GET' && path === 'user') return { login: 'robot' } as any;
      if (opts.failLabelDelete && method === 'DELETE' && path.includes('/labels/')) {
        throw new Error('HTTP 404: Label does not exist');
      }
      return undefined as any;
    },
  };
  const mutations = () =>
    calls.filter((c) => c.kind === 'graphql' && /^\s*mutation/.test(c.query ?? ''));
  const rests = () => calls.filter((c) => c.kind === 'rest');
  return { gh, calls, mutations, rests };
}

describe('setStatus (fake GhClient)', () => {
  it('updates every project whose Status field has the option (case-insensitive)', () => {
    const f = fakeGh();
    const n = setStatus(f.gh, 'o', 'r', 7, 'in progress');
    expect(n).toBe(2);
    expect(f.calls[0].variables).toEqual({ o: 'o', r: 'r', n: 7 });
    expect(f.mutations().map((m) => m.variables)).toEqual([
      { p: 'proj-1', i: 'item-1', f: 'field-1', o: 'opt-progress' },
      { p: 'proj-2', i: 'item-2', f: 'field-2', o: 'opt2-progress' },
    ]);
  });

  it('skips projects that lack the requested option', () => {
    const f = fakeGh();
    expect(setStatus(f.gh, 'o', 'r', 7, 'Backlog')).toBe(1);
    expect(setStatus(f.gh, 'o', 'r', 7, 'Done')).toBe(0);
  });
});

describe('claim (fake GhClient)', () => {
  it('assigns the authenticated user, labels in-progress and sets Status', () => {
    const f = fakeGh();
    claim(f.gh, 'jaypetez', 'stride', 42);
    expect(f.rests().map((c) => [c.method, c.path, c.body])).toEqual([
      ['GET', 'user', undefined],
      ['POST', 'repos/jaypetez/stride/issues/42/assignees', { assignees: ['robot'] }],
      ['POST', 'repos/jaypetez/stride/issues/42/labels', { labels: ['status:in-progress'] }],
    ]);
    expect(f.mutations().map((m) => m.variables?.o)).toEqual(['opt-progress', 'opt2-progress']);
  });
});

describe('release (fake GhClient)', () => {
  it('unassigns, removes the in-progress label and returns the issue to Ready', () => {
    const f = fakeGh();
    release(f.gh, 'o', 'r', 5, false);
    expect(f.rests().map((c) => [c.method, c.path])).toEqual([
      ['GET', 'user'],
      ['DELETE', 'repos/o/r/issues/5/assignees'],
      ['DELETE', 'repos/o/r/issues/5/labels/status%3Ain-progress'],
    ]);
    expect(f.rests()[1].body).toEqual({ assignees: ['robot'] });
    expect(f.mutations().map((m) => m.variables?.o)).toEqual(['opt-ready', 'opt2-ready']);
  });

  it('with --needs-human labels the issue and moves it to Backlog', () => {
    const f = fakeGh();
    release(f.gh, 'o', 'r', 5, true);
    expect(f.rests().at(-1)).toMatchObject({
      method: 'POST',
      path: 'repos/o/r/issues/5/labels',
      body: { labels: ['status:needs-human'] },
    });
    expect(f.mutations().map((m) => m.variables?.o)).toEqual(['opt-backlog']);
  });

  it('tolerates the in-progress label already being absent', () => {
    const f = fakeGh({ failLabelDelete: true });
    expect(() => release(f.gh, 'o', 'r', 5, false)).not.toThrow();
    expect(f.mutations()).toHaveLength(2);
  });
});

describe('loadCandidates (fake GhClient)', () => {
  const node = (number: number, body: string, extra: Record<string, unknown> = {}) => ({
    number,
    title: `Issue ${number}`,
    url: `https://github.com/o/r/issues/${number}`,
    body,
    labels: { nodes: [{ name: 'agent:ok' }, { name: 'phase:P1' }] },
    assignees: { totalCount: 0 },
    issueDependenciesSummary: { blockedBy: 1 },
    closedByPullRequestsReferences: { nodes: [{ state: 'OPEN' }, { state: 'MERGED' }] },
    ...extra,
  });
  const page = (nodes: unknown[], endCursor: string | null) => ({
    repository: {
      issues: { pageInfo: { hasNextPage: endCursor !== null, endCursor }, nodes },
    },
  });

  it('pages through issues, parses keys and skips unmanaged issues', () => {
    const f = fakeGh({
      pages: [
        page([node(1, '<!-- factory-key: AUD-001 -->'), node(2, 'no key here')], 'c1'),
        page([node(3, '<!-- factory-key: AUD-002 -->', { issueDependenciesSummary: null })], null),
      ],
    });
    const cands = loadCandidates(f.gh, 'o', 'r');
    expect(cands.map((c) => [c.number, c.key])).toEqual([
      [1, 'AUD-001'],
      [3, 'AUD-002'],
    ]);
    expect(cands[0]).toMatchObject({ openBlockers: 1, openPrs: 1, assignees: 0 });
    expect(cands[1].openBlockers).toBe(0);
    expect(f.calls.map((c) => c.variables?.c)).toEqual([null, 'c1']);
  });
});

describe('createCliGh (fake gh binary)', () => {
  beforeEach(() => {
    fakeExec.calls = [];
    fakeExec.impl = () => '';
  });
  const quiet = { mutationSpacingMs: 0, log: () => {} };

  it('graphql sends the query on stdin and returns data', () => {
    fakeExec.impl = () => JSON.stringify({ data: { viewer: { login: 'me' } } });
    const gh = createCliGh(quiet);
    expect(gh.graphql('query { viewer { login } }', { a: 1 })).toEqual({
      viewer: { login: 'me' },
    });
    const [bin, args, options] = fakeExec.calls[0] as [string, string[], { input: string }];
    expect(bin).toBe('gh');
    expect(args).toEqual(['api', 'graphql', '--input', '-']);
    expect(JSON.parse(options.input)).toEqual({
      query: 'query { viewer { login } }',
      variables: { a: 1 },
    });
  });

  it('graphql throws on GraphQL errors', () => {
    fakeExec.impl = () => JSON.stringify({ errors: [{ message: 'nope' }] });
    expect(() => createCliGh(quiet).graphql('mutation { x }')).toThrow(/GraphQL error: .*nope/);
  });

  it('rest passes method/path/body and tolerates empty output', () => {
    const outputs = [JSON.stringify({ login: 'me' }), '  '];
    fakeExec.impl = () => outputs.shift();
    const gh = createCliGh(quiet);
    expect(gh.rest('GET', 'user')).toEqual({ login: 'me' });
    expect(gh.rest('POST', 'repos/o/r/issues/1/labels', { labels: ['x'] })).toBeUndefined();
    expect(fakeExec.calls[0][1]).toEqual(['api', '--method', 'GET', 'user']);
    expect(fakeExec.calls[1][1]).toEqual([
      'api',
      '--method',
      'POST',
      'repos/o/r/issues/1/labels',
      '--input',
      '-',
    ]);
    expect(JSON.parse((fakeExec.calls[1][2] as { input: string }).input)).toEqual({
      labels: ['x'],
    });
  });

  it('fails fast with a trimmed message on a non-retryable error', () => {
    fakeExec.impl = () => {
      throw Object.assign(new Error('exit 1'), { stderr: 'HTTP 404: Not Found' });
    };
    expect(() => createCliGh(quiet).rest('GET', 'repos/o/r')).toThrow(
      /^gh api --method GET failed: HTTP 404: Not Found/,
    );
    expect(fakeExec.calls).toHaveLength(1);
  });
});
