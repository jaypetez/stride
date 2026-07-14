# @stride/mcp

A read-only [Model Context Protocol](https://modelcontextprotocol.io) server that
exposes Stride's coach to any MCP client ("bring your Stride coach into Claude").
Every tool calls into `@stride/core` — the same deterministic engine behind the
CLI and API — so the facts MCP serves are byte-identical to the coach's.

Speaks MCP over **stdio**: `stdout` is the JSON-RPC protocol channel, so all logs
go to **stderr** (and dotenv loads with `{ quiet: true }`). Never `console.log`
in this app.

## Run it offline

```bash
pnpm --filter @stride/mcp dev        # speaks MCP over stdio
```

Call any tool with `{ "demo": true }` to run on bundled fixtures (no
credentials). The `pnpm verify` smoke harness drives the full
initialize → `tools/list` → `tools/call` handshake offline.

## Tools (8)

**Fact tools (5)** — thin adapters over the shared `COACH_TOOLS` in
`@stride/core`; each returns already-computed values and takes `demo?`:

| Tool | Returns |
|---|---|
| `get_training_load` | CTL (fitness), ATL (fatigue), TSB (form), ACWR, ramp rate |
| `get_recent_activities` | Recent activity summaries (`limit?`, default 10) |
| `get_pace_zones` | HR + pace training zones from the athlete's anchors |
| `get_next_workout_inputs` | The pre-computed signals behind the next-workout decision |
| `get_plan_context` | Goal, days-to-race, phase, experience, fitness, ramp, volume |

**Action tools (3)** — run the coach and emit the safety disclaimer; each accepts
an optional free-text `note` (threaded into red-flag detection) and `demo?`:

| Tool | Purpose |
|---|---|
| `analyze_workout` | Compute metrics for a workout (`id?`, most recent by default) and explain it |
| `suggest_next_workout` | Recommend the next workout from form, workload, and phase |
| `generate_plan` | Generate a periodized, guardrail-validated plan (`race?`, `weeks?`, `start?`, `date?`) |

## Notes

- The fact tools' order is **frozen** so the cached tool prefix never shifts
  (GOAL §8).
- `analyze_workout` / `suggest_next_workout` / `generate_plan` return the same
  computed numbers as the CLI/API; only prose differs when an `ANTHROPIC_API_KEY`
  is present. See the [architecture doc](../../docs/architecture.md).
