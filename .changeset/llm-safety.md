---
"@stride/core": minor
"@stride/mcp": minor
---

Modernize the Claude coach and enforce the safety layer.

- Real proposal→repair→reject plan loop (Option A): when an LLM is present, `generatePlan` asks Claude for a STRUCTURAL plan via structured outputs (`messages.parse` + `zodOutputFormat`, new `LlmPlanProposal` schema — phases and per-day type/emphasis/rationale, no numbers), then deterministically materializes each session with `makeSession` and runs the hardened `validatePlan`→`repairPlan`→re-validate loop. Valid → return; repaired-to-valid → return (`repaired: true`); unrepairable/refused/empty → reject and fall back to the always-valid skeleton. The no-key path is untouched, so golden snapshots stay byte-identical.
- Anthropic layer redesign: the `CoachLLM` seam now returns rich results (`CompleteResult`/`ParseResult`/`ClassifyResult`), with prompt caching (`cache_control` ephemeral on an expanded frozen persona), streaming for interactive paths, structured outputs for plans, adaptive thinking + per-tier `effort` (plan/opus=high, chat/sonnet=medium, classify/haiku=none), a tool runner over the shared toolset, refusal/truncation handling (discard model output → deterministic fallback), and usage/`request_id` audit logging (`coach/llm-log.ts`).
- Shared read-only toolset (`coach/tools.ts`): a `CoachDataProvider` interface plus the five §8 fact tools, reused by both the coach tool runner and the MCP server so MCP and the coach expose byte-identical facts. MCP is now a thin adapter over the core toolset (adds `get_next_workout_inputs` and `get_plan_context`), emits the disclaimer, and accepts a `note` input.
- Safety enforcement: a `disclaimer` is attached to every coach output (including the offline path); `analyzeWorkout` halts on a `stop` red flag (skips the LLM) like `suggestNextWorkout`; a `note` free-text param is threaded into `detectRedFlags` so `STOP_KEYWORDS` fire; a PAR-Q screening helper is exported; and an optional Haiku `classify` second pass augments WARNING-level flags on free text while keyword rules stay authoritative for `stop`.
