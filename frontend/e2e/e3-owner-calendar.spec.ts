import { expect, test } from '@playwright/test';
import type { BookingRulesView } from '../src/api/client';
import { getRules, putRules, visitOf } from './support/app';
import { createBooking } from './support/bookings';
import { SLOT_LABEL } from './support/bookUi';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

let originalRules: BookingRulesView | undefined;

test.afterEach(async ({ request }) => {
  if (originalRules) await putRules(request, originalRules);
  originalRules = undefined;
});

test('week and day views, and the Nino filter drops the other masters', async ({ page, request }) => {
  const nino = await createBooking(request, { service: 'Haircut', specialist: 'Nino', name: 'Calendar Nino' });
  const elena = await createBooking(request, { service: 'Manicure', specialist: 'Elena', name: 'Calendar Elena' });
  await useRole(page, 'OWNER');
  await page.goto('/calendar');

  // Week: both visits of tomorrow; day: today only.
  const ninoRow = page.getByTestId(`visit-row-${nino.id}`);
  const elenaRow = page.getByTestId(`visit-row-${elena.id}`);
  await expect(ninoRow).toBeVisible();
  await expect(elenaRow).toBeVisible();
  await page.getByText('Day', { exact: true }).click();
  await expect(ninoRow).toHaveCount(0);
  await page.getByText('Week', { exact: true }).click();
  await expect(ninoRow).toBeVisible();

  // Filter: Nino keeps his rows, Elena's Manicure/Pedicure/Brow Shaping rows go.
  const elenaServices = page.getByText(/^(Manicure|Pedicure|Brow Shaping) · /);
  await expect(elenaServices.first()).toBeVisible();
  await page.getByText('Nino Beridze', { exact: true }).click();
  await expect(elenaServices).toHaveCount(0);
  await expect(ninoRow).toBeVisible();
  await expect(elenaRow).toHaveCount(0);
});

test('a pending visit: panel, confirm, reschedule to another day, cancel by the business', async ({ page, request }) => {
  originalRules = await getRules(request);
  await putRules(request, { ...originalRules, newClientRequiresConfirmation: true });
  const visit = await createBooking(request, { name: 'Calendar Pending' });
  expect(visit.status).toBe('PENDING');
  await useRole(page, 'OWNER');
  await page.goto('/calendar');

  const row = page.getByTestId(`visit-row-${visit.id}`);
  const panel = page.getByTestId('visit-panel');
  await row.click();
  await expect(panel).toContainText(visit.phone);
  await expect(panel.getByText('New', { exact: true })).toBeVisible();
  await expect(panel.getByText('Pending', { exact: true })).toBeVisible();
  // The buttons name the action, the status stands apart from them.
  await expect(panel.getByRole('button', { name: 'Confirm', exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Cancel', exact: true })).toBeVisible();
  await expect(panel).not.toContainText(/:\d{2}:\d{2}/);
  for (const label of ['Confirmed', 'Cancelled by business', 'Mark completed', 'Mark no-show']) {
    await expect(panel.getByRole('button', { name: label, exact: true })).toHaveCount(0);
  }
  await screenshotWidths(page, 'calendar-panel');

  // Confirm: the panel and the list row both say Confirmed.
  await panel.getByRole('button', { name: 'Confirm', exact: true }).click();
  await expect(panel.getByRole('button', { name: 'Confirm', exact: true })).toHaveCount(0);
  await expect(panel.getByText('Confirmed', { exact: true })).toBeVisible();
  await expect(panel.getByText('Pending', { exact: true })).toHaveCount(0);
  await expect(row).toContainText('Confirmed');
  expect((await visitOf(request, visit.id)).status).toBe('CONFIRMED');

  // Reschedule: the slot dialog lists the free slots, the last one is on another day.
  const bookedDay = (iso: string): string => new Date(iso).toLocaleDateString('en-CA', { timeZone: 'Asia/Tbilisi' });
  await panel.getByRole('button', { name: 'Reschedule' }).click();
  const slots = page.getByRole('button', { name: SLOT_LABEL });
  await expect(slots.first()).toBeVisible();
  await screenshotWidths(page, 'calendar-slots');
  await slots.last().click();
  await expect(slots).toHaveCount(0);
  await expect.poll(async () => bookedDay((await visitOf(request, visit.id)).serviceStart)).not.toBe(bookedDay(visit.serviceStart));

  // Cancel by the business: the status changes, there is no reschedule button any more.
  await panel.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(panel.getByRole('button', { name: 'Cancel', exact: true })).toHaveCount(0);
  await expect(panel.getByText('Cancelled by business', { exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Reschedule' })).toHaveCount(0);
  expect((await visitOf(request, visit.id)).status).toBe('CANCELLED_BY_BUSINESS');
});

test.describe('in a browser in another time zone', () => {
  test.use({ timezoneId: 'America/Los_Angeles' });

  test('the calendar asks for the days of the business and shows one day in the day view', async ({ page, request }) => {
    const visit = await createBooking(request, { name: 'Calendar Traveller' });
    const today = new Date().toLocaleDateString('en-CA', { timeZone: 'Asia/Tbilisi' });
    const midnight = (daysAhead: number): number => Date.parse(`${today}T00:00:00+04:00`) + daysAhead * 86_400_000;
    const rangeOf = (url: string): number[] => ['from', 'to'].map((name) => Date.parse(new URL(url).searchParams.get(name) ?? ''));
    const isCalendar = (url: string): boolean => new URL(url).pathname === '/api/app/calendar';
    await useRole(page, 'OWNER');

    const week = page.waitForRequest((r) => isCalendar(r.url()));
    await page.goto('/calendar');
    expect(rangeOf((await week).url())).toEqual([midnight(0), midnight(7)]);
    await expect(page.getByTestId(`visit-row-${visit.id}`)).toBeVisible();

    const day = page.waitForRequest((r) => isCalendar(r.url()));
    await page.getByText('Day', { exact: true }).click();
    expect(rangeOf((await day).url())).toEqual([midnight(0), midnight(1)]);
    await expect(page.getByTestId(`visit-row-${visit.id}`)).toHaveCount(0);
    expect(await page.getByTestId('calendar-day').count()).toBeLessThanOrEqual(1);
  });
});
