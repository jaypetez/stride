import { useMutation, useQuery } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { api, errorMessage, errorStatus, type RaceOption } from './api';
import { PmcChart } from './components/PmcChart';
import { PoweredByStrava } from './components/PoweredByStrava';
import { Disclaimer, SafetyFlags } from './components/SafetyFlags';
import { formatDuration, formatKm, formatPace } from './format';
import { parseWeeks } from './validate';

type Mode = 'demo' | 'live';

export function App() {
  const [mode, setMode] = useState<Mode>('demo');
  const [note, setNote] = useState('');
  const demo = mode === 'demo';

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>
            Stride <span className="tag">beta</span>
          </h1>
          <p className="subtitle">Your Strava agentic coach</p>
        </div>
        {/* Two toggle buttons, not a tablist: each one exposes its state via aria-pressed. */}
        <fieldset className="mode-toggle" aria-label="Data mode">
          <button
            type="button"
            className={demo ? 'active' : ''}
            aria-pressed={demo}
            onClick={() => setMode('demo')}
          >
            Demo
          </button>
          <button
            type="button"
            className={!demo ? 'active' : ''}
            aria-pressed={!demo}
            onClick={() => setMode('live')}
          >
            My data
          </button>
        </fieldset>
      </header>

      {!demo && (
        <p className="notice">
          Showing your synced data. If it's empty, run <code>stride connect</code> then{' '}
          <code>stride sync</code> in the CLI, and start the API with{' '}
          <code>pnpm --filter @stride/api dev</code>.
        </p>
      )}

      <NoteForm note={note} onSubmit={setNote} />

      <main className="grid">
        <FormCard demo={demo} />
        <NextCard demo={demo} note={note} />
        <section className="card wide">
          <h2>Fitness trend</h2>
          <FitnessTrend demo={demo} />
        </section>
        <AnalysisCard demo={demo} note={note} />
        <ActivitiesCard demo={demo} />
        <PlanCard demo={demo} note={note} />
      </main>

      <footer className="footer">
        <span className="footer-attribution">
          Stride is not affiliated with Strava. <PoweredByStrava />
        </span>
        <span className="muted">
          Informational and educational purposes only — not medical advice.
        </span>
      </footer>
    </div>
  );
}

/**
 * "How do you feel?" check-in. The note is screened by core's deterministic
 * red-flag rules on every coach call (next, analyze, plan) — the same as the
 * CLI's `--note` — and any STOP/WARN flags render above the results.
 */
function NoteForm({ note, onSubmit }: { note: string; onSubmit: (note: string) => void }) {
  const [draft, setDraft] = useState(note);
  const submit = (e: FormEvent) => {
    e.preventDefault();
    onSubmit(draft.trim());
  };
  return (
    <form className="note-form" onSubmit={submit}>
      <label>
        How do you feel today? (optional — screened for safety red flags)
        <input
          type="text"
          value={draft}
          placeholder="e.g. legs feel heavy"
          onChange={(e) => setDraft(e.target.value)}
        />
      </label>
      <button type="submit">Check in</button>
    </form>
  );
}

/** Loading placeholder announced politely to assistive tech. */
function Loading() {
  return (
    <p className="muted" role="status" aria-busy="true">
      Loading…
    </p>
  );
}

/** Error line using the API's own `error` message when it sent one. */
function ErrorText({ error, fallback }: { error: unknown; fallback?: string }) {
  return (
    <p className="error" role="status">
      {errorMessage(error, fallback)}
    </p>
  );
}

function FormCard({ demo }: { demo: boolean }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['pmc', demo],
    queryFn: () => api.pmc(demo),
  });
  return (
    <section className="card" aria-busy={isLoading}>
      <h2>Current form</h2>
      {isLoading && <Loading />}
      {error && <ErrorText error={error} />}
      {data?.latest && (
        <div className="stats">
          <Stat label="Fitness (CTL)" value={data.latest.ctl.toFixed(0)} />
          <Stat label="Fatigue (ATL)" value={data.latest.atl.toFixed(0)} />
          <Stat label="Form (TSB)" value={data.latest.tsb.toFixed(0)} accent />
          <Stat
            label="ACWR"
            value={data.latestAcwr ? `${data.latestAcwr.acwr}` : '—'}
            sub={data.latestAcwr?.flag}
          />
          <Stat label="Ramp / wk" value={data.rampRatePerWeek.toFixed(1)} />
        </div>
      )}
      {data && !data.latest && <p className="muted">No training data yet.</p>}
    </section>
  );
}

function NextCard({ demo, note }: { demo: boolean; note: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['next', demo, note],
    queryFn: () => api.next(demo, note),
  });
  return (
    <section className="card" aria-busy={isLoading}>
      <h2>Next workout</h2>
      {isLoading && <Loading />}
      {error && <ErrorText error={error} />}
      {data && (
        <div>
          <SafetyFlags flags={data.flags} />
          <h3 className="workout-title">{data.workout.title}</h3>
          <div className="chips">
            {data.workout.targetDurationSec ? (
              <span className="chip">{formatDuration(data.workout.targetDurationSec)}</span>
            ) : null}
            {data.workout.targetPaceSecPerKm ? (
              <span className="chip">{formatPace(data.workout.targetPaceSecPerKm)}</span>
            ) : null}
            {data.workout.targetHrZone ? (
              <span className="chip">HR Z{data.workout.targetHrZone}</span>
            ) : null}
            {data.workout.targetTss ? (
              <span className="chip">{data.workout.targetTss} TSS</span>
            ) : null}
          </div>
          <p>{data.workout.description}</p>
          <p className="muted why">Why: {data.workout.rationale}</p>
          <Disclaimer text={data.disclaimer} />
        </div>
      )}
    </section>
  );
}

function FitnessTrend({ demo }: { demo: boolean }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['pmc', demo],
    queryFn: () => api.pmc(demo),
  });
  if (isLoading) return <Loading />;
  if (error || !data) return <ErrorText error={error} />;
  return <PmcChart pmc={data.pmc} />;
}

function AnalysisCard({ demo, note }: { demo: boolean; note: string }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['analyze', demo, note],
    queryFn: () => api.analyze(demo ? 'demo' : 'last', note),
  });
  // A 404 is the genuine empty state (nothing synced yet); anything else is a
  // real failure and shows the API's own message.
  const empty = error && errorStatus(error) === 404;
  return (
    <section className="card" aria-busy={isLoading}>
      <h2>Latest analysis</h2>
      {isLoading && <Loading />}
      {empty && <p className="muted">No activity to analyze yet.</p>}
      {error && !empty && <ErrorText error={error} />}
      {data && (
        <div>
          <SafetyFlags flags={data.flags} />
          <h3 className="workout-title">{data.analysis.headline}</h3>
          <div className="chips">
            <span className="chip">{data.metrics.tss} TSS</span>
            <span className="chip">{data.metrics.method}</span>
            {data.metrics.averagePaceSecPerKm ? (
              <span className="chip">{formatPace(data.metrics.averagePaceSecPerKm)}</span>
            ) : null}
            {data.metrics.aerobicDecouplingPct !== undefined ? (
              <span className="chip">decoup {data.metrics.aerobicDecouplingPct}%</span>
            ) : null}
          </div>
          <p>{data.analysis.explanation}</p>
          <Disclaimer text={data.disclaimer} />
        </div>
      )}
    </section>
  );
}

function ActivitiesCard({ demo }: { demo: boolean }) {
  const { data, isLoading, error } = useQuery({
    queryKey: ['activities', demo],
    queryFn: () => api.activities(demo),
  });
  return (
    <section className="card wide" aria-busy={isLoading}>
      <h2>Recent activities</h2>
      {isLoading && <Loading />}
      {error && <ErrorText error={error} />}
      {data && data.length === 0 && <p className="muted">No activities.</p>}
      {data && data.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Name</th>
              <th>Distance</th>
              <th>Time</th>
              <th>
                <span className="sr-only">Link</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {data.slice(0, 8).map((a) => (
              <tr key={a.id}>
                <td>{(a.startDateLocal ?? a.startDate).slice(0, 10)}</td>
                <td>{a.name}</td>
                <td>{formatKm(a.distance)}</td>
                <td>{formatDuration(a.movingTime)}</td>
                <td>
                  {a.source === 'strava' && (
                    <a
                      className="strava-link"
                      href={`https://www.strava.com/activities/${a.id}`}
                      target="_blank"
                      rel="noreferrer"
                    >
                      View on Strava
                    </a>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function PlanCard({ demo, note }: { demo: boolean; note: string }) {
  const [race, setRace] = useState<RaceOption>('10k');
  // Kept as the raw input text so an empty field is caught here (see parseWeeks).
  const [weeksText, setWeeksText] = useState('8');
  const [formError, setFormError] = useState<string | null>(null);
  const mutation = useMutation({
    mutationFn: (weeks: number) => api.plan({ demo, race, weeks, note: note || undefined }),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    const parsed = parseWeeks(weeksText);
    if ('error' in parsed) {
      setFormError(parsed.error);
      return;
    }
    setFormError(null);
    mutation.mutate(parsed.weeks);
  };

  const violations = mutation.data?.validation.violations ?? [];

  return (
    <section className="card wide" aria-busy={mutation.isPending}>
      <h2>Training plan</h2>
      <form className="plan-form" onSubmit={submit} noValidate>
        <label>
          Race
          <select value={race} onChange={(e) => setRace(e.target.value as RaceOption)}>
            <option value="5k">5k</option>
            <option value="10k">10k</option>
            <option value="half">Half</option>
            <option value="marathon">Marathon</option>
          </select>
        </label>
        <label>
          Weeks
          <input
            type="number"
            min={1}
            max={52}
            step={1}
            value={weeksText}
            aria-invalid={formError ? true : undefined}
            aria-describedby={formError ? 'plan-weeks-error' : undefined}
            onChange={(e) => setWeeksText(e.target.value)}
          />
        </label>
        <button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Generating…' : 'Generate plan'}
        </button>
      </form>
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
      {mutation.data && (
        <>
          <SafetyFlags flags={mutation.data.flags} />
          <p className="muted">{mutation.data.plan.summary}</p>
          {mutation.data.validation.valid ? (
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
              {mutation.data.plan.weeks.map((w) => (
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
          <Disclaimer text={mutation.data.disclaimer} />
        </>
      )}
    </section>
  );
}

function Stat({
  label,
  value,
  sub,
  accent,
}: {
  label: string;
  value: string;
  sub?: string;
  accent?: boolean;
}) {
  return (
    <div className={`stat ${accent ? 'accent' : ''}`}>
      <div className="stat-value">{value}</div>
      <div className="stat-label">{label}</div>
      {sub && <div className="stat-sub">{sub}</div>}
    </div>
  );
}
