/**
 * Client-side validation of the plan form's "Weeks" field (AUD-020). The input
 * value is a string, and `Number('')` is 0 — which used to reach the API as
 * `weeks: 0` and come back as a confusing 400. Mirrors the API's `PlanBody`
 * bound (`z.number().int().positive().max(52)`).
 */
export function parseWeeks(raw: string): { weeks: number } | { error: string } {
  const text = raw.trim();
  if (text === '') return { error: 'Enter the number of weeks (1–52).' };
  const n = Number(text);
  if (!Number.isInteger(n) || n < 1 || n > 52) {
    return { error: 'Weeks must be a whole number from 1 to 52.' };
  }
  return { weeks: n };
}
