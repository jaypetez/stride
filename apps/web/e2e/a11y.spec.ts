import AxeBuilder from '@axe-core/playwright';
import { expect, type Page, test } from '@playwright/test';

/**
 * axe-core scans of every dashboard view (AUD-019 / AUD-021): no serious or
 * critical violations allowed.
 *
 * The "Powered by Strava" badge is excluded: its orange-on-orange styling is the
 * Strava brand treatment and changing it was explicitly declined (#438). Every
 * other element, including the "View on Strava" links, is scanned.
 */
const card = (page: Page, name: string) =>
  page.locator('section.card').filter({ has: page.getByRole('heading', { level: 2, name }) });

async function expectNoSeriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'best-practice'])
    .exclude('.powered-by-strava')
    .analyze();
  const serious = results.violations.filter(
    (v) => v.impact === 'serious' || v.impact === 'critical',
  );
  expect(
    serious.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target) })),
  ).toEqual([]);
}

for (const scheme of ['light', 'dark'] as const) {
  test.describe(`${scheme} colour scheme`, () => {
    test.use({ colorScheme: scheme });

    test('demo dashboard', async ({ page }) => {
      await page.goto('/');
      await expect(page.getByRole('img', { name: /Performance management chart/ })).toBeVisible();
      await expect(card(page, 'Next workout').locator('.disclaimer')).toBeVisible();
      await expect(card(page, 'Recent activities').locator('tbody tr').first()).toBeVisible();
      await expectNoSeriousViolations(page);
    });

    test('generated plan with guardrail status', async ({ page }) => {
      await page.goto('/');
      const plan = card(page, 'Training plan');
      await plan.getByRole('button', { name: 'Generate plan' }).click();
      await expect(plan.locator('table tbody tr').first()).toBeVisible();
      await expectNoSeriousViolations(page);
    });

    test('safety flags (STOP and WARN)', async ({ page }) => {
      await page.goto('/');
      await page.getByRole('textbox', { name: /How do you feel today/ }).fill('chest pain');
      await page.getByRole('button', { name: 'Check in' }).click();
      await expect(card(page, 'Next workout').getByRole('alert')).toBeVisible();
      const plan = card(page, 'Training plan');
      await plan.getByRole('button', { name: 'Generate plan' }).click();
      await expect(plan.getByRole('alert')).toBeVisible();
      await expectNoSeriousViolations(page);

      await page.getByRole('textbox', { name: /How do you feel today/ }).fill('a bit sore');
      await page.getByRole('button', { name: 'Check in' }).click();
      await expect(card(page, 'Next workout').locator('.flag-warn')).toBeVisible();
      await expectNoSeriousViolations(page);
    });

    test('live mode (empty store) and form error', async ({ page }) => {
      await page.goto('/');
      await page.getByRole('button', { name: 'My data' }).click();
      await expect(card(page, 'Recent activities').getByText('No activities.')).toBeVisible();
      const plan = card(page, 'Training plan');
      await plan.getByRole('spinbutton', { name: 'Weeks' }).fill('');
      await plan.getByRole('button', { name: 'Generate plan' }).click();
      await expect(plan.getByRole('alert')).toBeVisible();
      await expectNoSeriousViolations(page);
    });
  });
}
