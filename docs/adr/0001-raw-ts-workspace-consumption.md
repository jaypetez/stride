# 0001. Raw-`.ts` workspace consumption (no TS project references)

- Status: Accepted
- Date: 2026-07-14

## Context

[`GOAL.md`](../../GOAL.md) §6 committed the monorepo to **TypeScript project
references with `composite: true`** — the conventional way to wire a pnpm +
Turborepo workspace, where each package emits declaration files and a
`.tsbuildinfo` so downstream packages consume built `dist/` output.

That design optimizes for incremental *builds*. Stride optimizes for something
different (AGENTS.md): an AI agent — or a human — being able to run the **entire
inner loop** (edit → lint → typecheck → test → run → observe) locally with no
build step and no network. A `composite` graph inserts a mandatory build between
"edit a file in `core`" and "run the CLI that imports it", which is exactly the
latency the project wants to remove.

The relevant facts in the current tree:

- `packages/config/tsconfig.base.json` sets `"noEmit": true` and
  `"declaration": false`; **`composite` is not set anywhere.**
- `packages/core/package.json` and `packages/schemas/package.json` map their
  `exports` straight at source — e.g. `"." : "./src/index.ts"` (plus subpath
  exports like `./science`, `./coach`, `./store`).
- `pnpm typecheck` runs `tsc --noEmit` per package via Turbo; the CLI/API/MCP
  `dev` scripts run `tsx src/index.ts` directly against the TypeScript sources.

## Decision

Consume `@stride/core` and `@stride/schemas` as **raw TypeScript source, with no
build step**. Do not use `composite`/project references. Each package
type-checks with `tsc --noEmit`; runtime execution goes through `tsx` (dev) or a
bundler that inlines the workspace sources (release builds).

Any app that produces a runnable bundle (`apps/cli`, `apps/api`, `apps/mcp`)
must therefore tell `tsup` to bundle the workspace packages rather than treat
them as external:

```ts
// apps/*/tsup.config.ts
noExternal: [/^@stride\//],
```

Without that, the emitted `dist/` would try to `import` a `.ts` file at runtime
and crash. This is called out in AGENTS.md under "Bundling workspace packages".

## Consequences

**Easier:**

- Tight agent/dev inner loop: editing a function in `core` is immediately live
  in the CLI/API/MCP with no rebuild, and tests run against source.
- No `.tsbuildinfo` staleness, no build-ordering bugs, no emitted `dist/` in the
  packages to keep in sync.
- The web app can import a pure, browser-safe subpath (`@stride/core/science`)
  for formatting helpers without pulling in Node-only code.

**Harder / watch out for:**

- Every new bundled app must remember `noExternal: [/^@stride\//]`; forgetting it
  is the classic "works in dev, breaks in `dist`" trap.
- Consumers can't treat the packages as pre-typed npm artifacts — they are
  private workspace packages (`"private": true`), not published, so this only
  works inside the monorepo.
- Type errors in `core` surface in every dependent package's typecheck rather
  than being isolated behind a declaration boundary. In practice this is fine at
  this size and is caught by the CI matrix.
- This is a documented **deviation from GOAL §6**; revisit it if Stride ever
  publishes these packages to a registry, at which point emitting `dist/` +
  types becomes worthwhile.
