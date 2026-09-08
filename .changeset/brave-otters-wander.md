---
"@stride/api": patch
"@stride/cli": patch
"@stride/config": patch
"@stride/core": patch
"@stride/mcp": patch
"@stride/schemas": patch
"@stride/web": patch
---

Batch dependency maintenance. Runtime: `hono` 4.13.5 → 4.13.7 (api, web),
`@anthropic-ai/sdk` 0.122.0 → 0.124.0 (core), `@hono/zod-validator` 0.9.0 →
0.9.1 (api), and `@clack/prompts` 1.7.0 → 1.8.0 (cli). Dev tooling: `vitest`
and `@vitest/coverage-v8` 4.1.11 → 5.0.0, `@types/node` 26.2.0 → 26.5.0,
`@playwright/test` 1.62.1 → 1.63.0, `@changesets/cli` 3.0.1 → 3.0.2, and
`lint-staged` 17.4.1 → 17.5.0.

`@clack/prompts` 1.8.0 pulls `@clack/core` 1.5.0, which retypes `isCancel` from
`(value) => value is symbol` to `(value) => value is typeof CANCEL_SYMBOL`.
TypeScript therefore no longer narrows `boolean | symbol` to `boolean` after the
guard, so the PAR-Q screening loop in `stride profile` needed an explicit
`boolean` assertion at one call site. Type-level only; no runtime behaviour
changed.
