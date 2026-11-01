import {
  ISSUE_TYPES,
  type Issue,
  PHASES,
  type Phase,
  PRIORITIES,
  SIZES,
  type WorkstreamBacklog,
} from './types.ts';

/**
 * Cross-workstream anchor issues. Other workstreams depend on these by key, so
 * they must exist (with these meanings) in their owning file.
 */
export const ANCHORS = [
  'GOV-001',
  'GOV-010',
  'GOV-011',
  'GOV-012',
  'GOV-013',
  'GOV-014',
  'GOV-015',
  'GOV-016',
  'CLOUD-001',
  'CLOUD-002',
  'CLOUD-003',
  'CLOUD-004',
  'CLOUD-005',
  'SOCIAL-001',
  'SOCIAL-002',
  'SOCIAL-003',
  'SOCIAL-004',
  'CHAT-001',
  'INTEG-001',
  'INTEG-002',
  'INTEG-010',
  'ENGINE-001',
  'ENGINE-002',
  'COACH-001',
  'ANDF-001',
  'ANDF-002',
  'ANDF-003',
  'ANDF-004',
  'ANDF-005',
  'ANDX-001',
  'QA-001',
  'QA-002',
  'SEC-001',
  'SEC-002',
  'REL-001',
  'REL-002',
] as const;

export const phaseIndex = (p: Phase): number => PHASES.indexOf(p);

export interface ValidationResult {
  errors: string[];
  warnings: string[];
}

/** Validates the whole backlog. Pure — no I/O. */
export function validateBacklog(backlogs: WorkstreamBacklog[]): ValidationResult {
  const errors: string[] = [];
  const warnings: string[] = [];
  const issues = new Map<string, Issue>();
  const epicKeys = new Set<string>();
  const wsKeys = new Set<string>();
  const areas = new Set<string>();

  for (const { workstream: ws, epics, issues: list } of backlogs) {
    if (!/^[A-Z]+$/.test(ws.key)) errors.push(`workstream key ${ws.key} must be uppercase letters`);
    if (wsKeys.has(ws.key)) errors.push(`duplicate workstream ${ws.key}`);
    wsKeys.add(ws.key);
    if (areas.has(ws.area)) errors.push(`duplicate area ${ws.area}`);
    areas.add(ws.area);
    if (epics.length === 0) errors.push(`${ws.key}: no epics`);

    const localEpics = new Set<string>();
    for (const e of epics) {
      if (!new RegExp(`^${ws.key}-E\\d+$`).test(e.key)) errors.push(`epic key ${e.key} bad format`);
      if (epicKeys.has(e.key)) errors.push(`duplicate epic ${e.key}`);
      epicKeys.add(e.key);
      localEpics.add(e.key);
      if (!PHASES.includes(e.phase)) errors.push(`${e.key}: bad phase ${e.phase}`);
      if (!e.summary.trim()) errors.push(`${e.key}: empty summary`);
    }

    const usedEpics = new Set<string>();
    for (const i of list) {
      const where = `${i.key}`;
      if (!new RegExp(`^${ws.key}-\\d{3}$`).test(i.key))
        errors.push(`${where}: key must be ${ws.key}-nnn`);
      if (issues.has(i.key)) errors.push(`duplicate issue ${i.key}`);
      issues.set(i.key, i);
      if (!localEpics.has(i.epic)) errors.push(`${where}: epic ${i.epic} not in ${ws.key}`);
      usedEpics.add(i.epic);
      if (!PHASES.includes(i.phase)) errors.push(`${where}: bad phase ${i.phase}`);
      if (!PRIORITIES.includes(i.priority)) errors.push(`${where}: bad priority ${i.priority}`);
      if (!SIZES.includes(i.size)) errors.push(`${where}: bad size ${i.size}`);
      if (!ISSUE_TYPES.includes(i.type)) errors.push(`${where}: bad type ${i.type}`);
      if (typeof i.agent !== 'boolean') errors.push(`${where}: agent must be boolean`);
      if (!i.title.trim() || i.title.length > 90)
        errors.push(`${where}: title empty or > 90 chars`);
      if (/^\[/.test(i.title))
        errors.push(`${where}: title must not start with [ (seeder adds the key)`);
      if (!i.context.trim()) errors.push(`${where}: empty context`);
      if (i.acceptance.length === 0) errors.push(`${where}: no acceptance criteria`);
      if (i.testPlan.length === 0) errors.push(`${where}: no test plan`);
    }
    for (const e of localEpics) {
      if (!usedEpics.has(e)) warnings.push(`${e}: epic has no issues`);
    }
  }

  for (const a of ANCHORS) {
    if (!issues.has(a)) errors.push(`missing anchor issue ${a}`);
  }

  for (const i of issues.values()) {
    for (const d of i.dependsOn ?? []) {
      const dep = issues.get(d);
      if (!dep) {
        errors.push(`${i.key}: depends on unknown ${d}`);
        continue;
      }
      if (d === i.key) errors.push(`${i.key}: depends on itself`);
      if (phaseIndex(dep.phase) > phaseIndex(i.phase)) {
        errors.push(`${i.key} (${i.phase}) depends on later-phase ${d} (${dep.phase})`);
      }
    }
  }

  const cycle = findCycle(issues);
  if (cycle) errors.push(`dependency cycle: ${cycle.join(' -> ')}`);

  return { errors, warnings };
}

function findCycle(issues: Map<string, Issue>): string[] | null {
  const state = new Map<string, 1 | 2>();
  const stack: string[] = [];
  const visit = (k: string): string[] | null => {
    const s = state.get(k);
    if (s === 2) return null;
    if (s === 1) return [...stack.slice(stack.indexOf(k)), k];
    state.set(k, 1);
    stack.push(k);
    for (const d of issues.get(k)?.dependsOn ?? []) {
      if (!issues.has(d)) continue;
      const c = visit(d);
      if (c) return c;
    }
    stack.pop();
    state.set(k, 2);
    return null;
  };
  for (const k of issues.keys()) {
    const c = visit(k);
    if (c) return c;
  }
  return null;
}
