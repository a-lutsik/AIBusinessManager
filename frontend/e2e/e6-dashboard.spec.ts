import { expect, test, type APIRequestContext } from '@playwright/test';
import type { DashboardView } from '../src/api/client';
import { taskOfVisit } from './support/app';
import { createBooking, uniqueName, uniquePhone } from './support/bookings';
import { API_URL, BASE_URL, NINO_ID, TENANT_ID } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

async function dashboardOf(request: APIRequestContext): Promise<DashboardView> {
  const response = await request.get(`${API_URL}/api/app/dashboard`, { headers: { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'OWNER' } });
  return (await response.json()) as DashboardView;
}

const HAIRCUT_ID = '00000000-0000-4000-8000-000000000021';
const PER_HOUR = /GEL\s*\d[\d,.]*\/h/;

test.beforeEach(async ({ page }) => {
  await useRole(page, 'OWNER');
});

// What the month holds depends on the day the suite runs: on the 1st there is nothing yet. So the cards are held against the API's
// answer of the same moment, and the two states of a card are set up on the wire. The server's texts: MetricsCalculatorTest.
test('six metric cards say what the API says: no NaN, "not enough data" where the sample is small, date window, explanation', async ({ page, request }) => {
  const metrics = (await dashboardOf(request)).metrics ?? [];
  expect(metrics).toHaveLength(6);

  await page.goto('/dashboard');
  const cards = page.getByTestId('metric-card');
  await expect(cards).toHaveCount(6);
  await screenshotWidths(page, 'dashboard-owner');

  for (const [index, metric] of metrics.entries()) {
    const card = cards.nth(index);
    await expect(card).not.toContainText(/NaN|undefined|null/);
    await expect(card).not.toContainText('minor units');
    await expect(card).toContainText(metric.explanation ?? '');
    if (metric.insufficientData) await expect(card).toContainText('Not enough data in this window');
    else await expect(card).not.toContainText('Not enough data in this window');
    if (metric.unit === 'CURRENCY_PER_HOUR' && !metric.insufficientData) await expect(card).toContainText(PER_HOUR);
  }
  await expect(cards.filter({ hasText: /\d{4}-\d{2}-\d{2} → \d{4}-\d{2}-\d{2}/ }).first()).toBeVisible();
});

test('the money card shows minor units per hour as an amount in the currency of the tenant', async ({ page }) => {
  await page.route('**/api/app/dashboard', async (route) => {
    const response = await route.fetch();
    const dashboard = (await response.json()) as DashboardView;
    const metrics = (dashboard.metrics ?? []).map((metric) =>
      metric.unit === 'CURRENCY_PER_HOUR' ? { ...metric, value: 4250, insufficientData: false } : { ...metric, value: null, insufficientData: true }
    );
    await route.fulfill({ response, json: { ...dashboard, metrics } });
  });
  await page.goto('/dashboard');
  const cards = page.getByTestId('metric-card');
  await expect(cards).toHaveCount(6);
  await expect(cards.filter({ hasText: PER_HOUR })).toHaveCount(1);
  await expect(cards.filter({ hasText: PER_HOUR })).toContainText(/GEL\s*42\.50\/h/);
  await expect(cards.filter({ hasText: 'Not enough data in this window' })).toHaveCount(5);
});

test('a metric with too small a sample shows "not enough data" instead of a number', async ({ page }) => {
  await page.route('**/api/app/dashboard', async (route) => {
    const response = await route.fetch();
    const dashboard = (await response.json()) as DashboardView;
    const metrics = (dashboard.metrics ?? []).map((metric, index) =>
      index === 0 ? { ...metric, value: null, insufficientData: true } : { ...metric, value: 0.5, insufficientData: false }
    );
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

  // Signals as the API has them at this moment: each open one can be dismissed, none open says so.
  const signals = (await dashboardOf(request)).signals ?? [];
  await expect(page.getByText('Growth signals', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Dismiss' })).toHaveCount(signals.length);
  await expect(page.getByText('No growth signals right now.')).toHaveCount(signals.length === 0 ? 1 : 0);
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

test.describe('in a browser in another time zone', () => {
  test.use({ timezoneId: 'America/Los_Angeles' });

  test('"Today" holds the visits of the business\'s day, a past one too, in order, and not tomorrow\'s', async ({ page, request }) => {
    const dayOf = (offset: number): string => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tbilisi' }).format(new Date(Date.now() + offset * 86_400_000));
    const visitAt = async (day: string, time: string, name: string): Promise<void> => {
      const response = await request.post(`${API_URL}/api/app/appointments`, {
        headers: { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'OWNER' },
        data: { specialistId: NINO_ID, serviceId: HAIRCUT_ID, serviceStart: new Date(`${day}T${time}:00+04:00`).toISOString(), phone: uniquePhone(), displayName: name, locale: 'en', marketingConsent: false, source: 'CONSOLE', statusOverride: 'CONFIRMED', skipNoticeAndHorizon: true },
      });
      if (!response.ok()) throw new Error(`Visit at ${day} ${time} -> ${response.status()}: ${await response.text()}`);
    };
    const [early, late, tomorrow] = [uniqueName('Today Early'), uniqueName('Today Late'), uniqueName('Tomorrow Visitor')];
    await visitAt(dayOf(0), '23:30', late);
    await visitAt(dayOf(0), '00:30', early);
    await visitAt(dayOf(1), '00:30', tomorrow);

    await page.goto('/dashboard');
    const rows = page.getByText(/^Haircut · /);
    await expect(rows.filter({ hasText: late })).toHaveCount(1);
    const names = await rows.allInnerTexts();
    expect(names.findIndex((text) => text.includes(early))).toBeGreaterThanOrEqual(0);
    expect(names.findIndex((text) => text.includes(early))).toBeLessThan(names.findIndex((text) => text.includes(late)));
    await expect(page.getByText(tomorrow)).toHaveCount(0);
  });
});
