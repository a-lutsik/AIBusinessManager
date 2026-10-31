import { expect, test } from '@playwright/test';
import { NINO_ID } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

const ERROR_TEXT = /Owner-only|[Ff]orbidden|[Ff]ailed|[Uu]nauthori[sz]ed|Internal Server|\bNaN\b|undefined|\[object/;

test.beforeEach(async ({ page }) => {
  await useRole(page, 'OWNER');
});

test('every console route opens with its title and no error text', async ({ page }) => {
  for (const [path, title] of [
    ['/dashboard', 'Dashboard'],
    ['/calendar', 'Calendar'],
    ['/clients', 'Clients'],
    ['/services', 'Services'],
    ['/specialists', 'Specialists'],
    ['/rules', 'Booking rules'],
    ['/ai', 'AI manager'],
    [`/specialists/${NINO_ID}`, 'Service matrix'],
  ]) {
    await page.goto(path);
    await expect(page.getByText(title, { exact: true }).first(), path).toBeVisible();
    await page.waitForLoadState('networkidle');
    await expect(page.locator('body'), path).not.toContainText(ERROR_TEXT);
  }
});

test('services: eight cards with prices', async ({ page }) => {
  await page.goto('/services');
  const prices: Record<string, string> = {
    Haircut: '80.00',
    Blowout: '50.00',
    Color: '200.00',
    Highlights: '250.00',
    Manicure: '70.00',
    Pedicure: '90.00',
    'Brow Shaping': '35.00',
    Consultation: '0.00',
  };
  for (const [name, price] of Object.entries(prices)) {
    await expect(page.getByText(name, { exact: true }).first(), name).toBeVisible();
    await expect(page.getByText(new RegExp(`^GEL\\s*${price.replace('.', '\\.')}$`)), `${name} price`).toBeVisible();
  }
  await expect(page.getByText('Active', { exact: true })).toHaveCount(8);
  await screenshotWidths(page, 'services');
});

test('specialists: three masters, Nino matrix with the Color override', async ({ page }) => {
  await page.goto('/specialists');
  await expect(page.getByText('Open service matrix →')).toHaveCount(3);
  for (const master of ['Nino Beridze', 'Anna Novakova', 'Elena Kravets']) {
    await expect(page.getByText(master, { exact: true })).toBeVisible();
  }
  await screenshotWidths(page, 'specialists');

  await page.getByText('Nino Beridze', { exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/specialists/${NINO_ID}$`));
  await expect(page.getByText('Service matrix', { exact: true })).toBeVisible();

  await expect(page.getByTestId('matrix-row-Color')).toContainText(/120 min[\s\S]*135 min/);
  for (const offered of ['Haircut', 'Blowout', 'Color', 'Highlights', 'Consultation']) {
    await expect(page.getByTestId(`matrix-row-${offered}`), offered).toContainText('Yes');
  }
  for (const notOffered of ['Manicure', 'Pedicure', 'Brow Shaping']) {
    await expect(page.getByTestId(`matrix-row-${notOffered}`), notOffered).toContainText('No');
  }
  await screenshotWidths(page, 'matrix-nino');
});

test('the language and the dark theme survive a reload', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page.getByText('Dashboard', { exact: true }).first()).toBeVisible();

  await page.getByText('RU', { exact: true }).click();
  await expect(page.getByText('Дашборд', { exact: true }).first()).toBeVisible();
  await page.reload();
  await expect(page.getByText('Дашборд', { exact: true }).first()).toBeVisible();
  await page.getByText('EN', { exact: true }).click();
  await page.reload();
  await expect(page.getByText('Dashboard', { exact: true }).first()).toBeVisible();

  const theme = (): Promise<string | null> => page.evaluate(() => document.documentElement.getAttribute('data-theme'));
  await expect.poll(theme).toBe('light');
  await page.getByText('dark_mode').click();
  await expect.poll(theme).toBe('dark');
  await page.reload();
  await expect.poll(theme).toBe('dark');
});

test('an unknown URL shows not-found with a link to the dashboard', async ({ page }) => {
  await page.goto('/no-such-screen');
  await expect(page.getByText('This screen does not exist').first()).toBeVisible();
  await screenshotWidths(page, 'not-found');
  await page.getByText('Go to the dashboard').click();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText('Dashboard', { exact: true }).first()).toBeVisible();
});
