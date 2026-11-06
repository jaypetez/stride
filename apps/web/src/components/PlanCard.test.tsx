import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NO_SEED_TEXT, PlanCard } from './PlanCard';

const { mockApi, planResult } = vi.hoisted(() => {
  const planResult = (overrides: Record<string, unknown> = {}) => ({
    plan: {
      id: 'b1',
      createdAt: '2026-07-09T00:00:00Z',
      kind: 'build',
      goal: { distance: '10k' },
      startDate: '2026-07-13',
      summary: 'A 8-week fitness build: base → build.',
      weeks: [],
    },
    validation: { valid: true, violations: [], repaired: false },
    disclaimer: 'Not medical advice.',
    flags: [],
    seed: { weeklyMinutes: 192, longestRunMin: 74.6, vdot: 45, runCount: 12 },
    ...overrides,
  });
  return { mockApi: { plan: vi.fn() }, planResult };
});

vi.mock('../api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api')>()),
  api: mockApi,
}));

function renderCard() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PlanCard demo note="" />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockApi.plan.mockResolvedValue(planResult());
});

describe('PlanCard Race | Build fitness toggle (MVP-026)', () => {
  it('starts on Race, with the toggle state exposed via aria-pressed', () => {
    renderCard();
    expect(screen.getByRole('group', { name: 'Plan type' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Race' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: 'Build fitness' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(screen.getByRole('combobox', { name: 'Race' })).toBeInTheDocument();
  });

  it('build mode hides the race picker and sends kind build with the weeks', async () => {
    renderCard();
    fireEvent.click(screen.getByRole('button', { name: 'Build fitness' }));
    expect(screen.getByRole('button', { name: 'Build fitness' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.queryByRole('combobox', { name: 'Race' })).not.toBeInTheDocument();
    const weeks = screen.getByRole('spinbutton', { name: 'Weeks' });
    expect(weeks).toHaveAttribute('min', '4');
    expect(weeks).toHaveAttribute('max', '16');
    fireEvent.change(weeks, { target: { value: '10' } });
    fireEvent.click(screen.getByRole('button', { name: 'Generate build plan' }));

    expect(
      await screen.findByText('Based on your last 4 weeks: 3.2 h/week, longest run 75 min.'),
    ).toBeInTheDocument();
    expect(mockApi.plan).toHaveBeenCalledWith({
      demo: true,
      kind: 'build',
      weeks: 10,
      note: undefined,
    });
  });

  it('shows the gentle-default line when the plan had no seed', async () => {
    mockApi.plan.mockResolvedValue(planResult({ seed: undefined }));
    renderCard();
    fireEvent.click(screen.getByRole('button', { name: 'Build fitness' }));
    fireEvent.click(screen.getByRole('button', { name: 'Generate build plan' }));
    expect(await screen.findByText(NO_SEED_TEXT)).toBeInTheDocument();
  });

  it('rejects build weeks outside 4–16 without calling the API', () => {
    renderCard();
    fireEvent.click(screen.getByRole('button', { name: 'Build fitness' }));
    fireEvent.change(screen.getByRole('spinbutton', { name: 'Weeks' }), {
      target: { value: '20' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Generate build plan' }));
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Weeks must be a whole number from 4 to 16.',
    );
    expect(mockApi.plan).not.toHaveBeenCalled();
  });

  it('race plans show no seed line', async () => {
    mockApi.plan.mockResolvedValue(
      planResult({
        plan: {
          id: 'r1',
          createdAt: '2026-07-09T00:00:00Z',
          goal: { distance: '10k' },
          startDate: '2026-07-13',
          summary: 'Race plan',
          weeks: [],
        },
      }),
    );
    renderCard();
    fireEvent.click(screen.getByRole('button', { name: 'Generate plan' }));
    expect(await screen.findByText('Race plan')).toBeInTheDocument();
    expect(screen.queryByText(/Based on your last 4 weeks/)).not.toBeInTheDocument();
    expect(mockApi.plan).toHaveBeenCalledWith({
      demo: true,
      race: '10k',
      weeks: 8,
      note: undefined,
    });
  });
});
