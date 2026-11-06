import { seedSummary } from '@stride/core/science';
import { useMutation } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { api, type PlanKindOption, type RaceOption } from '../api';
import { parseWeeks } from '../validate';
import { ErrorText } from './ErrorText';
import { Disclaimer, SafetyFlags } from './SafetyFlags';

/** Weeks a build plan (MVP-026) may last; mirrors the API's `kind: 'build'` bound. */
const BUILD_WEEKS = { min: 4, max: 16 } as const;
const RACE_WEEKS = { min: 1, max: 52 } as const;

/** Shown when a build plan had too few recent runs to seed from. */
export const NO_SEED_TEXT = 'Not enough recent runs — starting from a gentle default.';

/**
 * The training-plan card: a race plan toward a goal distance, or (MVP-026) a
 * no-race "build my fitness" block seeded from the last 4 weeks of training.
 */
export function PlanCard({ demo, note }: { demo: boolean; note: string }) {
  const [kind, setKind] = useState<PlanKindOption>('race');
  const [race, setRace] = useState<RaceOption>('10k');
  // Kept as the raw input text so an empty field is caught here (see parseWeeks).
  const [weeksText, setWeeksText] = useState('8');
  const [formError, setFormError] = useState<string | null>(null);
  const build = kind === 'build';
  const range = build ? BUILD_WEEKS : RACE_WEEKS;
  const mutation = useMutation({
    mutationFn: (weeks: number) =>
      api.plan(
        build
          ? { demo, kind, weeks, note: note || undefined }
          : { demo, race, weeks, note: note || undefined },
      ),
  });

  const choose = (next: PlanKindOption) => {
    setKind(next);
    setFormError(null);
    mutation.reset();
  };

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = parseWeeks(weeksText, range);
    if ('error' in parsed) {
      setFormError(parsed.error);
      return;
    }
    setFormError(null);
    mutation.mutate(parsed.weeks);
  };

  const data = mutation.data;
  const violations = data?.validation.violations ?? [];
  const builtPlan = data?.plan.kind === 'build';

  return (
    <section className="card wide" aria-busy={mutation.isPending}>
      <h2>Training plan</h2>
      {/* Two toggle buttons (like the data-mode toggle): each exposes its state via aria-pressed. */}
      <fieldset className="mode-toggle plan-kind" aria-label="Plan type">
        <button
          type="button"
          className={build ? '' : 'active'}
          aria-pressed={!build}
          onClick={() => choose('race')}
        >
          Race
        </button>
        <button
          type="button"
          className={build ? 'active' : ''}
          aria-pressed={build}
          onClick={() => choose('build')}
        >
          Build fitness
        </button>
      </fieldset>
      <form className="plan-form" onSubmit={submit} noValidate>
        {!build && (
          <label>
            Race
            <select value={race} onChange={(e) => setRace(e.target.value as RaceOption)}>
              <option value="5k">5k</option>
              <option value="10k">10k</option>
              <option value="half">Half</option>
              <option value="marathon">Marathon</option>
            </select>
          </label>
        )}
        <label>
          Weeks
          <input
            type="number"
            min={range.min}
            max={range.max}
            step={1}
            value={weeksText}
            aria-invalid={formError ? true : undefined}
            aria-describedby={formError ? 'plan-weeks-error' : undefined}
            onChange={(e) => setWeeksText(e.target.value)}
          />
        </label>
        <button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Generating…' : build ? 'Generate build plan' : 'Generate plan'}
        </button>
      </form>
      {build && (
        <p className="muted">
          A progressive plan from where you are now: base then build, a recovery week every 4th
          week, no race and no taper ({BUILD_WEEKS.min}–{BUILD_WEEKS.max} weeks).
        </p>
      )}
      {formError && (
        <p id="plan-weeks-error" className="error" role="alert">
          {formError}
        </p>
      )}
      {mutation.isError && (
        <ErrorText
          error={mutation.error}
          fallback="Could not generate a plan (is the API running?)."
        />
      )}
      {data && (
        <>
          <SafetyFlags flags={data.flags} />
          {builtPlan && (
            <p className="seed-summary">
              {data.seed ? `${seedSummary(data.seed)}.` : NO_SEED_TEXT}
            </p>
          )}
          <p className="muted">{data.plan.summary}</p>
          {data.validation.valid ? (
            <p className="ok">✓ Passes all guardrails.</p>
          ) : (
            <div className="warn">
              <p>Guardrail issues: {violations.length}</p>
              <ul className="violations">
                {violations.map((v) => (
                  <li key={`${v.rule}-${v.weekNumber ?? ''}-${v.message}`}>
                    {v.weekNumber !== undefined ? `Week ${v.weekNumber}: ` : ''}
                    {v.message}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <table className="table">
            <thead>
              <tr>
                <th>Week</th>
                <th>Phase</th>
                <th>TSS</th>
                <th>km</th>
                <th>Sessions</th>
              </tr>
            </thead>
            <tbody>
              {data.plan.weeks.map((w) => (
                <tr key={w.weekNumber}>
                  <td>{w.weekNumber}</td>
                  <td>
                    <span className="chip">{w.phase}</span>
                  </td>
                  <td>{w.targetTss}</td>
                  <td>{w.targetDistanceKm}</td>
                  <td className="muted">
                    {w.days
                      .flatMap((d) => d.sessions)
                      .filter((s) => s.type !== 'rest')
                      .map((s) => s.title.replace(/\s*\(.*\)$/, ''))
                      .join(' · ')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <Disclaimer text={data.disclaimer} />
        </>
      )}
    </section>
  );
}
