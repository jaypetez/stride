/**
 * Idempotent seeder: makes GitHub (labels, milestones, issues, sub-issues,
 * blocked-by links, Projects v2 + fields + views + items) match the backlog.
 *
 * Safe to re-run: everything is keyed by the hidden `factory-key` marker in
 * issue bodies and by exact Project/field/option names. It never deletes issues,
 * never removes human-added labels/links, and never moves an item's Status out
 * of In progress / In review / Done.
 */
import { FOCUS_PHASE } from './focus.ts';
import type { GhClient } from './gh.ts';
import {
  epicLabels,
  factoryLabels,
  issueLabels,
  issueTitle,
  ORCHESTRATOR_TITLE,
  PROJECT_PREFIX,
  parseHash,
  parseKey,
  renderEpicBody,
  renderIssueBody,
  renderProjectReadme,
} from './render.ts';
import {
  type Issue,
  PHASE_DESCRIPTIONS,
  PHASE_TITLES,
  PHASES,
  type Workstream,
  type WorkstreamBacklog,
} from './types.ts';
import { phaseIndex } from './validate.ts';

export interface SeedOptions {
  owner: string;
  repo: string;
  dryRun: boolean;
  /** Skip the (slow) Projects phase. */
  skipProjects?: boolean;
  /** Make Projects public (the repo is public OSS). */
  publicProjects?: boolean;
  log?: (msg: string) => void;
}

export interface SeedStats {
  [action: string]: number;
}

interface GhIssue {
  id: string;
  number: number;
  title: string;
  body: string;
  url: string;
  labels: string[];
  milestone: number | null;
  parentId: string | null;
  blockedByIds: Set<string>;
  /** Blocker node id → its factory key (null when the blocker isn't a factory issue). */
  blockerKeys: Map<string, string | null>;
}

/** Labels the factory may add *and remove*; anything else on an issue is left alone. */
const MANAGED_LABEL = /^(factory|epic|agent:ok|human-only|area:|phase:|priority:|size:|type:)/;

export const STATUS_OPTIONS = ['Backlog', 'Ready', 'In progress', 'In review', 'Done'] as const;
const STATUS_COLORS = ['GRAY', 'BLUE', 'YELLOW', 'PURPLE', 'GREEN'];
const PROTECTED_STATUS = new Set(['In progress', 'In review', 'Done']);

export interface FieldSpec {
  name: string;
  options: { name: string; color: string; description?: string }[];
}

export function projectFieldSpecs(
  backlogs: WorkstreamBacklog[],
  orchestrator: boolean,
): FieldSpec[] {
  const specs: FieldSpec[] = [
    {
      name: 'Phase',
      options: PHASES.map((p) => ({
        name: p,
        color: p === 'Later' ? 'GRAY' : 'BLUE',
        description: PHASE_TITLES[p],
      })),
    },
    {
      name: 'Priority',
      options: [
        { name: 'high', color: 'RED' },
        { name: 'medium', color: 'YELLOW' },
        { name: 'low', color: 'GREEN' },
      ],
    },
    {
      name: 'Size',
      options: ['XS', 'S', 'M', 'L'].map((s) => ({ name: s, color: 'GRAY' })),
    },
    {
      name: 'Agent',
      options: [
        { name: 'agent', color: 'GREEN', description: 'agent:ok' },
        { name: 'human', color: 'RED', description: 'human-only' },
        { name: 'epic', color: 'PURPLE', description: 'parent issue' },
      ],
    },
  ];
  if (orchestrator) {
    specs.unshift({
      name: 'Workstream',
      options: backlogs.map((b) => ({ name: b.workstream.title, color: 'BLUE' })),
    });
  }
  return specs;
}

export function seed(gh: GhClient, backlogs: WorkstreamBacklog[], opts: SeedOptions): SeedStats {
  const log = opts.log ?? ((m: string) => process.stderr.write(`${m}\n`));
  const stats: SeedStats = {};
  const { owner, repo, dryRun } = opts;
  const act = (kind: string, desc: string, fn: () => void): void => {
    stats[kind] = (stats[kind] ?? 0) + 1;
    log(`${dryRun ? '[dry-run] ' : ''}${kind}: ${desc}`);
    if (!dryRun) fn();
  };

  // ── Repo + owner ids ────────────────────────────────────────────────────────
  const meta = gh.graphql(
    `query($o:String!,$r:String!){ repository(owner:$o,name:$r){ id } user(login:$o){ id } }`,
    { o: owner, r: repo },
  );
  const repoId: string = meta.repository.id;
  const ownerId: string = meta.user.id;

  // ── Labels ──────────────────────────────────────────────────────────────────
  const existingLabels = new Map<string, { color: string; description: string }>();
  for (let page = 1; ; page++) {
    const batch: any[] = gh.rest('GET', `repos/${owner}/${repo}/labels?per_page=100&page=${page}`);
    for (const l of batch)
      existingLabels.set(l.name, { color: l.color, description: l.description ?? '' });
    if (batch.length < 100) break;
  }
  for (const [name, [color, description]] of Object.entries(factoryLabels(backlogs))) {
    const cur = existingLabels.get(name);
    if (!cur) {
      act('label.create', name, () =>
        gh.rest('POST', `repos/${owner}/${repo}/labels`, { name, color, description }),
      );
    } else if (cur.color.toLowerCase() !== color || cur.description !== description) {
      act('label.update', name, () =>
        gh.rest('PATCH', `repos/${owner}/${repo}/labels/${encodeURIComponent(name)}`, {
          color,
          description,
        }),
      );
    }
  }

  // ── Milestones (one per phase) ──────────────────────────────────────────────
  const milestones = new Map<string, number>();
  for (let page = 1; ; page++) {
    const batch: any[] = gh.rest(
      'GET',
      `repos/${owner}/${repo}/milestones?state=all&per_page=100&page=${page}`,
    );
    for (const m of batch) milestones.set(m.title, m.number);
    if (batch.length < 100) break;
  }
  for (const p of PHASES) {
    const title = PHASE_TITLES[p];
    if (!milestones.has(title)) {
      act('milestone.create', title, () => {
        const m = gh.rest('POST', `repos/${owner}/${repo}/milestones`, {
          title,
          description: PHASE_DESCRIPTIONS[p],
        });
        milestones.set(title, m.number);
      });
    }
  }

  // ── Existing factory issues ─────────────────────────────────────────────────
  const byKey = new Map<string, GhIssue>();
  let cursor: string | null = null;
  do {
    const data: any = gh.graphql(
      `query($o:String!,$r:String!,$c:String){ repository(owner:$o,name:$r){
        issues(first:100, after:$c, labels:["factory"], states:[OPEN,CLOSED]){
          pageInfo{ hasNextPage endCursor }
          nodes{ id number title body url labels(first:30){nodes{name}} milestone{number}
                 parent{ id } blockedBy(first:50){ nodes{ id body } } } } } }`,
      { o: owner, r: repo, c: cursor },
    );
    const conn = data.repository.issues;
    for (const n of conn.nodes) {
      const key = parseKey(n.body);
      if (!key) continue;
      byKey.set(key, {
        id: n.id,
        number: n.number,
        title: n.title,
        body: n.body,
        url: n.url,
        labels: n.labels.nodes.map((l: any) => l.name),
        milestone: n.milestone?.number ?? null,
        parentId: n.parent?.id ?? null,
        blockedByIds: new Set(n.blockedBy.nodes.map((b: any) => b.id)),
        blockerKeys: new Map(n.blockedBy.nodes.map((b: any) => [b.id, parseKey(b.body)])),
      });
    }
    cursor = conn.pageInfo.hasNextPage ? conn.pageInfo.endCursor : null;
  } while (cursor);
  log(`found ${byKey.size} existing factory issues`);

  const num = (key: string) => byKey.get(key)?.number;

  type Entry = {
    key: string;
    ws: Workstream;
    title: string;
    labels: string[];
    phase: (typeof PHASES)[number];
    render: () => string;
  };
  // Issues in dependency order (so "Depends on #n" resolves at creation time),
  // then epics (so their child lists resolve). Pass 2 is then a near no-op,
  // which keeps us well under GitHub's content-creation rate limits.
  const entries: Entry[] = [];
  const wsOf = new Map<string, Workstream>();
  for (const b of backlogs) for (const i of b.issues) wsOf.set(i.key, b.workstream);
  for (const i of topoOrder(backlogs.flatMap((b) => b.issues))) {
    const ws = wsOf.get(i.key)!;
    entries.push({
      key: i.key,
      ws,
      title: issueTitle(i),
      labels: issueLabels(ws, i),
      phase: i.phase,
      render: () => renderIssueBody(ws, i, num),
    });
  }
  for (const b of backlogs) {
    for (const e of b.epics) {
      const children = b.issues.filter((i) => i.epic === e.key);
      entries.push({
        key: e.key,
        ws: b.workstream,
        title: issueTitle(e),
        labels: epicLabels(b.workstream, e),
        phase: e.phase,
        render: () => renderEpicBody(b.workstream, e, children, num),
      });
    }
  }

  // ── Pass 1: create missing issues ───────────────────────────────────────────
  for (const en of entries) {
    if (byKey.has(en.key)) continue;
    act('issue.create', en.title, () => {
      const created = gh.rest('POST', `repos/${owner}/${repo}/issues`, {
        title: en.title,
        body: en.render(),
        labels: en.labels,
        milestone: milestones.get(PHASE_TITLES[en.phase]),
      });
      byKey.set(en.key, {
        id: created.node_id,
        number: created.number,
        title: en.title,
        body: created.body ?? '',
        url: created.html_url,
        labels: en.labels,
        milestone: milestones.get(PHASE_TITLES[en.phase]) ?? null,
        parentId: null,
        blockedByIds: new Set(),
        blockerKeys: new Map(),
      });
    });
  }

  // ── Pass 2: converge title / body / labels / milestone ──────────────────────
  for (const en of entries) {
    const cur = byKey.get(en.key);
    if (!cur) continue; // dry-run create
    const body = en.render();
    const patch: Record<string, unknown> = {};
    if (cur.title !== en.title) patch.title = en.title;
    if (parseHash(cur.body) !== parseHash(body)) patch.body = body;
    const ms = milestones.get(PHASE_TITLES[en.phase]);
    if (ms && cur.milestone !== ms) patch.milestone = ms;
    const keep = cur.labels.filter((l) => !MANAGED_LABEL.test(l));
    const want = [...new Set([...keep, ...en.labels])].sort();
    if (want.join() !== [...cur.labels].sort().join()) patch.labels = want;
    if (Object.keys(patch).length) {
      act('issue.update', `${en.key} (${Object.keys(patch).join(',')})`, () => {
        gh.rest('PATCH', `repos/${owner}/${repo}/issues/${cur.number}`, patch);
        if (patch.body) cur.body = body;
      });
    }
  }

  // ── Sub-issues + blocked-by ─────────────────────────────────────────────────
  for (const b of backlogs) {
    for (const i of b.issues) {
      const cur = byKey.get(i.key);
      const epic = byKey.get(i.epic);
      if (!cur || !epic) {
        stats['subissue.add'] = (stats['subissue.add'] ?? 0) + 1;
        continue;
      }
      if (cur.parentId !== epic.id) {
        act('subissue.add', `${i.epic} ⊃ ${i.key}`, () =>
          gh.graphql(
            `mutation($p:ID!,$c:ID!){ addSubIssue(input:{issueId:$p, subIssueId:$c, replaceParent:true}){ clientMutationId } }`,
            { p: epic.id, c: cur.id },
          ),
        );
      }
      for (const d of i.dependsOn ?? []) {
        const dep = byKey.get(d);
        if (!dep) {
          stats['blockedby.add'] = (stats['blockedby.add'] ?? 0) + 1;
          continue;
        }
        if (cur.blockedByIds.has(dep.id)) continue;
        act('blockedby.add', `${i.key} ⟵ ${d}`, () =>
          gh.graphql(
            `mutation($i:ID!,$b:ID!){ addBlockedBy(input:{issueId:$i, blockingIssueId:$b}){ clientMutationId } }`,
            { i: cur.id, b: dep.id },
          ),
        );
      }
      // Drop factory-owned links whose dependency was removed from the backlog
      // (e.g. after re-phasing). Links to non-factory issues are human-owned.
      const wanted = new Set(i.dependsOn ?? []);
      for (const [blockerId, blockerKey] of cur.blockerKeys) {
        if (!blockerKey || wanted.has(blockerKey)) continue;
        act('blockedby.remove', `${i.key} ⟵ ${blockerKey}`, () =>
          gh.graphql(
            `mutation($i:ID!,$b:ID!){ removeBlockedBy(input:{issueId:$i, blockingIssueId:$b}){ clientMutationId } }`,
            { i: cur.id, b: blockerId },
          ),
        );
      }
    }
  }

  if (opts.skipProjects) return stats;

  // ── Projects ────────────────────────────────────────────────────────────────
  const projects = new Map<string, { id: string; number: number }>();
  {
    const data = gh.graphql(
      `query($o:String!){ user(login:$o){ projectsV2(first:100){ nodes{ id number title closed } } } }`,
      { o: owner },
    );
    for (const p of data.user.projectsV2.nodes) {
      if (!p.closed) projects.set(p.title, { id: p.id, number: p.number });
    }
  }

  const allIssues: Issue[] = backlogs.flatMap((b) => b.issues);
  const issueByKey = new Map(allIssues.map((i) => [i.key, i]));
  const wsByKey = new Map<string, Workstream>();
  for (const b of backlogs) {
    for (const e of b.epics) wsByKey.set(e.key, b.workstream);
    for (const i of b.issues) wsByKey.set(i.key, b.workstream);
  }

  const targets: { title: string; ws: Workstream | null; keys: string[] }[] = [
    { title: ORCHESTRATOR_TITLE, ws: null, keys: entries.map((e) => e.key) },
    ...backlogs.map((b) => ({
      title: `${PROJECT_PREFIX}${b.workstream.title}`,
      ws: b.workstream,
      keys: projectKeys(backlogs, b),
    })),
  ];

  for (const t of targets) {
    let project = projects.get(t.title);
    if (!project) {
      act('project.create', t.title, () => {
        const d = gh.graphql(
          `mutation($o:ID!,$t:String!,$r:ID!){ createProjectV2(input:{ownerId:$o,title:$t,repositoryId:$r}){ projectV2{ id number } } }`,
          { o: ownerId, t: t.title, r: repoId },
        );
        project = d.createProjectV2.projectV2;
        projects.set(t.title, project!);
      });
      if (!project) {
        stats['project.items'] = (stats['project.items'] ?? 0) + t.keys.length;
        continue; // dry-run
      }
    }
    const pid = project.id;
    const readme = renderProjectReadme(t.ws, backlogs);
    const shortDescription = t.ws ? t.ws.description.slice(0, 250) : 'All Stride factory work.';
    syncProjectMeta(gh, pid, readme, shortDescription, opts.publicProjects ?? true, act);

    const fields = ensureFields(gh, pid, projectFieldSpecs(backlogs, t.ws === null), act, dryRun);
    ensureViews(gh, pid, t.ws === null, act);

    // Items
    let items = loadItems(gh, pid);
    const missing = t.keys.filter((k) => byKey.has(k) && !items.has(byKey.get(k)!.id));
    for (let i = 0; i < missing.length; i += 8) {
      const chunk = missing.slice(i, i + 8);
      act('project.items.add', `${t.title}: +${chunk.length}`, () => {
        const m = chunk
          .map(
            (k, j) =>
              `a${j}: addProjectV2ItemById(input:{projectId:${JSON.stringify(pid)}, contentId:${JSON.stringify(byKey.get(k)!.id)}}){ item{ id } }`,
          )
          .join('\n');
        try {
          gh.graphql(`mutation{ ${m} }`);
        } catch (err) {
          // A retried batch may have partly applied already; that's fine.
          if (!/Content already exists in this project/.test(String(err))) throw err;
        }
      });
    }
    if (missing.length && !dryRun) items = loadItems(gh, pid);

    // Field values
    const updates: string[] = [];
    const descs: string[] = [];
    for (const k of t.keys) {
      const gi = byKey.get(k);
      const item = gi && items.get(gi.id);
      if (!gi || !item) continue;
      const iss = issueByKey.get(k);
      const isEpic = !iss;
      const phase = iss?.phase ?? backlogs.flatMap((b) => b.epics).find((e) => e.key === k)!.phase;
      const want: Record<string, string> = {
        Phase: phase,
        Agent: isEpic ? 'epic' : iss!.agent ? 'agent' : 'human',
      };
      if (iss) {
        want.Priority = iss.priority;
        want.Size = iss.size;
      }
      if (t.ws === null) want.Workstream = wsByKey.get(k)!.title;
      const curStatus = item.values.Status;
      if (!curStatus || !PROTECTED_STATUS.has(curStatus)) {
        const blocked = (iss?.dependsOn ?? []).length > 0 && !isEpic;
        // Readiness is recomputed live by the dispatcher; this is a coarse board hint.
        const afterFocus = phaseIndex(phase) > phaseIndex(FOCUS_PHASE);
        want.Status = isEpic || blocked || afterFocus || !iss?.agent ? 'Backlog' : 'Ready';
        if (blocked && gi.blockedByIds.size === 0 && !dryRun) want.Status = 'Backlog';
      }
      for (const [field, value] of Object.entries(want)) {
        if (item.values[field] === value) continue;
        const f = fields.get(field);
        const optId = f?.options.get(value);
        if (!f || !optId) continue;
        updates.push(
          `updateProjectV2ItemFieldValue(input:{projectId:${JSON.stringify(pid)}, itemId:${JSON.stringify(item.itemId)}, fieldId:${JSON.stringify(f.id)}, value:{singleSelectOptionId:${JSON.stringify(optId)}}}){ clientMutationId }`,
        );
        descs.push(`${k}.${field}=${value}`);
      }
    }
    for (let i = 0; i < updates.length; i += 10) {
      const chunk = updates.slice(i, i + 10);
      act('project.fields.set', `${t.title}: ${chunk.length} values`, () => {
        gh.graphql(`mutation{ ${chunk.map((u, j) => `u${j}: ${u}`).join('\n')} }`);
      });
    }
  }

  return stats;
}

/** Keys a workstream's Project holds: its own epics/issues plus any `includePhases` issues. */
export function projectKeys(backlogs: WorkstreamBacklog[], b: WorkstreamBacklog): string[] {
  const keys = [...b.epics.map((e) => e.key), ...b.issues.map((i) => i.key)];
  const phases = new Set(b.workstream.includePhases ?? []);
  if (phases.size) {
    for (const other of backlogs) {
      if (other === b) continue;
      for (const i of other.issues) if (phases.has(i.phase)) keys.push(i.key);
    }
  }
  return [...new Set(keys)];
}

/** Stable topological order: every issue appears after the issues it depends on. */
export function topoOrder(issues: Issue[]): Issue[] {
  const byKey = new Map(issues.map((i) => [i.key, i]));
  const done = new Set<string>();
  const out: Issue[] = [];
  const visit = (i: Issue, path: Set<string>) => {
    if (done.has(i.key) || path.has(i.key)) return;
    path.add(i.key);
    for (const d of i.dependsOn ?? []) {
      const dep = byKey.get(d);
      if (dep) visit(dep, path);
    }
    path.delete(i.key);
    done.add(i.key);
    out.push(i);
  };
  for (const i of issues) visit(i, new Set());
  return out;
}

type Act = (kind: string, desc: string, fn: () => void) => void;

function syncProjectMeta(
  gh: GhClient,
  pid: string,
  readme: string,
  shortDescription: string,
  isPublic: boolean,
  act: Act,
): void {
  const d = gh.graphql(
    `query($p:ID!){ node(id:$p){ ... on ProjectV2{ readme shortDescription public } } }`,
    { p: pid },
  );
  const n = d.node;
  if (n.readme !== readme || n.shortDescription !== shortDescription || n.public !== isPublic) {
    act('project.meta', pid, () =>
      gh.graphql(
        `mutation($p:ID!,$r:String!,$s:String!,$pub:Boolean!){ updateProjectV2(input:{projectId:$p, readme:$r, shortDescription:$s, public:$pub}){ projectV2{ id } } }`,
        { p: pid, r: readme, s: shortDescription, pub: isPublic },
      ),
    );
  }
}

interface FieldInfo {
  id: string;
  options: Map<string, string>;
}

function loadFields(gh: GhClient, pid: string): Map<string, FieldInfo & { raw: any[] }> {
  const d = gh.graphql(
    `query($p:ID!){ node(id:$p){ ... on ProjectV2{ fields(first:50){ nodes{
       ... on ProjectV2SingleSelectField{ id name options{ id name color description } } } } } } }`,
    { p: pid },
  );
  const out = new Map<string, FieldInfo & { raw: any[] }>();
  for (const f of d.node.fields.nodes) {
    if (!f?.id || !f.options) continue;
    out.set(f.name, {
      id: f.id,
      options: new Map(f.options.map((o: any) => [o.name, o.id])),
      raw: f.options,
    });
  }
  return out;
}

function ensureFields(
  gh: GhClient,
  pid: string,
  specs: FieldSpec[],
  act: Act,
  dryRun: boolean,
): Map<string, FieldInfo> {
  let fields = loadFields(gh, pid);
  const statusSpec: FieldSpec = {
    name: 'Status',
    options: STATUS_OPTIONS.map((name, i) => ({ name, color: STATUS_COLORS[i]! })),
  };
  let changed = false;
  for (const spec of [statusSpec, ...specs]) {
    const cur = fields.get(spec.name);
    const opts = (keepIds: boolean) =>
      spec.options.map((o) => ({
        ...(keepIds && cur?.options.get(o.name) ? { id: cur.options.get(o.name) } : {}),
        name: o.name,
        color: o.color,
        description: o.description ?? '',
      }));
    if (!cur) {
      act('project.field.create', spec.name, () =>
        gh.graphql(
          `mutation($p:ID!,$n:String!,$o:[ProjectV2SingleSelectFieldOptionInput!]!){ createProjectV2Field(input:{projectId:$p, dataType:SINGLE_SELECT, name:$n, singleSelectOptions:$o}){ clientMutationId } }`,
          { p: pid, n: spec.name, o: opts(false) },
        ),
      );
      changed = true;
    } else {
      const have = [...cur.options.keys()].join('|');
      const want = spec.options.map((o) => o.name).join('|');
      if (have !== want) {
        act('project.field.options', `${spec.name}: ${have} → ${want}`, () =>
          gh.graphql(
            `mutation($f:ID!,$o:[ProjectV2SingleSelectFieldOptionInput!]!){ updateProjectV2Field(input:{fieldId:$f, singleSelectOptions:$o}){ clientMutationId } }`,
            { f: cur.id, o: opts(true) },
          ),
        );
        changed = true;
      }
    }
  }
  if (changed && !dryRun) fields = loadFields(gh, pid);
  return fields;
}

const ORCH_VIEWS: { name: string; layout: string; filter?: string }[] = [
  { name: 'Board', layout: 'BOARD_LAYOUT' },
  {
    name: 'Next up (agents)',
    layout: 'TABLE_LAYOUT',
    filter: 'is:open label:agent:ok no:assignee status:Ready',
  },
  { name: 'Human-only', layout: 'TABLE_LAYOUT', filter: 'is:open label:human-only' },
  { name: 'Epics', layout: 'TABLE_LAYOUT', filter: 'label:epic' },
];
const WS_VIEWS: typeof ORCH_VIEWS = [
  { name: 'Board', layout: 'BOARD_LAYOUT' },
  { name: 'Open', layout: 'TABLE_LAYOUT', filter: 'is:open -label:epic' },
];

function ensureViews(gh: GhClient, pid: string, orchestrator: boolean, act: Act): void {
  const d = gh.graphql(
    `query($p:ID!){ node(id:$p){ ... on ProjectV2{ views(first:30){ nodes{ id name } } } } }`,
    { p: pid },
  );
  const have = new Set(d.node.views.nodes.map((v: any) => v.name));
  for (const v of orchestrator ? ORCH_VIEWS : WS_VIEWS) {
    if (have.has(v.name)) continue;
    act('project.view.create', v.name, () => {
      const c = gh.graphql(
        `mutation($p:ID!,$n:String!,$l:ProjectV2ViewLayout!){ createProjectV2View(input:{projectId:$p, name:$n, layout:$l}){ projectV2View{ id } } }`,
        { p: pid, n: v.name, l: v.layout },
      );
      if (v.filter) {
        gh.graphql(
          `mutation($v:ID!,$f:String!){ updateProjectV2View(input:{viewId:$v, filter:$f}){ clientMutationId } }`,
          { v: c.createProjectV2View.projectV2View.id, f: v.filter },
        );
      }
    });
  }
}

function loadItems(
  gh: GhClient,
  pid: string,
): Map<string, { itemId: string; values: Record<string, string> }> {
  const out = new Map<string, { itemId: string; values: Record<string, string> }>();
  let cursor: string | null = null;
  do {
    const d: any = gh.graphql(
      `query($p:ID!,$c:String){ node(id:$p){ ... on ProjectV2{ items(first:100, after:$c){
         pageInfo{ hasNextPage endCursor }
         nodes{ id content{ ... on Issue{ id } }
           fieldValues(first:20){ nodes{ ... on ProjectV2ItemFieldSingleSelectValue{ name
             field{ ... on ProjectV2SingleSelectField{ name } } } } } } } } } }`,
      { p: pid, c: cursor },
    );
    const conn = d.node.items;
    for (const n of conn.nodes) {
      if (!n.content?.id) continue;
      const values: Record<string, string> = {};
      for (const v of n.fieldValues.nodes) if (v?.field?.name) values[v.field.name] = v.name;
      out.set(n.content.id, { itemId: n.id, values });
    }
    cursor = conn.pageInfo.hasNextPage ? conn.pageInfo.endCursor : null;
  } while (cursor);
  return out;
}
