import { expect, test, type Page } from '@playwright/test';
import type { BookingRulesView } from '../src/api/client';
import { getRules, putRules } from './support/app';
import { createBooking, uniqueName, uniquePhone } from './support/bookings';
import { bookThroughUi, SLOT_LABEL } from './support/bookUi';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

let originalRules: BookingRulesView | undefined;

test.beforeEach(async ({ request }) => {
  originalRules = await getRules(request);
});

test.afterEach(async ({ request }) => {
  if (originalRules) await putRules(request, originalRules);
});

const RESCHEDULE_DISABLED = 'Online rescheduling is not available for this business.';

const toggleOf = (page: Page, label: string) => page.getByText(label, { exact: true }).locator('..').getByRole('switch');

async function saveRule(page: Page, label: string, on: boolean): Promise<void> {
  await toggleOf(page, label).setChecked(on);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText('Rules saved.')).toBeVisible();
}

test('new clients need confirmation: saved, kept after reload, applied to a public booking', async ({ page }) => {
  await useRole(page, 'OWNER');
  await page.goto('/rules');
  await expect(toggleOf(page, 'New clients require confirmation')).not.toBeChecked();
  await screenshotWidths(page, 'rules');

  await saveRule(page, 'New clients require confirmation', true);
  await page.reload();
  await expect(toggleOf(page, 'New clients require confirmation')).toBeChecked();

  const phone = uniquePhone();
  const ui = await bookThroughUi(page, {
    service: 'Haircut',
    specialist: 'Nino Beridze',
    name: uniqueName('Rules Newcomer'),
    localPhone: phone.slice('+995'.length),
  });
  expect(ui.booking.status).toBe('PENDING');
  await expect(page.getByText('Sent for confirmation')).toBeVisible();
  await screenshotWidths(page, 'book-pending');

  await page.goto('/calendar');
  await expect(page.getByTestId(`visit-row-${ui.booking.id}`)).toContainText('Pending');
});

test('with client rescheduling switched off the manage link shows an error instead of slots', async ({ page, request }) => {
  await useRole(page, 'OWNER');
  await page.goto('/rules');
  await saveRule(page, 'Clients may reschedule', false);

  const booking = await createBooking(request, { name: 'Rules Rescheduler' });
  await page.goto(booking.managePath);
  await page.getByRole('button', { name: 'Reschedule' }).click();
  await expect(page.getByText(RESCHEDULE_DISABLED, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: SLOT_LABEL })).toHaveCount(0);
});

test('the rule switched off after the manage link opened: the refused slot gives the same message', async ({ page, request }) => {
  const booking = await createBooking(request, { name: 'Rules Late Rescheduler' });
  await page.route(`**/api/public/bookings/${booking.id}/reschedule*`, (route) =>
    route.fulfill({ status: 403, contentType: 'application/json', json: { code: 'RESCHEDULE_DISABLED', detail: 'Client reschedule is disabled for this business' } })
  );
  await page.goto(booking.managePath);
  await page.getByRole('button', { name: 'Reschedule' }).click();
  await page.getByRole('button', { name: SLOT_LABEL }).first().click();
  await expect(page.getByText(RESCHEDULE_DISABLED, { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: SLOT_LABEL })).toHaveCount(0);
  await expect(page.getByText('Client reschedule is disabled for this business')).toHaveCount(0);

  // The page remembers the refusal: no second request for slots.
  let slotRequests = 0;
  page.on('request', (request_) => {
    if (request_.url().includes('/slots?')) slotRequests += 1;
  });
  await page.getByRole('button', { name: 'Reschedule' }).click();
  await expect(page.getByText(RESCHEDULE_DISABLED, { exact: true })).toBeVisible();
  expect(slotRequests).toBe(0);
});

test('a rules failure other than a refusal shows an error, not a form with default values', async ({ page }) => {
  await useRole(page, 'OWNER');
  await page.route('**/api/app/rules', (route) => route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }));
  await page.goto('/rules');
  await expect(page.getByText('Could not load booking rules.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0);
});
