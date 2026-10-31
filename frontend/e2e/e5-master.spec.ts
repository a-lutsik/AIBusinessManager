import { expect, test, type Page } from '@playwright/test';
import { createBooking, uniqueName } from './support/bookings';
import { ANNA_ID, NINO_ID } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

const MONEY = /GEL\s*\d|\d\s*GEL|₾/;

test.beforeEach(async ({ page }) => {
  await useRole(page, 'MASTER');
});

async function expectNoPhoneNoMoney(page: Page): Promise<void> {
  const text = page.locator('body');
  await expect(text).not.toContainText('+995');
  await expect(text).not.toContainText(MONEY);
}

test('MASTER sees the Today view: three menu items, no metrics or tasks', async ({ page }) => {
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');
  await expect(page.getByText('Today', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('Dashboard', { exact: true })).toHaveCount(0);

  // The rail shows icons only: expand it to read the labels.
  await page.getByText('monitor_heart').click();
  await expect(page.getByRole('link')).toHaveCount(3);
  await expect(page.getByRole('link')).toContainText(['Today', 'Calendar', 'Clients']);

  await expect(page.getByTestId('metric-card')).toHaveCount(0);
  for (const heading of ['Growth', 'Owner tasks', 'Growth signals']) {
    await expect(page.getByText(heading, { exact: true })).toHaveCount(0);
  }
  await expectNoPhoneNoMoney(page);
  await screenshotWidths(page, 'dashboard-master');
});

test('MASTER sees a visit without the phone and a client without phone or counters', async ({ page, request }) => {
  const name = uniqueName('Master Viewer');
  const visit = await createBooking(request, { name });

  await page.goto('/calendar');
  // The panel first shows the row of the list; the check below must see it filled from the visit detail.
  const detail = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/app/appointments/${visit.id}`);
  await page.getByTestId(`visit-row-${visit.id}`).click();
  expect((await detail).status()).toBe(200);
  const panel = page.getByTestId('visit-panel');
  await expect(panel).toContainText(name);
  await expect(panel.getByText('New', { exact: true })).toBeVisible();
  await expectNoPhoneNoMoney(page);

  await page.goto('/clients');
  await page.getByPlaceholder('Search').last().fill(name);
  const client = page.getByText(name, { exact: true });
  await expect(client).toBeVisible();
  await expect(page.locator('body')).not.toContainText(/\d+ visits?/);
  await expectNoPhoneNoMoney(page);
  await screenshotWidths(page, 'clients-master');

  await client.click();
  await expect(page.getByText('No completed visits yet')).toBeVisible();
  await expect(page.locator('body')).not.toContainText('NEW_CLIENT');
  await expect(page.locator('body')).not.toContainText(/\d+ visits?/);
  await expectNoPhoneNoMoney(page);
  await screenshotWidths(page, 'client-card-master');
});

/** Every money property of an API answer (name as in MasterViewPolicy.MONEY_WORDS) with a value other than null or 0. */
function moneyLeft(value: unknown, path = '$'): string[] {
  if (Array.isArray(value)) return value.flatMap((item, index) => moneyLeft(item, `${path}[${index}]`));
  if (value === null || typeof value !== 'object') return [];
  return Object.entries(value).flatMap(([key, item]) =>
    /price|amount|value|revenue|discount|minor/i.test(key) && item !== null && item !== 0 ? [`${path}.${key}`] : moneyLeft(item, `${path}.${key}`)
  );
}

for (const { path, api } of [
  { path: '/services', api: '/api/app/services' },
  { path: `/specialists/${NINO_ID}`, api: `/api/app/specialists/${NINO_ID}/matrix` },
]) {
  test(`MASTER opening ${path} directly gets the data without money (MasterViewPolicy)`, async ({ page }) => {
    const response = page.waitForResponse((r) => new URL(r.url()).pathname === api);
    await page.goto(path);
    const answer = await response;
    expect(answer.status()).toBe(200);
    expect(moneyLeft(await answer.json())).toEqual([]);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('body')).not.toContainText(MONEY);
  });
}

for (const { path, api } of [
  { path: '/rules', api: '/api/app/rules' },
  { path: `/specialists/${ANNA_ID}`, api: `/api/app/specialists/${ANNA_ID}/matrix` },
]) {
  test(`MASTER opening ${path} directly is refused by the API and sees no prices`, async ({ page }) => {
    const response = page.waitForResponse((r) => new URL(r.url()).pathname === api);
    await page.goto(path);
    expect((await response).status()).toBe(403);
    await page.waitForLoadState('networkidle');
    await expect(page.locator('body')).not.toContainText(MONEY);
  });
}

test('MASTER on /rules gets a refusal instead of the editable rules form', async ({ page }) => {
  await page.goto('/rules');
  await expect(page.getByText('Only the owner can view and change booking rules.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0);
  await expect(page.getByRole('switch')).toHaveCount(0);
});
