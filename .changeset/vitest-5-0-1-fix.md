---
"@stride/api": patch
"@stride/cli": patch
"@stride/config": patch
"@stride/core": patch
"@stride/mcp": patch
"@stride/schemas": patch
"@stride/web": patch
---

Completes the dev-dependencies bump from the previous release: `vitest`/
`@vitest/coverage-v8` 5.0.0 -> 5.0.1, held back at the time after CI showed
`apps/web` typecheck failures.

That was misdiagnosed as a vitest regression. `@vitest/expect`'s `Assertion`
type declaration is byte-identical between 5.0.0 and 5.0.1 (diffed the
packed npm tarballs directly). The real cause: Dependabot's frozen lockfile
snapshot resolved two different `@types/node` versions across the two
peer-qualified `vitest@5.0.1` variants in the workspace, so
`@testing-library/jest-dom`'s vitest type augmentation landed on a
different physical vitest instance than the one `apps/web`'s test files
actually imported. Re-resolving the lockfile today converges both variants
onto a single `@types/node` and fixes it, with no version pin changes
beyond the intended bump.
