/**
 * Compliant "Powered by Strava" attribution (GOAL §4 / Strava brand guidelines).
 *
 * This is a self-contained, styled PLACEHOLDER badge: the exact required text
 * "Powered by Strava" set in Strava orange (#FC5200), visually distinct from and
 * deliberately less prominent than Stride's own wordmark. It does NOT reproduce
 * Strava's logo mark — we must not fabricate it.
 *
 * TODO(before any public / hosted deployment): swap this for the official
 * "Powered by Strava" raster badge from Strava's brand assets
 * (https://developers.strava.com/guidelines/). Tracked in apps/web/README.md.
 */
export function PoweredByStrava() {
  return (
    <a
      className="powered-by-strava"
      href="https://www.strava.com"
      target="_blank"
      rel="noreferrer"
      aria-label="Powered by Strava"
    >
      Powered by Strava
    </a>
  );
}
