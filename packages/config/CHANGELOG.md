# @stride/config

## 0.2.0

### Patch Changes

- 19e260c: docs: architecture, ADRs, per-package READMEs, and worked examples

  Add `docs/architecture.md` (three-layer model + data flow + diagram), four ADRs
  under `docs/adr/` (raw-`.ts` workspaces, durable daily-load series, Option A plan
  generation, advisory sync lock), per-package READMEs for core/schemas/config and
  the cli/api/mcp apps, and a runnable `examples/` directory with real,
  byte-reproducible offline command output. README updated with `doctor`/`profile`,
  the `--note` flag and PAR-Q screening, a Documentation section, and an OpenSSF
  Best Practices badge placeholder. Docs-only; no runtime code changed.

## 0.1.0
