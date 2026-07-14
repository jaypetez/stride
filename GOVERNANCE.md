# Stride Governance

This document describes how the Stride project is governed. It is intentionally
lightweight for an early-stage project and will grow as the community does.

## Roles

- **Users** — anyone who runs Stride. Feedback via issues and discussions is the
  primary input to the roadmap.
- **Contributors** — anyone who opens a pull request, files a triaged issue, or
  improves the docs. See [CONTRIBUTING.md](CONTRIBUTING.md).
- **Maintainers** — contributors with merge rights and release responsibility.
  The current list lives in [MAINTAINERS.md](MAINTAINERS.md).

## Decision-making

Stride currently follows a **benevolent-maintainer** model: the maintainers in
[MAINTAINERS.md](MAINTAINERS.md) make final decisions, but strive for
lazy consensus — a proposal (issue or PR) with no sustained objection after a
reasonable review window is accepted.

Substantial or hard-to-reverse decisions (public API, the sports-science
methodology, Strava-compliance posture, license, or stack — see
[`GOAL.md`](GOAL.md)) are recorded as **Architecture Decision Records** under
`docs/adr/` and referenced from the PR that implements them.

The non-negotiable engineering invariants are documented in
[`AGENTS.md`](AGENTS.md) ("Conventions & boundaries") and `GOAL.md` §3–§4:
compute-in-code / reason-in-LLM, deterministic guardrails, the explicit safety
layer, and Strava compliance by construction. Changes that weaken an invariant
require a maintainer decision and an ADR.

## Becoming a maintainer

A contributor with a sustained track record of high-quality, reviewed
contributions may be nominated by an existing maintainer and added by lazy
consensus of the current maintainers. Maintainers who become inactive for an
extended period may be moved to emeritus status.

## Changing this document

Governance changes are proposed by pull request and accepted by consensus of the
current maintainers.

## Code of Conduct

Participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md).
