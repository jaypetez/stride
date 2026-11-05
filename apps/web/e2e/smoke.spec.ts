import { expect, type Page, test } from '@playwright/test';

/** The `<section>` card that owns the given h2 heading. */
const card = (page: Page, name: string) =>
  page.locator('section.card').filter({ has: page.getByRole('heading', { level: 2, name }) });

test('renders the dashboard shell', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Stride/ })).toBeVisible();
  // Demo mode is the default; the "Next workout" card should have a heading.
  await expect(page.getByRole('heading', { name: 'Next workout' })).toBeVisible();
});

test('renders real demo data from the API', async ({ page }) => {
  await page.goto('/');

  // Current form: PMC stats come from GET /pmc?demo=true.
  await expect(card(page, 'Current form').getByText('Fitness (CTL)')).toBeVisible();

  // Next workout: a TSS chip, the safety disclaimer, and no "API unavailable".
  const next = card(page, 'Next workout');
  await expect(next.locator('.chip', { hasText: /\d+ TSS/ })).toBeVisible();
  await expect(next.locator('.disclaimer')).toBeVisible();

  // Latest analysis of the demo activity, also with its TSS chip + disclaimer.
  const analysis = card(page, 'Latest analysis');
  await expect(analysis.locator('.chip', { hasText: /\d+ TSS/ })).toBeVisible();
  await expect(analysis.locator('.disclaimer')).toBeVisible();

  // Fitness trend: the SVG chart with its three lines.
  const chart = page.getByRole('img', { name: /Performance management chart/ });
  await expect(chart).toBeVisible();
  await expect(chart.locator('path.line')).toHaveCount(3);

  // Recent activities table has demo rows.
  await expect(card(page, 'Recent activities').locator('tbody tr').first()).toBeVisible();

  await expect(page.getByText('API unavailable.')).toHaveCount(0);
});

test('generates a demo plan with a week table', async ({ page }) => {
  await page.goto('/');
  const plan = card(page, 'Training plan');
  await plan.getByRole('spinbutton', { name: 'Weeks' }).fill('6');
  await plan.getByRole('button', { name: 'Generate plan' }).click();

  await expect(plan.locator('table tbody tr')).toHaveCount(6);
  await expect(plan.getByText(/Passes all guardrails|Guardrail issues/)).toBeVisible();
  await expect(plan.locator('.disclaimer')).toBeVisible();
});

test('rejects an empty weeks field without calling the API', async ({ page }) => {
  await page.goto('/');
  const plan = card(page, 'Training plan');
  await plan.getByRole('spinbutton', { name: 'Weeks' }).fill('');
  await plan.getByRole('button', { name: 'Generate plan' }).click();
  await expect(plan.getByRole('alert')).toContainText('Enter the number of weeks');
  await expect(plan.locator('table')).toHaveCount(0);
});

test('the data-mode toggle exposes aria-pressed', async ({ page }) => {
  await page.goto('/');
  const demo = page.getByRole('button', { name: 'Demo' });
  const live = page.getByRole('button', { name: 'My data' });
  await expect(demo).toHaveAttribute('aria-pressed', 'true');
  await live.click();
  await expect(live).toHaveAttribute('aria-pressed', 'true');
  await expect(demo).toHaveAttribute('aria-pressed', 'false');
  // The e2e API runs on an empty store: live mode shows real empty states.
  await expect(card(page, 'Recent activities').getByText('No activities.')).toBeVisible();
  await expect(
    card(page, 'Latest analysis').getByText('No activity to analyze yet.'),
  ).toBeVisible();
});

test('shows the required Strava attribution', async ({ page }) => {
  await page.goto('/');
  // The compliant "Powered by Strava" badge (GOAL §4) with the exact text and a
  // "View on Strava" affordance are the required attributions in any UI.
  const attribution = page.getByRole('link', { name: 'Powered by Strava' });
  await expect(attribution).toBeVisible();
  await expect(attribution).toHaveText('Powered by Strava');
  // Anchored so it can only match the strava.com host itself, never a lookalike
  // like strava.com.evil.example (js/regex/missing-regexp-anchor).
  await expect(attribution).toHaveAttribute('href', /^https:\/\/(www\.)?strava\.com\/?$/);
});
