/**
 * Client-side validation of the plan form's "Weeks" field (AUD-020). The input
 * value is a string, and `Number('')` is 0 — which used to reach the API as
 * `weeks: 0` and come back as a confusing 400. Mirrors the API's `PlanBody`
 * bound (`z.number().int().positive().max(52)`); a build plan (MVP-026) passes
 * its own 4–16 range.
 */
export function parseWeeks(
  raw: string,
  range: { min: number; max: number } = { min: 1, max: 52 },
): { weeks: number } | { error: string } {
  const { min, max } = range;
  const text = raw.trim();
  if (text === '') return { error: `Enter the number of weeks (${min}–${max}).` };
  const n = Number(text);
  if (!Number.isInteger(n) || n < min || n > max) {
    return { error: `Weeks must be a whole number from ${min} to ${max}.` };
  }
  return { weeks: n };
}
