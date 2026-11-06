import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { DetailedError } from 'hono/client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';

const { NEXT_TITLE, DISCLAIMER, STOP_FLAG, WARN_FLAG, fixtures, mockApi } = vi.hoisted(() => {
  const NEXT_TITLE = 'Easy aerobic run';
  const DISCLAIMER = 'Stride is for informational purposes only and is not medical advice.';
  const STOP_FLAG =
    'Symptoms you described (e.g. chest pain, dizziness, fainting) can be serious. ' +
    'Stop exercising and seek advice from a medical professional before continuing.';
  const WARN_FLAG =
    'You mentioned pain, injury, or illness — prioritize recovery and do not push through it.';
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
  const analyze = (flags: string[] = []) => ({
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
      flags,
      disclaimer: DISCLAIMER,
    },
    disclaimer: DISCLAIMER,
    flags,
  });
  const next = (flags: string[] = []) => ({
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
    disclaimer: DISCLAIMER,
    flags,
  });
  const plan = (overrides: Record<string, unknown> = {}) => ({
    plan: {
      id: 'p1',
      createdAt: '2026-07-09T00:00:00Z',
      goal: { distance: '10k' },
      startDate: '2026-07-13',
      summary: 'Test plan',
      weeks: [],
    },
    validation: { valid: true, violations: [], repaired: false },
    disclaimer: DISCLAIMER,
    flags: [],
    ...overrides,
  });
  const fixtures = { pmc, activity, analyze, next, plan };
  const mockApi = {
    health: vi.fn(),
    profile: vi.fn(),
    pmc: vi.fn(),
    activities: vi.fn(),
    analyze: vi.fn(),
    next: vi.fn(),
    plan: vi.fn(),
  };
  return { NEXT_TITLE, DISCLAIMER, STOP_FLAG, WARN_FLAG, fixtures, mockApi };
});

// Keep the real `errorMessage`/`errorStatus` helpers; only the network calls are mocked.
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  api: mockApi,
}));

/** The error `parseResponse` throws for a non-2xx API answer `{ error, requestId }`. */
function apiError(status: number, error: string) {
  return new DetailedError(`${status} Error`, {
    statusCode: status,
    detail: { data: { error, requestId: 'req-1' }, statusText: 'Error' },
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.health.mockResolvedValue({ status: 'ok', version: 'test' });
  mockApi.profile.mockResolvedValue({});
  mockApi.pmc.mockResolvedValue(fixtures.pmc);
  mockApi.activities.mockResolvedValue([fixtures.activity]);
  mockApi.analyze.mockResolvedValue(fixtures.analyze());
  mockApi.next.mockResolvedValue(fixtures.next());
  mockApi.plan.mockResolvedValue(fixtures.plan());
});

function renderApp() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  );
}

function card(name: string): HTMLElement {
  const heading = screen.getByRole('heading', { level: 2, name });
  const section = heading.closest('section');
  if (!section) throw new Error(`no card for ${name}`);
  return section;
}

describe('App', () => {
  it('renders the Stride header and Strava attribution', () => {
    renderApp();
    expect(screen.getByRole('heading', { level: 1, name: /Stride/ })).toBeInTheDocument();
    expect(screen.getByText(/Powered by Strava/)).toBeInTheDocument();
  });

  it('shows the next workout and PMC fitness stat once queries resolve', async () => {
    renderApp();
    expect(await screen.findByText(NEXT_TITLE)).toBeInTheDocument();
    await waitFor(() => {
      expect(within(card('Current form')).getByText(/Fitness \(CTL\)/)).toBeInTheDocument();
    });
  });

  it('announces loading with role="status" and aria-busy', () => {
    mockApi.next.mockReturnValue(new Promise(() => {}));
    renderApp();
    const next = card('Next workout');
    expect(next).toHaveAttribute('aria-busy', 'true');
    expect(within(next).getByRole('status')).toHaveTextContent('Loading…');
  });
});

describe('mode toggle', () => {
  it('exposes the selected mode with aria-pressed', () => {
    renderApp();
    const demo = screen.getByRole('button', { name: 'Demo' });
    const live = screen.getByRole('button', { name: 'My data' });
    expect(demo).toHaveAttribute('aria-pressed', 'true');
    expect(live).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();

    fireEvent.click(live);
    expect(demo).toHaveAttribute('aria-pressed', 'false');
    expect(live).toHaveAttribute('aria-pressed', 'true');
  });

  it('switches every query to live data', async () => {
    renderApp();
    await screen.findByText(NEXT_TITLE);
    fireEvent.click(screen.getByRole('button', { name: 'My data' }));

    expect(await screen.findByText(/Showing your synced data/)).toBeInTheDocument();
    await waitFor(() => {
      expect(mockApi.pmc).toHaveBeenCalledWith(false);
      expect(mockApi.activities).toHaveBeenCalledWith(false);
      expect(mockApi.next).toHaveBeenCalledWith(false, '');
      expect(mockApi.analyze).toHaveBeenCalledWith('last', '');
    });
  });
});

describe('empty states', () => {
  it('shows empty messages when live mode has no data', async () => {
    mockApi.pmc.mockResolvedValue({ ...fixtures.pmc, pmc: [], latest: null, latestAcwr: null });
    mockApi.activities.mockResolvedValue([]);
    mockApi.analyze.mockRejectedValue(apiError(404, 'Activity "last" not found'));
    renderApp();
    fireEvent.click(screen.getByRole('button', { name: 'My data' }));

    expect(await screen.findByText('No training data yet.')).toBeInTheDocument();
    expect(await screen.findByText('No activities.')).toBeInTheDocument();
    expect(await screen.findByText('No activity to analyze yet.')).toBeInTheDocument();
    expect(await screen.findByText(/Not enough data yet/)).toBeInTheDocument();
  });
});

describe('error states', () => {
  it("shows the API's own error message", async () => {
    mockApi.next.mockRejectedValue(apiError(429, 'Strava rate limit hit; retry in 15 minutes'));
    mockApi.analyze.mockRejectedValue(apiError(500, 'Store is corrupt'));
    renderApp();

    expect(
      await within(card('Next workout')).findByText('Strava rate limit hit; retry in 15 minutes'),
    ).toBeInTheDocument();
    // A non-404 analyze failure is a real error, not the empty state.
    expect(
      await within(card('Latest analysis')).findByText('Store is corrupt'),
    ).toBeInTheDocument();
    expect(screen.queryByText('No activity to analyze yet.')).not.toBeInTheDocument();
  });

  it('falls back to "API unavailable." when the server never answered', async () => {
    mockApi.pmc.mockRejectedValue(new TypeError('Failed to fetch'));
    renderApp();
    expect(await within(card('Current form')).findByText('API unavailable.')).toBeInTheDocument();
  });

  it('shows the API error for a failed plan instead of a generic hint', async () => {
    mockApi.plan.mockRejectedValue(apiError(400, 'Invalid request — weeks: Too big'));
    renderApp();
    fireEvent.click(screen.getByRole('button', { name: 'Generate plan' }));
    expect(await screen.findByText('Invalid request — weeks: Too big')).toBeInTheDocument();
    expect(screen.queryByText(/is the API running/)).not.toBeInTheDocument();
  });
});

describe('plan form', () => {
  it('rejects an empty weeks input without calling the API', () => {
    renderApp();
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Weeks' }), {
      target: { value: '' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Generate plan' }));

    expect(screen.getByRole('alert')).toHaveTextContent('Enter the number of weeks');
    expect(screen.getByRole('spinbutton', { name: 'Weeks' })).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    expect(mockApi.plan).not.toHaveBeenCalled();
  });

  it('submits the parsed weeks, race and demo flag', async () => {
    renderApp();
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Weeks' }), {
      target: { value: '12' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Generate plan' }));
    expect(await screen.findByText('Test plan')).toBeInTheDocument();
    expect(mockApi.plan).toHaveBeenCalledWith({
      demo: true,
      race: '10k',
      weeks: 12,
      note: undefined,
    });
    expect(screen.getByText(/Passes all guardrails/)).toBeInTheDocument();
    expect(within(card('Training plan')).getByText(DISCLAIMER)).toBeInTheDocument();
  });

  it('lists guardrail violations', async () => {
    mockApi.plan.mockResolvedValue(
      fixtures.plan({
        validation: {
          valid: false,
          repaired: false,
          violations: [
            {
              rule: 'ramp',
              message: 'Weekly load ramps 14% (cap 10%).',
              severity: 'error',
              weekNumber: 3,
            },
            { rule: 'rest', message: 'No rest day this week.', severity: 'warning' },
          ],
        },
      }),
    );
    renderApp();
    fireEvent.click(screen.getByRole('button', { name: 'Generate plan' }));

    expect(await screen.findByText('Guardrail issues: 2')).toBeInTheDocument();
    expect(screen.getByText('Week 3: Weekly load ramps 14% (cap 10%).')).toBeInTheDocument();
    expect(screen.getByText('No rest day this week.')).toBeInTheDocument();
  });
});

describe('safety flags and disclaimer', () => {
  it('shows the disclaimer wherever coach output is shown', async () => {
    renderApp();
    expect(await within(card('Next workout')).findByText(DISCLAIMER)).toBeInTheDocument();
    expect(await within(card('Latest analysis')).findByText(DISCLAIMER)).toBeInTheDocument();
  });

  it('renders STOP and WARN flags above Next and Analyze results', async () => {
    mockApi.next.mockResolvedValue(fixtures.next([STOP_FLAG]));
    mockApi.analyze.mockResolvedValue(fixtures.analyze([WARN_FLAG]));
    renderApp();

    const nextAlert = await within(card('Next workout')).findByRole('alert');
    expect(nextAlert).toHaveTextContent(/STOP/);
    expect(nextAlert).toHaveClass('flag-stop');
    // The banner comes BEFORE the workout title in document order.
    const title = within(card('Next workout')).getByText(NEXT_TITLE);
    expect(
      nextAlert.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    const analyzeAlert = await within(card('Latest analysis')).findByRole('alert');
    expect(analyzeAlert).toHaveTextContent(/Warning/);
    expect(analyzeAlert).toHaveClass('flag-warn');
  });

  it('sends the check-in note to every coach call and flags a STOP plan', async () => {
    mockApi.plan.mockResolvedValue(fixtures.plan({ flags: [STOP_FLAG] }));
    renderApp();
    fireEvent.change(screen.getByRole('textbox', { name: /How do you feel today/ }), {
      target: { value: '  chest pain on my last run ' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Check in' }));

    await waitFor(() => {
      expect(mockApi.next).toHaveBeenCalledWith(true, 'chest pain on my last run');
      expect(mockApi.analyze).toHaveBeenCalledWith('demo', 'chest pain on my last run');
    });

    fireEvent.click(screen.getByRole('button', { name: 'Generate plan' }));
    const alert = await within(card('Training plan')).findByRole('alert');
    expect(alert).toHaveTextContent(/STOP/);
    expect(mockApi.plan).toHaveBeenCalledWith(
      expect.objectContaining({ note: 'chest pain on my last run' }),
    );
  });
});
