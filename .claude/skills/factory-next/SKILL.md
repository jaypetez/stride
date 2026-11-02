---
name: factory-next
description: Pull the next unblocked Stride factory issue from GitHub, implement it in an isolated worktree, prove it with the offline gates, and open a signed-off PR. Optional argument is a workstream (cloud, android, social, chat, integrations, engine, coach, quality, security, release, governance) or an issue number.
---

# /factory-next: work one factory issue to a green PR

You are a worker in the Stride software factory (`docs/factory/README.md`).
Read `AGENTS.md` first. Its conventions and stop conditions are binding.
Do **one** issue per invocation.

## 1. Pick
The dispatcher only returns issues up to the current focus phase (`tools/factory/src/focus.ts`, currently `MVP`: the Solo Garmin local-only Android app, ADR 0010). Don't work on later-phase issues unless the user explicitly asks.

- **No argument:** run `pnpm -s factory next --json`. The `-s` keeps pnpm's banner out of the JSON.
- **Workstream argument:** run `pnpm -s factory next --workstream <arg> --json`.
- **Issue-number argument:** use that issue, but only if `pnpm -s factory next --all --json` lists it as ready.
- **Nothing ready:** say so, list what is blocking the next few issues (`pnpm factory next --all --include-human`), and stop.

## 2. Claim and understand
1. Claim the issue with `pnpm factory claim <n>`. This assigns you, adds the `status:in-progress` label, and sets Project Status to In progress.
2. Read the issue with `gh issue view <n>`. Its acceptance criteria and **test plan** are the contract.
3. Read the ADRs and files it links to, and the closed issues it depended on along with their merged PRs, so you build on what exists.
4. If the issue is ambiguous in a way that changes the design, comment your assumption on the issue and proceed with the most conservative reading. Escalate (step 6) only if you are truly blocked.

## 3. Branch in isolation
- Create a git worktree on a new branch `factory/<n>-<short-slug>` from up-to-date `origin/main`.
- Never work on `main`.
- Never touch unrelated files. The user may have uncommitted work in the main checkout.

## 4. Implement and test (the gates are the verdict)
- **Write tests for the behaviour first**, or alongside it. Every test-plan item must exist and pass **offline**: no secrets, no network.
- **Respect the non-negotiables:**
  - Compute numbers in code and let the LLM only write prose.
  - Every plan feature ships with a guardrail test.
  - Every cloud route gets an entry in the authorization matrix (ADR 0009).
  - The Strava flags must be honoured (ADR 0006).
  - Never `console.log` in MCP.
- **Run the gates:**
  - Always run `pnpm check`.
  - Run `pnpm verify` if any CLI, API or MCP surface changed.
  - Run `pnpm check:android` (or `./android/gradlew -p android check`) if `android/` changed. If that script doesn't exist yet, the issue that adds it is probably your dependency.
- **Update the docs your change makes stale:** AGENTS.md commands, ADRs, READMEs.
- **Add a changeset** (`pnpm changeset`) if a package's behaviour changed.

## 5. Ship
- Commit with sign-off and conventional commits: `git commit -s -m "feat(cloud): … (#<n>)"`. End every message with the attribution line from your session instructions.
- Push the branch, then `gh pr create`:
  - **Title:** the conventional-commit summary.
  - **Body:** what changed, how each acceptance criterion is met (as a checklist), the gate commands you ran with their results, and `Closes #<n>`.
- Run `pnpm factory status <n> "In review"`.
- Watch CI with `gh pr checks <pr> --watch`. If CI fails, fix the failure and push again; this counts toward the stop condition. A Windows-runner crash with exit `-1073741502`, or a hang, is a known flake: re-run it, don't debug it.
- **Do not merge.** A human merges.

## 6. Stop conditions
Stop and escalate if any of these happen:
- the same failure occurs 2–3 times
- you need a secret or account that doesn't exist
- the issue contradicts an ADR

To escalate:
1. Comment the exact error and what you tried on the issue.
2. Run `pnpm factory release <n> --needs-human`.
3. Stop.

## 7. Report
Finish with:
- the issue
- the PR URL
- the gate results
- anything a reviewer should look at first
