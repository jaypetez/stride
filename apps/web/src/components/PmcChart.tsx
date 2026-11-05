import type { PmcPoint } from '@stride/schemas';

interface Props {
  pmc: PmcPoint[];
}

const W = 760;
const H = 240;
const PAD_X = 44;
const PAD_Y = 24;

type SeriesKey = 'ctl' | 'atl' | 'tsb';

/**
 * Each series differs by colour AND stroke pattern, so the chart still reads for
 * colour-blind users and in greyscale (WCAG 1.4.1 — use of colour).
 */
const SERIES: { key: SeriesKey; label: string; dash?: string }[] = [
  { key: 'ctl', label: 'CTL — fitness' },
  { key: 'atl', label: 'ATL — fatigue', dash: '8 5' },
  { key: 'tsb', label: 'TSB — form', dash: '2 4' },
];

const fmt = (v: number) => v.toFixed(0);

/** One sample per week (plus the latest day) for the screen-reader data table. */
function weeklySamples(data: PmcPoint[]): PmcPoint[] {
  const out: PmcPoint[] = [];
  for (let i = data.length - 1; i >= 0; i -= 7) out.unshift(data[i] as PmcPoint);
  return out;
}

/**
 * Minimal dependency-free SVG chart of CTL (fitness), ATL (fatigue), TSB (form),
 * with y-axis value labels, start/end date labels, non-colour line cues and a
 * visually hidden data table as its text alternative (AUD-021).
 */
export function PmcChart({ pmc }: Props) {
  if (pmc.length < 2) {
    return (
      <p className="muted">Not enough data yet — sync some activities to see your fitness trend.</p>
    );
  }
  const data = pmc.slice(-120);
  const values = data.flatMap((p) => [p.ctl, p.atl, p.tsb]);
  const max = Math.max(...values, 10);
  const min = Math.min(...values, 0);
  const range = max - min || 1;

  const x = (i: number) => PAD_X + (i / (data.length - 1)) * (W - 2 * PAD_X);
  const y = (v: number) => H - PAD_Y - ((v - min) / range) * (H - 2 * PAD_Y);
  const line = (key: SeriesKey) =>
    data
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`)
      .join(' ');

  const first = data[0] as PmcPoint;
  const last = data[data.length - 1] as PmcPoint;
  const zeroY = y(0);
  const yTicks = [...new Set([Math.round(max), 0, Math.round(min)])];
  const summary =
    `Daily fitness (CTL), fatigue (ATL) and form (TSB) from ${first.date} to ${last.date}. ` +
    `Latest: CTL ${fmt(last.ctl)}, ATL ${fmt(last.atl)}, TSB ${fmt(last.tsb)}.`;

  return (
    <div className="chart-wrap">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="chart"
        role="img"
        aria-labelledby="pmc-title pmc-desc"
      >
        <title id="pmc-title">Performance management chart</title>
        <desc id="pmc-desc">{summary}</desc>
        <line x1={PAD_X} y1={PAD_Y} x2={PAD_X} y2={H - PAD_Y} className="axis" />
        <line x1={PAD_X} y1={zeroY} x2={W - PAD_X} y2={zeroY} className="axis" />
        {yTicks.map((t) => (
          <text
            key={t}
            x={PAD_X - 6}
            y={y(t)}
            className="axis-label"
            textAnchor="end"
            dominantBaseline="middle"
          >
            {t}
          </text>
        ))}
        <text x={PAD_X} y={H - 4} className="axis-label" textAnchor="start">
          {first.date}
        </text>
        <text x={W - PAD_X} y={H - 4} className="axis-label" textAnchor="end">
          {last.date}
        </text>
        {SERIES.map((s) => (
          <path
            key={s.key}
            d={line(s.key)}
            className={`line ${s.key}`}
            fill="none"
            strokeDasharray={s.dash}
          />
        ))}
      </svg>
      <div className="legend" aria-hidden="true">
        {SERIES.map((s) => (
          <span key={s.key} className={`key ${s.key}`}>
            <svg width="22" height="6" className="key-swatch" aria-hidden="true">
              <line x1="0" y1="3" x2="22" y2="3" strokeDasharray={s.dash} />
            </svg>
            {s.label}
          </span>
        ))}
      </div>
      <table className="sr-only">
        <caption>Fitness, fatigue and form by week</caption>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Fitness (CTL)</th>
            <th scope="col">Fatigue (ATL)</th>
            <th scope="col">Form (TSB)</th>
          </tr>
        </thead>
        <tbody>
          {weeklySamples(data).map((p) => (
            <tr key={p.date}>
              <th scope="row">{p.date}</th>
              <td>{fmt(p.ctl)}</td>
              <td>{fmt(p.atl)}</td>
              <td>{fmt(p.tsb)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
