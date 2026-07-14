# Worked examples

Real output from Stride's **offline demo mode** — no Strava account, no Anthropic
key, no network. Every command here runs against the bundled synthetic fixtures
(`packages/core/src/fixtures.ts`), so you can reproduce them on a fresh clone
after `pnpm install`.

| Example | Command |
|---|---|
| [`analyze.md`](analyze.md) | `stride analyze --demo` |
| [`next.md`](next.md) | `stride next --demo` |
| [`plan.md`](plan.md) | `stride plan --demo --race 10k --weeks 8` |
| [`doctor.md`](doctor.md) | `stride doctor` |

## How these were produced

Each command was run with the reference clock pinned to
`2026-07-14T12:00:00Z` and color disabled, e.g.:

```bash
pnpm --filter @stride/cli dev -- analyze --demo --now 2026-07-14T12:00:00Z
```

The captured text is the program's **stdout** with `NO_COLOR=1` (a real terminal
adds ANSI color; the numbers and layout are otherwise identical). Under pnpm the
script banner goes to stderr and is not part of the captured output.

## Why they are byte-reproducible

- **`--now` / `STRIDE_NOW` pins the clock.** Demo `next` and `plan` output is a
  function of the reference date; pin it and reruns are byte-identical (the
  `pnpm verify` smoke harness asserts exactly this).
- **Every number is computed deterministically** in `@stride/core` — training
  load, CTL/ATL/TSB, ACWR, zones, and all plan durations/paces/distances. These
  are identical on every machine, with or without an API key.
- **Prose is deterministic only without a key.** With no `ANTHROPIC_API_KEY`,
  the coach uses its templated fallback text (what you see here). If you *do*
  have a key set, the numbers stay the same but the `explanation` / `rationale`
  / plan `summary` prose is rewritten by Claude and will differ. To reproduce
  these files exactly, run without `ANTHROPIC_API_KEY`.

> Note on shells: if you invoke via `pnpm ... dev -- <args>` in Git Bash on
> Windows, the `--` separator may be forwarded literally; use PowerShell / a
> POSIX shell, or `pnpm --filter @stride/cli exec tsx src/index.ts <args>`.
