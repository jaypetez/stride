/**
 * Backlog-as-code data model for the Stride "software factory".
 *
 * Every GitHub issue, epic, label, milestone and Project the factory manages is
 * declared in `src/backlog/*.ts` using these types. The seeder (`seed.ts`) makes
 * GitHub match this data idempotently; the dispatcher (`next.ts`) reads GitHub
 * back to pick the next unblocked issue for an agent.
 */

/** Delivery phases. Each maps to a GitHub milestone and ends in a demoable slice. */
export const PHASES = ['MVP', 'P0', 'P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'Later'] as const;
export type Phase = (typeof PHASES)[number];

export const PHASE_TITLES: Record<Phase, string> = {
  MVP: 'MVP — Solo Garmin (local Android)',
  P0: 'P0 — Foundations',
  P1: 'P1 — Walking skeleton',
  P2: 'P2 — Activities & integrations',
  P3: 'P3 — Programs & stats',
  P4: 'P4 — Chat & push',
  P5: 'P5 — AI coach',
  P6: 'P6 — Hardening & v0.1 APK',
  Later: 'Later — Parked',
};

export const PHASE_DESCRIPTIONS: Record<Phase, string> = {
  MVP: 'Single-user, local-only Android app: connect your own Garmin Personal API key, sync on device, see load/form/VO2max/zones computed on device. No backend, no groups (ADR 0010).',
  P0: 'Docs, ADRs, CI gate, scaffolds (cloud Worker, Android project), auth spike, human-only account setup.',
  P1: 'Sign in on Android → create a group → invite a friend → see the member list, against local `wrangler dev`.',
  P2: 'Strava, Health Connect (Garmin) and FIT/GPX activities flow into a group feed with comments and reactions.',
  P3: 'A group trains on a shared marathon program; VO2max/VDOT, PMC and zone charts per member.',
  P4: 'Real-time group chat over Durable Objects with FCM push notifications.',
  P5: 'The AI coach explains workouts and plans and answers @coach in group chat.',
  P6: 'Security hardening, legal pages, observability, and a signed v0.1 APK on GitHub Releases.',
  Later: 'Parked: photos in chat, Play Store publication, direct Garmin API, iOS (Swift).',
};

export const PRIORITIES = ['high', 'medium', 'low'] as const;
export type Priority = (typeof PRIORITIES)[number];

export const SIZES = ['XS', 'S', 'M', 'L'] as const;
export type Size = (typeof SIZES)[number];

export const ISSUE_TYPES = ['feature', 'task', 'spike', 'docs', 'test', 'chore'] as const;
export type IssueType = (typeof ISSUE_TYPES)[number];

/** A workstream becomes one GitHub Project and one `area:*` label. */
export interface Workstream {
  /** Short uppercase key, prefix of every epic/issue key in it (e.g. `CLOUD`). */
  key: string;
  /** GitHub Project title (prefixed with "Stride · " by the seeder). */
  title: string;
  /** `area:<area>` label. */
  area: string;
  /** One-paragraph Project README / short description. */
  description: string;
  /**
   * Also add every issue (from any workstream) in these phases to this
   * workstream's Project — e.g. the MVP Project collects re-phased ANDF/GOV work.
   */
  includePhases?: Phase[];
}

/** An epic is a parent GitHub issue; its issues are attached as sub-issues. */
export interface Epic {
  /** `<WS>-E<n>`, e.g. `CLOUD-E1`. */
  key: string;
  title: string;
  phase: Phase;
  /** Markdown: what the epic delivers and why. */
  summary: string;
}

export interface Issue {
  /** `<WS>-<nnn>`, e.g. `CLOUD-003`. Stable forever — the seeder's idempotency key. */
  key: string;
  /** Parent epic key (must be in the same workstream file). */
  epic: string;
  /** Imperative, ≤ 80 chars, no key prefix (the seeder adds `[KEY]`). */
  title: string;
  phase: Phase;
  priority: Priority;
  size: Size;
  type: IssueType;
  /** `true` → an agent may pick it up. `false` → `human-only` (accounts, secrets, legal sign-off). */
  agent: boolean;
  /** Markdown: why, background, links to ADRs / GOAL.md sections. */
  context: string;
  /** Checklist items. Each must be objectively verifiable. */
  acceptance: string[];
  /** How it is proven offline: the test file(s)/commands that must pass. */
  testPlan: string[];
  /** Paths created or touched (globs ok). Helps agents and reviewers scope. */
  files?: string[];
  /** Issue keys (any workstream) that must be closed before this starts. */
  dependsOn?: string[];
}

export interface WorkstreamBacklog {
  workstream: Workstream;
  epics: Epic[];
  issues: Issue[];
}
