---
"@stride/api": patch
"@stride/cli": patch
"@stride/config": patch
"@stride/core": patch
"@stride/mcp": patch
"@stride/schemas": patch
"@stride/web": patch
---

Dependency maintenance batch. No runtime behaviour changed outside routine
patch/minor bumps to third-party libraries.

Runtime deps: `zod` 4.5.4 -> 4.6.5, `@anthropic-ai/sdk` 0.124.0 -> 0.127.0,
`@clack/prompts` 1.8.0 -> 1.8.1, `react`/`react-dom`/`@types/react`/
`@types/react-dom` 19.2.x -> 19.3.0.

The `react`/`react-dom` pair and the `@anthropic-ai/sdk` bump each needed a
Dependabot rebase to land: React refuses to boot when `react` and `react-dom`
resolve to different versions ("Incompatible React versions"), so the two
PRs had to merge back-to-back rather than independently; the SDK PR's first
CI run predated the React merge and was retested after rebasing onto the
updated lockfile.

CI: `github/codeql-action` (`init`/`analyze`/`upload-sarif`) 4.37.9 -> 4.38.0.
`init` and `analyze` pin the same SHA in `codeql.yml`, so Dependabot's split
PRs each show a version mismatch and fail their own `Analyze` check in
isolation — same shape as the React pairing above, resolved by merging both.

Dev tooling: `@biomejs/biome` 2.5.12 -> 2.5.14, `@changesets/cli` 3.0.2 ->
3.0.3, `lint-staged` 17.5.0 -> 17.5.1, `turbo` 2.10.12 -> 2.11.2, `happy-dom`
20.14.0 -> 20.14.5, `vite` 8.2.2 -> 8.3.0.

`vitest`/`@vitest/coverage-v8` are deliberately held at 5.0.0 (Dependabot
proposed 5.0.1): bumping them makes `@testing-library/jest-dom@7.0.1`'s
vitest module augmentation resolve against the 5.0.1 peer variant, and every
jest-dom matcher (`toBeInTheDocument`, etc.) disappears from the `Assertion`
type, breaking `apps/web`'s typecheck. jest-dom's own peer range (`>= 0.32`)
is too loose to catch this at install time. Revisit once jest-dom ships a
vitest 5.0.1-compatible release.
