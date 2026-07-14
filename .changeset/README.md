# Changesets

This folder is managed by [Changesets](https://github.com/changesets/changesets) —
it drives Stride's versioning and per-package changelogs.

## Adding a changeset

When your PR changes behavior a user would notice, add a changeset:

```bash
pnpm changeset
```

Pick the bump (`patch` / `minor` / `major`) and write a one-line summary. This
creates a markdown file here; commit it with your PR.

All `@stride/*` packages are **fixed** to a single shared version (see
`config.json`), so one changeset versions the whole product together.

## Releasing

On merge to `main`, the Release workflow (`.github/workflows/release.yml`) opens
(or updates) a **"Version Packages"** PR that consumes the pending changesets,
bumps versions, and updates each package's `CHANGELOG.md`. Merging that PR cuts
the release. Packages are `private`, so nothing is published to npm.
