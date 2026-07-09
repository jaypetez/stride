import type { PmcPoint } from '@stride/schemas';

interface Props {
  pmc: PmcPoint[];
}

const W = 760;
const H = 240;
const PAD = 32;

/** Minimal dependency-free SVG chart of CTL (fitness), ATL (fatigue), TSB (form). */
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

  const x = (i: number) => PAD + (i / (data.length - 1)) * (W - 2 * PAD);
  const y = (v: number) => H - PAD - ((v - min) / range) * (H - 2 * PAD);
  const line = (key: 'ctl' | 'atl' | 'tsb') =>
    data
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(p[key]).toFixed(1)}`)
      .join(' ');

  const zeroY = y(0);

  return (
    <div className="chart-wrap">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="chart"
        role="img"
        aria-label="Performance management chart"
      >
        <line x1={PAD} y1={zeroY} x2={W - PAD} y2={zeroY} className="axis" />
        <path d={line('ctl')} className="line ctl" fill="none" />
        <path d={line('atl')} className="line atl" fill="none" />
        <path d={line('tsb')} className="line tsb" fill="none" />
      </svg>
      <div className="legend">
        <span className="key ctl">CTL — fitness</span>
        <span className="key atl">ATL — fatigue</span>
        <span className="key tsb">TSB — form</span>
      </div>
    </div>
  );
}
