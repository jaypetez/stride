# @stride/cli

The `stride` command-line coach — terminal-native workout analysis, next-workout
suggestions, and periodized plans over `@stride/core`. Built with commander +
`@clack/prompts`; runs via `tsx` in dev and bundles to `dist/` with tsup.

## Run it offline

No credentials needed — `analyze`, `next`, and `plan` all take `--demo` (bundled
synthetic data):

```bash
pnpm --filter @stride/cli dev -- analyze --demo
pnpm --filter @stride/cli dev -- next --demo
pnpm --filter @stride/cli dev -- plan --demo --race 10k --weeks 8
pnpm --filter @stride/cli dev -- doctor
```

See [`examples/`](../../examples/) for real captured output. For byte-identical
reruns, pin the clock with `--now <ISO>` (or `STRIDE_NOW`).

> Git Bash on Windows may forward the `--` separator literally; if so, drop it
> (`pnpm --filter @stride/cli dev analyze --demo`) or use
> `pnpm --filter @stride/cli exec tsx src/index.ts analyze --demo`.

## Commands

| Command | What it does | Notable flags |
|---|---|---|
| `analyze [id]` | Analyze a workout (most recent by default) | `--demo`, `--note <text>`, `--json` |
| `next` | Suggest your next workout from current form | `--demo`, `--note <text>`, `--json` |
| `plan` | Generate a periodized training plan | `--demo`, `--race <5k\|10k\|half\|marathon>`, `--weeks <n>`, `--start <date>`, `--date <race-date>`, `--note <text>`, `--json` |
| `doctor` | Preflight: tooling, configured credentials, what runs offline | — |
| `profile` | Show your athlete profile + anchors; optionally run screening | `--json`, `--screen` |
| `connect` | Authorize Stride with Strava (local loopback OAuth) | — |
| `sync` | Import Strava activities into the local store | `--pages <n>`, `--full`, `--rebuild`, `--backfill`, `--reconcile` |
| `disconnect` | Remove local Strava tokens (revokes on Strava, best-effort) | `--purge` (delete all local data) |

Global flags (before or after the subcommand): `--now <iso>` pins the reference
clock (also `STRIDE_NOW`); `--verbose` prints full stack traces and raises the
log level to `debug`.

### Flag notes

- **`--demo`** — use bundled fixtures; no Strava/Anthropic credentials required.
- **`--note <text>`** — free text on how you feel (`analyze`/`next`/`plan`). It
  is screened for safety red flags *before* any model call: a STOP keyword (e.g.
  "chest pain", "dizzy") halts coaching and returns a see-a-professional message;
  softer keywords add a warning. See `packages/core/src/coach/safety.ts`.
- **`--json`** — machine-readable output for scripting/diffing.
- **`--screen`** (on `profile`) — run the 7-question PAR-Q readiness screening in
  an interactive terminal; the result is persisted to your profile
  (`medicalClearance` + `healthFlags`) and constrains later coaching. Skips
  cleanly when stdin is not a TTY.

## Live use (bring your own credentials)

```bash
cp ../../.env.example ../../.env   # add STRAVA_CLIENT_ID/SECRET, optional ANTHROPIC_API_KEY
pnpm --filter @stride/cli dev -- connect
pnpm --filter @stride/cli dev -- sync
pnpm --filter @stride/cli dev -- next
```

The local store defaults to `./.stride` (**cwd-relative**) — run from the repo
root or set an absolute `STRIDE_DATA_DIR`. Anthropic is optional; without a key
the coach uses its deterministic fallback prose.
