import { expect, test, type APIRequestContext } from '@playwright/test';
import type { DashboardView } from '../src/api/client';
import { taskOfVisit } from './support/app';
import { createBooking } from './support/bookings';
import { API_URL, BASE_URL, TENANT_ID } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

async function metricsOf(request: APIRequestContext): Promise<NonNullable<DashboardView['metrics']>> {
  const response = await request.get(`${API_URL}/api/app/dashboard`, { headers: { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'OWNER' } });
  return ((await response.json()) as DashboardView).metrics ?? [];
}

test.beforeEach(async ({ page }) => {
  await useRole(page, 'OWNER');
});

test('six metric cards: no NaN, money in GEL, "not enough data" where the sample is small, date window', async ({ page, request }) => {
  const metrics = await metricsOf(request);
  expect(metrics).toHaveLength(6);

  await page.goto('/dashboard');
  const cards = page.getByTestId('metric-card');
  await expect(cards).toHaveCount(6);
  await screenshotWidths(page, 'dashboard-owner');

  for (const [index, metric] of metrics.entries()) {
    const card = cards.nth(index);
    await expect(card).not.toContainText(/NaN|undefined|null/);
    if (metric.insufficientData) await expect(card).toContainText('Not enough data in this window');
    else await expect(card).not.toContainText('Not enough data in this window');
  }
  await expect(cards.filter({ hasText: /GEL\s*\d[\d,.]*\/h/ })).toHaveCount(1);
  await expect(cards.filter({ hasText: /\d{4}-\d{2}-\d{2} → \d{4}-\d{2}-\d{2}/ }).first()).toBeVisible();
  // The explanation is for the owner: an amount in the currency, rounded hours, no technical units.
  const revenue = cards.filter({ hasText: /GEL\s*\d[\d,.]*\/h/ });
  await expect(revenue).toContainText(/Completed value \d+\.\d{2} GEL over \d+\.\d worked hours/);
  await expect(revenue).not.toContainText('minor units');
});

// The demo seed has enough data for all six metrics, so the small-sample case is set up on the wire. The API side of
// the rule (the thresholds) is covered by MetricsCalculatorTest.
test('a metric with too small a sample shows "not enough data" instead of a number', async ({ page }) => {
  await page.route('**/api/app/dashboard', async (route) => {
    const response = await route.fetch();
    const dashboard = (await response.json()) as DashboardView;
    const metrics = (dashboard.metrics ?? []).map((metric, index) => (index === 0 ? { ...metric, value: null, insufficientData: true } : metric));
    await route.fulfill({ response, json: { ...dashboard, metrics } });
  });
  await page.goto('/dashboard');
  const cards = page.getByTestId('metric-card');
  await expect(cards).toHaveCount(6);
  await expect(cards.first()).toContainText('Not enough data in this window');
  await expect(cards.nth(1)).not.toContainText('Not enough data in this window');
});

test('copying a task message issues a manage link; Done removes the task; signals or the empty state', async ({ page, context, request }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE_URL });
  const visit = await createBooking(request, { name: 'Dashboard Copier' });
  const task = await taskOfVisit(request, visit.id);

  await page.goto('/dashboard');
  const card = page.getByTestId(`owner-task-${task.id}`);
  await expect(card).toBeVisible();
  // The message is shown without its placeholder; the real link appears only in the copied text.
  await expect(card).not.toContainText(/[{}]/);
  await expect(card).toContainText('the link is added when you copy the message');

  const accessLink = page.waitForResponse((r) => r.url().includes(`/appointments/${visit.id}/access-link`) && r.request().method() === 'POST');
  await card.getByRole('button', { name: 'Copy message' }).click();
  expect((await accessLink).ok()).toBe(true);
  await expect(card.getByRole('button', { name: 'Copied' })).toBeVisible();
  const clipboard = await page.evaluate(() => navigator.clipboard.readText());
  expect(clipboard).toContain(`/b/${visit.id}?token=`);
  expect(clipboard).not.toContain('{');

  const completed = page.waitForResponse((r) => r.url().includes(`/tasks/${task.id}/complete`) && r.request().method() === 'POST');
  await card.getByRole('button', { name: 'Done' }).click();
  expect((await completed).ok()).toBe(true);
  await expect(card).toHaveCount(0);

  await expect(page.getByText('Growth signals', { exact: true })).toBeVisible();
  await expect(page.getByText('No growth signals right now.').or(page.getByRole('button', { name: 'Dismiss' }).first())).toBeVisible();
});

test('dashboard screenshots: dark theme', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('cadence.theme', 'dark'));
  await page.goto('/dashboard');
  await expect(page.getByTestId('metric-card')).toHaveCount(6);
  await expect.poll(() => page.evaluate(() => document.documentElement.getAttribute('data-theme'))).toBe('dark');
  await screenshotWidths(page, 'dashboard-owner-dark');
});

test('dashboard screenshots: Russian', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('cadence.locale', 'ru'));
  await page.goto('/dashboard');
  await expect(page.getByTestId('metric-card')).toHaveCount(6);
  const heading = page.getByText('Дашборд', { exact: true }).first();
  await expect(heading).toBeVisible();
  for (const title of ['Заполненность слотов', 'Доля повторных клиентов', 'Интервал возврата', 'Доля неявок', 'Выручка в час', 'Риск оттока']) {
    await expect(page.getByTestId('metric-card').filter({ hasText: title }), title).toHaveCount(1);
  }
  await expect(page.getByText('Новая запись — отправьте подтверждение вручную').first()).toBeVisible();
  await screenshotWidths(page, 'dashboard-owner-ru');

  // Plus Jakarta Sans has no Cyrillic: the heading's own font list must reach Inter, not the browser's serif default.
  const family = await heading.evaluate((element) => getComputedStyle(element).fontFamily);
  expect(family).toContain('Plus Jakarta Sans');
  const widths = await page.evaluate(async (headingFamily) => {
    const font = (name: string): string => `700 28px ${name}`;
    await document.fonts.load(font('Inter'), 'Дашборд');
    const context = document.createElement('canvas').getContext('2d');
    const width = (name: string): number => {
      if (!context) return -1;
      context.font = font(name);
      return context.measureText('Дашборд').width;
    };
    return { heading: width(headingFamily), inter: width('Inter'), serif: width('serif') };
  }, family);
  expect(widths.heading, 'the heading renders Cyrillic with Inter').toBeCloseTo(widths.inter, 1);
  expect(Math.abs(widths.heading - widths.serif), 'and not with the serif default').toBeGreaterThan(1);
});
