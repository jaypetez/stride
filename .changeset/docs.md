---
"@stride/core": patch
"@stride/schemas": patch
"@stride/config": patch
"@stride/cli": patch
"@stride/api": patch
"@stride/mcp": patch
---

docs: architecture, ADRs, per-package READMEs, and worked examples

Add `docs/architecture.md` (three-layer model + data flow + diagram), four ADRs
under `docs/adr/` (raw-`.ts` workspaces, durable daily-load series, Option A plan
generation, advisory sync lock), per-package READMEs for core/schemas/config and
the cli/api/mcp apps, and a runnable `examples/` directory with real,
byte-reproducible offline command output. README updated with `doctor`/`profile`,
the `--note` flag and PAR-Q screening, a Documentation section, and an OpenSSF
Best Practices badge placeholder. Docs-only; no runtime code changed.
