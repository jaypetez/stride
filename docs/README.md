# Stride documentation

Developer and design documentation for Stride, the local-first Strava AI running
coach. For the product intent and roadmap, start with the top-level
[`GOAL.md`](../GOAL.md).

## Contents

- [`architecture.md`](architecture.md) — the three-layer model
  (deterministic compute → Claude reasoning → guardrail/safety), the data flow
  from Strava to the coach, and the four surfaces over one shared core.
- [`adr/`](adr/) — Architecture Decision Records for the non-obvious calls (raw
  `.ts` workspaces, the durable daily-load series, Option A plan generation, the
  advisory sync lock). See the [ADR index](adr/README.md).
- [`../examples/`](../examples/) — real, byte-reproducible output from the
  offline demo commands.

## Related top-level docs

- [`GOAL.md`](../GOAL.md) — the north-star brief.
- [`AGENTS.md`](../AGENTS.md) — machine-readable command manifest, conventions,
  and gotchas.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — how to get set up and contribute.
- Per-package READMEs live next to each package (`packages/*/README.md`,
  `apps/*/README.md`).

## Site-ready

These files are plain, self-contained Markdown with relative links, structured
so a documentation site (Astro Starlight or Docusaurus, per GOAL §10 Phase 3)
can be layered on later without rewriting content. Building that site is out of
scope for now.
