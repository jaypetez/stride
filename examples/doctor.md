# `stride doctor`

Preflight check: shows the environment, which credentials are configured, and
exactly what runs offline versus what needs credentials. It reads nothing
sensitive beyond checking whether a token file exists, so it is safe to run any
time.

## Command

```bash
pnpm --filter @stride/cli dev -- doctor
```

## Output

```text
Environment
  Node:            v24.14.1
  Platform:        win32
  Data dir:        C:\...\stride\apps\cli\.stride
  Reference clock: system clock
  Log level:       warn (default)

Credentials
  Strava app:      not set (connect/sync unavailable)
  Strava tokens:   not connected (run `stride connect`)
  Anthropic key:   not set (deterministic fallback)

Runs offline — no credentials needed
  stride analyze --demo   ·   stride next --demo   ·   stride plan --demo
  API demo endpoints (?demo=true)   ·   MCP demo tools ({ demo: true })

Needs credentials
  Strava OAuth: connect, sync, and analyzing your own data
  Anthropic key: optional — enriches coaching prose (never required)
✓ Preflight complete.
  Tip: set STRIDE_NOW=<ISO> for byte-reproducible demo `next`/`plan` output.
```

## Machine-specific lines

`doctor` reflects your actual environment, so a few lines vary by machine and are
**not** reproducible:

- `Node:` — your Node version (this capture: `v24.14.1`).
- `Platform:` — your OS (`win32` / `linux` / `darwin`).
- `Data dir:` — the resolved local store path. This is **cwd-relative** (default
  `.stride`), so it depends on where you run from — run from the repo root or set
  an absolute `STRIDE_DATA_DIR` (see AGENTS.md "cwd-relative store"). The
  absolute path above is elided.

The `Credentials` block reflects whether you have set `STRAVA_CLIENT_ID` /
`STRAVA_CLIENT_SECRET`, run `stride connect`, and set `ANTHROPIC_API_KEY`. The
output above is a fresh checkout with no `.env`.
