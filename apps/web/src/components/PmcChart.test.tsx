import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PmcChart } from './PmcChart';

describe('PmcChart', () => {
  it('renders an svg chart when given at least two points', () => {
    render(
      <PmcChart
        pmc={[
          { date: '2026-07-07', ctl: 50, atl: 45, tsb: 8 },
          { date: '2026-07-08', ctl: 55, atl: 48, tsb: 7 },
          { date: '2026-07-09', ctl: 56, atl: 50, tsb: 5 },
        ]}
      />,
    );
    expect(screen.getByRole('img', { name: /Performance management chart/ })).toBeInTheDocument();
  });

  it('renders a fallback message when there is not enough data', () => {
    render(<PmcChart pmc={[]} />);
    expect(screen.getByText(/Not enough data/)).toBeInTheDocument();
  });
});
