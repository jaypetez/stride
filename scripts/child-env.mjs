/**
 * Build a child-process environment for the smoke/verify harness that ALWAYS
 * takes the deterministic, offline no-LLM / no-network branch.
 *
 * Scrubs live-credential vars (`ANTHROPIC_API_KEY`, any `STRAVA_*`) from the
 * base env so `pnpm verify` stays reproducible even on a developer/CI machine
 * that has them set, then layers deterministic pins and caller extras on top.
 *
 * Kept as a tiny, dependency-free, ASCII-only module (no imports, no Unicode)
 * so it can be imported cleanly by the harness AND by a unit test on any
 * platform — unlike the root harness script, which pulls in node built-ins and
 * prints Unicode status glyphs.
 */
export function scrubbedEnv(baseEnv = {}, pins = {}, extraEnv = {}) {
  const env = { ...baseEnv, ...pins, ...extraEnv };
  delete env.ANTHROPIC_API_KEY;
  for (const key of Object.keys(env)) {
    if (key.startsWith('STRAVA_')) delete env[key];
  }
  return env;
}
