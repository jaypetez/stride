import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PmcChart } from './PmcChart';

const PMC = [
  { date: '2026-07-07', ctl: 50, atl: 45, tsb: 8 },
  { date: '2026-07-08', ctl: 55, atl: 48, tsb: 7 },
  { date: '2026-07-09', ctl: 56, atl: 50, tsb: 5 },
];

describe('PmcChart', () => {
  it('renders an svg chart when given at least two points', () => {
    render(<PmcChart pmc={PMC} />);
    expect(screen.getByRole('img', { name: /Performance management chart/ })).toBeInTheDocument();
  });

  it('describes the chart in text, including the latest values', () => {
    render(<PmcChart pmc={PMC} />);
    const desc = document.getElementById('pmc-desc');
    expect(desc).toHaveTextContent('from 2026-07-07 to 2026-07-09');
    expect(desc).toHaveTextContent('Latest: CTL 56, ATL 50, TSB 5');
    expect(screen.getByRole('img')).toHaveAttribute('aria-labelledby', 'pmc-title pmc-desc');
  });

  it('labels the axes with values and dates', () => {
    const { container } = render(<PmcChart pmc={PMC} />);
    const labels = [...container.querySelectorAll('svg.chart text.axis-label')].map(
      (t) => t.textContent,
    );
    expect(labels).toEqual(expect.arrayContaining(['56', '0', '2026-07-07', '2026-07-09']));
  });

  it('distinguishes lines by stroke pattern, not only colour', () => {
    const { container } = render(<PmcChart pmc={PMC} />);
    const dash = (key: string) =>
      container.querySelector(`svg.chart path.${key}`)?.getAttribute('stroke-dasharray') ?? null;
    expect(dash('ctl')).toBeNull();
    expect(dash('atl')).toBeTruthy();
    expect(dash('tsb')).toBeTruthy();
    expect(dash('atl')).not.toBe(dash('tsb'));
  });

  it('has a visually hidden data table as a text alternative', () => {
    render(<PmcChart pmc={PMC} />);
    const table = screen.getByRole('table', { name: /Fitness, fatigue and form by week/ });
    expect(table).toHaveClass('sr-only');
    expect(within(table).getByRole('rowheader', { name: '2026-07-09' })).toBeInTheDocument();
  });

  it('renders a fallback message when there is not enough data', () => {
    render(<PmcChart pmc={[]} />);
    expect(screen.getByText(/Not enough data/)).toBeInTheDocument();
  });
});
