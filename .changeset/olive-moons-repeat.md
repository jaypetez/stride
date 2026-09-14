---
"@stride/api": patch
"@stride/cli": patch
"@stride/config": patch
"@stride/core": patch
"@stride/mcp": patch
"@stride/schemas": patch
"@stride/web": patch
---

Security and CI maintenance. No runtime behaviour changed — every change here is
confined to the dev toolchain and the workflow pins.

Security: the `js-yaml@4` override pinned `^4.3.1` and the lockfile resolved to
exactly 4.3.1, inside the vulnerable range of GHSA-2883-xcg3-v3hh (`>=4.0.0
<4.3.2`). `js-yaml` reaches the tree transitively via `@commitlint/cli >
@commitlint/load > cosmiconfig`, and Dependabot's security-update job runs with
`update-subdependencies: false`, so it could not fix this itself — its run
errored out. Both override lines now pin the patched versions (`js-yaml@4` →
`^4.3.2`, `js-yaml@3` → `^3.15.2`). The v3 pin is currently unused, since the
`@changesets/cli` 3.x bump dropped the `read-yaml-file` path that pulled it in,
but is kept as a defensive pin.

This advisory was failing the `audit` job, which fails the aggregate `build`
gate — the single required check on `main` — and so had the whole Dependabot
queue blocked.

CI: `pnpm/action-setup` 6.0.10 → 6.1.0 and `changesets/action` 2.1.1 → 2.1.2
(patch-only; no input renames, so the lockstep with `@changesets/cli` v3 holds).
