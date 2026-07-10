import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { App } from './App';

const { NEXT_TITLE } = vi.hoisted(() => ({ NEXT_TITLE: 'Easy aerobic run' }));

vi.mock('./api', () => {
  const pmc = {
    pmc: [
      { date: '2026-07-06', ctl: 50, atl: 40, tsb: 12 },
      { date: '2026-07-07', ctl: 52, atl: 45, tsb: 8 },
      { date: '2026-07-08', ctl: 55, atl: 48, tsb: 7 },
    ],
    acwr: [{ date: '2026-07-08', acwr: 1.1, acuteLoad: 55, chronicLoad: 50, flag: 'ok' }],
    latest: { date: '2026-07-08', ctl: 55, atl: 48, tsb: 7 },
    latestAcwr: { date: '2026-07-08', acwr: 1.1, acuteLoad: 55, chronicLoad: 50, flag: 'ok' },
    rampRatePerWeek: 2.5,
  };
  const activity = {
    id: 'demo-1',
    source: 'strava',
    sportType: 'run',
    name: 'Morning Run',
    startDate: '2026-07-08T06:00:00Z',
    startDateLocal: '2026-07-08T08:00:00',
    distance: 10000,
    movingTime: 3000,
    elapsedTime: 3100,
    totalElevationGain: 50,
    averageHeartrate: 150,
    hasHeartrate: true,
    trainer: false,
    manual: false,
  };
  return {
    api: {
      health: vi.fn(async () => ({ status: 'ok', version: 'test' })),
      profile: vi.fn(async () => ({})),
      pmc: vi.fn(async () => pmc),
      activities: vi.fn(async () => [activity]),
      analyze: vi.fn(async () => ({
        metrics: {
          activityId: 'demo-1',
          tss: 65,
          method: 'rtss',
          durationSec: 3000,
          distanceM: 10000,
          averagePaceSecPerKm: 300,
          aerobicDecouplingPct: 4,
        },
        analysis: {
          activity,
          headline: '65 TSS steady run',
          explanation: 'A solid aerobic effort with controlled decoupling.',
          flags: [],
        },
      })),
      next: vi.fn(async () => ({
        context: { fitness: pmc.latest, acwr: pmc.latestAcwr },
        workout: {
          type: 'easy',
          title: NEXT_TITLE,
          description: 'Keep it conversational.',
          targetDurationSec: 2400,
          targetHrZone: 2,
          targetTss: 40,
          rationale: 'Form is fresh; bank easy aerobic volume.',
        },
      })),
      plan: vi.fn(async () => ({
        plan: {
          id: 'p1',
          createdAt: '2026-07-09T00:00:00Z',
          goal: { distance: '10k' },
          startDate: '2026-07-13',
          summary: 'Test plan',
          weeks: [],
        },
        validation: { valid: true, violations: [] },
      })),
    },
  };
});

function renderApp() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  );
}

describe('App', () => {
  it('renders the Stride header and Strava attribution', () => {
    renderApp();
    expect(screen.getByRole('heading', { level: 1, name: /Stride/ })).toBeInTheDocument();
    expect(screen.getByText(/Powered by Strava/)).toBeInTheDocument();
  });

  it('shows the next workout and PMC fitness stat once queries resolve', async () => {
    renderApp();
    // Next-workout title comes from the mocked api.next().
    expect(await screen.findByText(NEXT_TITLE)).toBeInTheDocument();
    // The PMC "Fitness" stat only appears after api.pmc() resolves.
    await waitFor(() => {
      expect(screen.getByText(/Fitness \(CTL\)/)).toBeInTheDocument();
    });
  });
});
