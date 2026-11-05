/**
 * Coach safety output for the web dashboard (AUD-018).
 *
 * Every coach response (`/next`, `/analyze`, `/plan`) carries `flags` — messages
 * from the deterministic red-flag rules in `@stride/core` — and a `disclaimer`.
 * The CLI prints flags FIRST, STOP flags loudest; the dashboard mirrors that so a
 * STOP all-rest plan can never render as a plain table.
 */

/**
 * A STOP-level message from core's keyword rules reads like "…Stop exercising and
 * seek advice from a medical professional…". Same test as the CLI (`apps/cli/src/ui.ts`).
 */
export function isStopFlag(flag: string): boolean {
  return /stop exercising|seek advice from a medical professional|seek medical advice/i.test(flag);
}

/** Flags banner: STOP flags first (danger styling), then warnings. Renders nothing when clear. */
export function SafetyFlags({ flags }: { flags?: readonly string[] }) {
  if (!flags || flags.length === 0) return null;
  const stops = flags.filter(isStopFlag);
  const warnings = flags.filter((f) => !isStopFlag(f));
  return (
    <div className="flags">
      {stops.map((f) => (
        <div key={`stop-${f}`} role="alert" className="flag flag-stop">
          <strong>STOP</strong> {f}
        </div>
      ))}
      {warnings.map((f) => (
        <div key={`warn-${f}`} role="alert" className="flag flag-warn">
          <strong>Warning</strong> {f}
        </div>
      ))}
    </div>
  );
}

/** The standard safety disclaimer attached to every coach output. */
export function Disclaimer({ text }: { text?: string }) {
  if (!text) return null;
  return <p className="disclaimer">{text}</p>;
}
