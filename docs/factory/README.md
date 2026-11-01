# The Stride software factory

Stride Groups (native Android + Cloudflare, see ADRs 0005–0009) is built by a
**factory**:
- The backlog is code.
- GitHub Issues and Projects are the work queue.
- Claude agents (local or in the cloud) pull the next unblocked issue and ship it as a PR.
- CI decides whether that PR is mergeable.

```
tools/factory/src/backlog/*.ts  ──pnpm factory seed──▶  GitHub issues + sub-issues + blocked-by
        (source of truth)                                 + milestones + 14 Projects
                                                                │
                      ┌──────── pnpm factory next ◀─────────────┘
                      ▼
   /factory-next skill: claim → branch/worktree → implement → pnpm check → PR (Closes #n)
                      ▼
          CI `build` gate (required) → human merges (admin) → issue closes → unblocks dependents
```

## The pieces
| Piece | Where |
|---|---|
| Backlog: 13 workstreams, about 40 epics and about 155 issues | `tools/factory/src/backlog/*.ts` (typed by `src/types.ts`) |
| Validator: unique keys, anchors, dependencies resolve, no cycles, no phase inversions, acceptance criteria and test plan present | `pnpm factory validate`. It also runs in `pnpm test`. |
| Seeder (idempotent) | `pnpm factory seed [--dry-run] [--skip-projects] [--private]` |
| Dispatcher | `pnpm factory next [--workstream cloud] [--max-phase P1] [--all] [--json]` |
| Claim, release, status | `pnpm factory claim <n>`, `pnpm factory release <n> [--needs-human]`, `pnpm factory status <n> "In review"` |
| Agent procedure | `.claude/skills/factory-next/SKILL.md` |
| Cloud environment bootstrap | `scripts/factory/setup-cloud-env.sh` |

## GitHub layout
- **Projects (owner `jaypetez`):**
  - **Stride · Factory (Orchestrator)** holds every issue. Its fields are Status, Workstream, Phase, Priority, Size and Agent. Its views are Board, Next up (agents), Human-only and Epics.
  - One **Stride · <Workstream>** project per workstream, 13 in all including *Parked / Later*.
- **Milestones:** one per phase, P0 … P6 plus Later.
- **Labels:**
  - Classification: `area:*`, `phase:*`, `priority:*`, `size:*`, `type:*`.
  - Who can work it: `agent:ok` or `human-only`.
  - Structure: `epic`.
  - Claim state: `status:in-progress` and `status:needs-human`.
- **Native GitHub links:** sub-issues link each issue to its epic, and "blocked by" links carry the dependencies.

### How an issue becomes ready
The dispatcher reads **live** GitHub state. An issue is ready when it meets all of these:
- it is open and labelled `agent:ok`, and is not an epic
- it is unassigned and carries neither `status:*` label
- it has **0 open blockers**
- it has no open PR that closes it

Among ready issues, the dispatcher ranks by phase, then priority, then key.

## Starting the factory
1. **Do the human-only P0 issues.** List them with `pnpm factory next --include-human --all`, or open the *Human-only* view.
   - These cover Cloudflare, Strava app, Firebase, Google OAuth, Anthropic, keystore and domain.
   - Most dependents can still proceed offline without them, because tests use fakes.
2. **Start workers.** Use any mix of:
   - **Local:** `claude` in the repo root → `/factory-next`. For a continuous loop, `/loop /factory-next`. To run parallel lanes, open several terminals with `/factory-next cloud`, `/factory-next android`, and so on. Each one works in its own git worktree.
   - **Cloud:** in Claude Code on the web, or a scheduled routine, point at `jaypetez/stride` with the setup script `scripts/factory/setup-cloud-env.sh`, and prompt `/factory-next` (optionally with a workstream).
     - The Claude GitHub App must be installed on the repo.
     - The `gh` token needs the `repo` and `project` scopes.
3. **Review and merge.**
   - The `build` job in CI is the single required check.
   - `main` needs one approval. As the solo maintainer, merge with `gh pr merge --admin --squash`.
   - Once the factory has earned trust, consider auto-merge for `size:XS`/`size:S` PRs.
4. **Watch progress** on the Orchestrator board. Each milestone ends in a demoable slice. Demo it before starting the next phase in earnest.

## Changing the plan
1. Edit `tools/factory/src/backlog/<ws>.ts`.
2. Run `pnpm factory validate` and then `pnpm factory seed`.
3. Open a PR with the change.

New keys create new issues. Changed entries update the issue body; the hash marker detects the change, and manual edits to managed bodies are overwritten. The seeder never deletes issues: to drop work, close the issue as "not planned" and remove its entry.

## Stop conditions (from AGENTS.md)
If the same command fails 2–3 times with the same error:
1. The agent stops.
2. It comments the error on the issue.
3. It runs `pnpm factory release <n> --needs-human`.

Never retry into bankruptcy.
