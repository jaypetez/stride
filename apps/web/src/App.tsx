import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, type RaceOption } from './api';
import { PmcChart } from './components/PmcChart';
import { PoweredByStrava } from './components/PoweredByStrava';
import { formatDuration, formatKm, formatPace } from './format';

type Mode = 'demo' | 'live';

export function App() {
  const [mode, setMode] = useState<Mode>('demo');
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
        <div className="mode-toggle" role="tablist" aria-label="Data mode">
          <button type="button" className={demo ? 'active' : ''} onClick={() => setMode('demo')}>
            Demo
          </button>
          <button type="button" className={!demo ? 'active' : ''} onClick={() => setMode('live')}>
            My data
          </button>
        </div>
      </header>

      {!demo && (
        <p className="notice">
          Showing your synced data. If it's empty, run <code>stride connect</code> then{' '}
          <code>stride sync</code> in the CLI, and start the API with{' '}
          <code>pnpm --filter @stride/api dev</code>.
        </p>
      )}

      <main className="grid">
        <FormCard demo={demo} />
        <NextCard demo={demo} />
        <section className="card wide">
          <h2>Fitness trend</h2>
          <FitnessTrend demo={demo} />
        </section>
        <AnalysisCard demo={demo} />
        <ActivitiesCard demo={demo} />
        <PlanCard demo={demo} />
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

function FormCard({ demo }: { demo: boolean }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['pmc', demo],
    queryFn: () => api.pmc(demo),
  });
  return (
    <section className="card">
      <h2>Current form</h2>
      {isLoading && <p className="muted">Loading…</p>}
      {isError && <p className="muted">API unavailable.</p>}
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

function NextCard({ demo }: { demo: boolean }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['next', demo],
    queryFn: () => api.next(demo),
  });
  return (
    <section className="card">
      <h2>Next workout</h2>
      {isLoading && <p className="muted">Loading…</p>}
      {isError && <p className="muted">API unavailable.</p>}
      {data && (
        <div>
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
        </div>
      )}
    </section>
  );
}

function FitnessTrend({ demo }: { demo: boolean }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['pmc', demo],
    queryFn: () => api.pmc(demo),
  });
  if (isLoading) return <p className="muted">Loading…</p>;
  if (isError || !data) return <p className="muted">API unavailable.</p>;
  return <PmcChart pmc={data.pmc} />;
}

function AnalysisCard({ demo }: { demo: boolean }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['analyze', demo],
    queryFn: () => api.analyze(demo ? 'demo' : 'last'),
  });
  return (
    <section className="card">
      <h2>Latest analysis</h2>
      {isLoading && <p className="muted">Loading…</p>}
      {isError && <p className="muted">No activity to analyze yet.</p>}
      {data && (
        <div>
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
        </div>
      )}
    </section>
  );
}

function ActivitiesCard({ demo }: { demo: boolean }) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['activities', demo],
    queryFn: () => api.activities(demo),
  });
  return (
    <section className="card wide">
      <h2>Recent activities</h2>
      {isLoading && <p className="muted">Loading…</p>}
      {isError && <p className="muted">API unavailable.</p>}
      {data && data.length === 0 && <p className="muted">No activities.</p>}
      {data && data.length > 0 && (
        <table className="table">
          <thead>
            <tr>
              <th>Date</th>
              <th>Name</th>
              <th>Distance</th>
              <th>Time</th>
              <th />
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

function PlanCard({ demo }: { demo: boolean }) {
  const [race, setRace] = useState<RaceOption>('10k');
  const [weeks, setWeeks] = useState(8);
  const mutation = useMutation({ mutationFn: () => api.plan({ demo, race, weeks }) });

  return (
    <section className="card wide">
      <h2>Training plan</h2>
      <div className="plan-form">
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
            value={weeks}
            onChange={(e) => setWeeks(Number(e.target.value))}
          />
        </label>
        <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
          {mutation.isPending ? 'Generating…' : 'Generate plan'}
        </button>
      </div>
      {mutation.isError && (
        <p className="muted">Could not generate a plan (is the API running?).</p>
      )}
      {mutation.data && (
        <>
          <p className="muted">{mutation.data.plan.summary}</p>
          {mutation.data.validation.valid ? (
            <p className="ok">✓ Passes all guardrails.</p>
          ) : (
            <p className="warn">Guardrail issues: {mutation.data.validation.violations.length}</p>
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
