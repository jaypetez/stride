import { expect, test } from '@playwright/test';

test('renders the dashboard shell', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { name: /Stride/ })).toBeVisible();
  await expect(page.getByText('Powered by Strava')).toBeVisible();
  // Demo mode is the default; the "Next workout" card should have a heading.
  await expect(page.getByRole('heading', { name: 'Next workout' })).toBeVisible();
});
