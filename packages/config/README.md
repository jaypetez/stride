# @stride/config

Shared build configuration for the Stride monorepo. Currently exposes one thing:
the base TypeScript config that every package and app extends.

## Public surface

- `tsconfig.base.json` — the strict, `noEmit` TypeScript base. Exported as
  `@stride/config/tsconfig.base.json` and listed in `files`.

A consuming package extends it:

```jsonc
// packages/<name>/tsconfig.json
{
  "extends": "../config/tsconfig.base.json",
  "compilerOptions": { "types": ["node"] },
  "include": ["src", "test"]
}
```

## What the base sets

Notable options: `target` ES2022, `module`/`moduleResolution` ESNext/Bundler,
`strict: true`, `isolatedModules`, `noEmit: true`, `declaration: false`,
`skipLibCheck`. There is intentionally **no `composite`** — Stride consumes
workspace packages as raw `.ts` rather than via project references
([ADR 0001](../../docs/adr/0001-raw-ts-workspace-consumption.md)). Type-checking
is `tsc --noEmit` per package.

## Run

Nothing to run — it is config only. It is exercised by every other package's
`pnpm typecheck`.
