---
"@stride/core": patch
---

Close TypeScript test gaps (API routes and error mapping, planner property tests, core helpers, MCP live mode, factory gh client) and enforce per-package Vitest coverage thresholds in CI; the API now returns a 400 envelope for malformed JSON and a 404 envelope for unknown routes.
