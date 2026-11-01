/**
 * Dispatcher: picks the next issue an agent should work on, claims it, and
 * moves its Project Status. Reads live GitHub state (not the backlog files), so
 * readiness reflects what has actually merged.
 */
import type { GhClient } from './gh.ts';
import { parseKey } from './render.ts';
import { PHASES, PRIORITIES } from './types.ts';

export interface Candidate {
  number: number;
  key: string;
  title: string;
  url: string;
  labels: string[];
  assignees: number;
  /** Open issues blocking this one (GitHub `issueDependenciesSummary.blockedBy`). */
  openBlockers: number;
  /** Open PRs that will close this issue. */
  openPrs: number;
}

export interface PickOptions {
  /** Filter by `area:<x>` label or workstream key prefix (case-insensitive). */
  workstream?: string;
  /** Only this phase or earlier. */
  maxPhase?: (typeof PHASES)[number];
  /** Include human-only issues (for a human looking for work). */
  includeHuman?: boolean;
}

const labelValue = (labels: string[], prefix: string): string | undefined =>
  labels.find((l) => l.startsWith(prefix))?.slice(prefix.length);

export function isReady(c: Candidate, opts: PickOptions = {}): boolean {
  if (c.labels.includes('epic')) return false;
  if (!opts.includeHuman && !c.labels.includes('agent:ok')) return false;
  if (c.labels.includes('status:in-progress') || c.labels.includes('status:needs-human')) {
    return false;
  }
  if (c.assignees > 0 || c.openBlockers > 0 || c.openPrs > 0) return false;
  if (opts.workstream) {
    const w = opts.workstream.toLowerCase();
    const area = labelValue(c.labels, 'area:')?.toLowerCase();
    const prefix = c.key.split('-')[0]!.toLowerCase();
    if (area !== w && prefix !== w) return false;
  }
  if (opts.maxPhase) {
    const p = labelValue(c.labels, 'phase:') as (typeof PHASES)[number] | undefined;
    if (!p || PHASES.indexOf(p) > PHASES.indexOf(opts.maxPhase)) return false;
  }
  return true;
}

/** Sort order: phase → priority → key (stable, deterministic). */
export function rank(cands: Candidate[]): Candidate[] {
  const phase = (c: Candidate) => {
    const i = PHASES.indexOf(labelValue(c.labels, 'phase:') as any);
    return i < 0 ? 99 : i;
  };
  const prio = (c: Candidate) => {
    const i = PRIORITIES.indexOf(labelValue(c.labels, 'priority:') as any);
    return i < 0 ? 99 : i;
  };
  return [...cands].sort(
    (a, b) => phase(a) - phase(b) || prio(a) - prio(b) || a.key.localeCompare(b.key),
  );
}

export function pick(cands: Candidate[], opts: PickOptions = {}): Candidate[] {
  return rank(cands.filter((c) => isReady(c, opts)));
}

export function loadCandidates(gh: GhClient, owner: string, repo: string): Candidate[] {
  const out: Candidate[] = [];
  let cursor: string | null = null;
  do {
    const d: any = gh.graphql(
      `query($o:String!,$r:String!,$c:String){ repository(owner:$o,name:$r){
        issues(first:100, after:$c, labels:["factory"], states:[OPEN]){
          pageInfo{ hasNextPage endCursor }
          nodes{ number title url body
            labels(first:30){ nodes{ name } }
            assignees(first:1){ totalCount }
            issueDependenciesSummary{ blockedBy }
            closedByPullRequestsReferences(first:5, includeClosedPrs:false){ nodes{ state } } } } } }`,
      { o: owner, r: repo, c: cursor },
    );
    const conn = d.repository.issues;
    for (const n of conn.nodes) {
      const key = parseKey(n.body);
      if (!key) continue;
      out.push({
        number: n.number,
        key,
        title: n.title,
        url: n.url,
        labels: n.labels.nodes.map((l: any) => l.name),
        assignees: n.assignees.totalCount,
        openBlockers: n.issueDependenciesSummary?.blockedBy ?? 0,
        openPrs: n.closedByPullRequestsReferences.nodes.filter((p: any) => p.state === 'OPEN')
          .length,
      });
    }
    cursor = conn.pageInfo.hasNextPage ? conn.pageInfo.endCursor : null;
  } while (cursor);
  return out;
}

/** Sets the Status single-select on every Project the issue belongs to. */
export function setStatus(
  gh: GhClient,
  owner: string,
  repo: string,
  number: number,
  status: string,
): number {
  const d = gh.graphql(
    `query($o:String!,$r:String!,$n:Int!){ repository(owner:$o,name:$r){ issue(number:$n){
       projectItems(first:20){ nodes{ id project{ id
         field(name:"Status"){ ... on ProjectV2SingleSelectField{ id options{ id name } } } } } } } } }`,
    { o: owner, r: repo, n: number },
  );
  let count = 0;
  for (const it of d.repository.issue.projectItems.nodes) {
    const f = it.project.field;
    const opt = f?.options?.find((o: any) => o.name.toLowerCase() === status.toLowerCase());
    if (!opt) continue;
    gh.graphql(
      `mutation($p:ID!,$i:ID!,$f:ID!,$o:String!){ updateProjectV2ItemFieldValue(input:{projectId:$p,itemId:$i,fieldId:$f,value:{singleSelectOptionId:$o}}){ clientMutationId } }`,
      { p: it.project.id, i: it.id, f: f.id, o: opt.id },
    );
    count++;
  }
  return count;
}

/** Assigns the issue to the authenticated user, labels it in-progress, sets Status. */
export function claim(gh: GhClient, owner: string, repo: string, number: number): void {
  const me = gh.rest('GET', 'user').login as string;
  gh.rest('POST', `repos/${owner}/${repo}/issues/${number}/assignees`, { assignees: [me] });
  gh.rest('POST', `repos/${owner}/${repo}/issues/${number}/labels`, {
    labels: ['status:in-progress'],
  });
  setStatus(gh, owner, repo, number, 'In progress');
}

/** Releases a claim (e.g. the agent hit a stop condition). */
export function release(
  gh: GhClient,
  owner: string,
  repo: string,
  number: number,
  needsHuman: boolean,
): void {
  const me = gh.rest('GET', 'user').login as string;
  gh.rest('DELETE', `repos/${owner}/${repo}/issues/${number}/assignees`, { assignees: [me] });
  try {
    gh.rest(
      'DELETE',
      `repos/${owner}/${repo}/issues/${number}/labels/${encodeURIComponent('status:in-progress')}`,
    );
  } catch {
    // label already absent
  }
  if (needsHuman) {
    gh.rest('POST', `repos/${owner}/${repo}/issues/${number}/labels`, {
      labels: ['status:needs-human'],
    });
  }
  setStatus(gh, owner, repo, number, needsHuman ? 'Backlog' : 'Ready');
}
