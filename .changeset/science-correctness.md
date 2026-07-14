---
"@stride/core": patch
---

Fix three sports-science correctness defects: ACWR no longer raises false
`very_high` injury flags during the first ~4 weeks of history (warm-up window +
seeded EWMAs + low-chronic reliability guard); the PMC (fitness/fatigue/form) is
now projected to the reference day so fatigue decays over rest days instead of
freezing at the last activity; and treadmill (`trainer`) activities no longer
drive pace-based rTSS or VDOT anchors (their belt-estimated distance is
untrustworthy), falling back to heart-rate or duration.
