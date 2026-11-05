<!-- Thanks for contributing to Stride! -->

## What & why

<!-- What does this change do, and why? Link any related issue. -->

Closes #

## Checklist

- [ ] `pnpm lint`, `pnpm typecheck`, `pnpm test`, and `pnpm build` pass
- [ ] Added/updated tests for behavior changes
- [ ] Numbers are computed in `packages/core`, not by the LLM (see `GOAL.md`)
- [ ] Plan-related changes keep the deterministic guardrail (ramp / rest / back-to-back / long-run caps)
- [ ] Respects Strava API terms (read-only scopes, owner-only data, attribution)
- [ ] Commits are signed off (`git commit -s`, DCO)

## Screenshot baselines changed?
- [ ] No Roborazzi baselines changed
- [ ] Baselines were re-recorded on CI and the before/after images are linked above (see `docs/testing/screenshots.md`)
