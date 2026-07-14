import { expect, test } from '@playwright/test';

test('renders the dashboard shell', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Stride/ })).toBeVisible();
  // Demo mode is the default; the "Next workout" card should have a heading.
  await expect(page.getByRole('heading', { name: 'Next workout' })).toBeVisible();
});

test('shows the required Strava attribution', async ({ page }) => {
  await page.goto('/');
  // The compliant "Powered by Strava" badge (GOAL §4) with the exact text and a
  // "View on Strava" affordance are the required attributions in any UI.
  const attribution = page.getByRole('link', { name: 'Powered by Strava' });
  await expect(attribution).toBeVisible();
  await expect(attribution).toHaveText('Powered by Strava');
  await expect(attribution).toHaveAttribute('href', /strava\.com/);
});
