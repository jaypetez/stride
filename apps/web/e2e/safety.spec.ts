import { expect, type Page, test } from '@playwright/test';

/**
 * Red-flag notes are screened by core's deterministic safety rules on every coach
 * call; the dashboard must surface the resulting STOP / WARN flags above the
 * results (AUD-018), exactly as the CLI does with `--note`.
 */
const card = (page: Page, name: string) =>
  page.locator('section.card').filter({ has: page.getByRole('heading', { level: 2, name }) });

async function checkIn(page: Page, note: string) {
  await page.getByRole('textbox', { name: /How do you feel today/ }).fill(note);
  await page.getByRole('button', { name: 'Check in' }).click();
}

test('a STOP note shows a STOP alert above Next, Analyze and Plan results', async ({ page }) => {
  await page.goto('/');
  await checkIn(page, 'I had chest pain on my last run');

  for (const name of ['Next workout', 'Latest analysis']) {
    const alert = card(page, name).getByRole('alert');
    await expect(alert).toBeVisible();
    await expect(alert).toHaveClass(/flag-stop/);
    await expect(alert).toContainText('STOP');
    await expect(alert).toContainText('seek advice from a medical professional');
  }

  const plan = card(page, 'Training plan');
  await plan.getByRole('button', { name: 'Generate plan' }).click();
  const planAlert = plan.getByRole('alert');
  await expect(planAlert).toBeVisible();
  await expect(planAlert).toHaveClass(/flag-stop/);
  // The banner sits ABOVE the plan table.
  const alertBox = await planAlert.boundingBox();
  const tableBox = await plan.locator('table').boundingBox();
  expect(alertBox && tableBox && alertBox.y < tableBox.y).toBeTruthy();
  await expect(plan.locator('.disclaimer')).toBeVisible();
});

test('a WARN note shows a warning alert', async ({ page }) => {
  await page.goto('/');
  await checkIn(page, 'my calf is a bit sore');
  const alert = card(page, 'Next workout').getByRole('alert');
  await expect(alert).toBeVisible();
  await expect(alert).toHaveClass(/flag-warn/);
  await expect(alert).toContainText('Warning');
});
