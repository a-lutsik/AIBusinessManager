import { expect, test, type Page } from '@playwright/test';
import { createBooking, type CreatedBooking } from './support/bookings';
import { SLOT_LABEL } from './support/bookUi';
import { screenshotWidths } from './support/screenshots';

/** Last 6 hex characters of the visit id, the public #ref. */
const refOf = (id: string): string => id.replace(/-/g, '').slice(-6).toUpperCase();

/** How the page writes an instant in en: without seconds, in the tenant's zone, whatever the browser's zone is. */
const whenOf = (instant: string): string =>
  new Date(instant).toLocaleString('en', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Asia/Tbilisi' });

async function openManageLink(page: Page, booking: CreatedBooking): Promise<void> {
  await page.goto(booking.managePath);
  await expect(page.getByText(`#${refOf(booking.id)}`)).toBeVisible();
}

test('the manage link shows the visit facts and nothing about the client', async ({ page, request }) => {
  const booking = await createBooking(request, { name: 'Manage Viewer' });
  await openManageLink(page, booking);

  await expect(page.getByText("You're booked")).toBeVisible();
  await expect(page.getByText(booking.serviceName, { exact: true })).toBeVisible();
  await expect(page.getByText(whenOf(booking.serviceStart), { exact: true })).toBeVisible();
  await expect(page.getByText(booking.name, { exact: true })).toBeVisible();
  await expect(page.getByText(new RegExp(`GEL\\s*${(booking.priceMinor / 100).toFixed(2)}`))).toBeVisible();

  const text = page.locator('body');
  await expect(text).not.toContainText(booking.phone);
  await expect(text).not.toContainText('+995');
  await expect(text).not.toContainText(/trust/i);
  await expect(text).not.toContainText(/history/i);
  await screenshotWidths(page, 'visit-active');
});

test('the client reschedules to another slot', async ({ page, request }) => {
  const booking = await createBooking(request, { name: 'Manage Rescheduler' });
  await openManageLink(page, booking);
  const before = whenOf(booking.serviceStart);

  await page.getByRole('button', { name: 'Reschedule' }).click();
  const slots = page.getByRole('button', { name: SLOT_LABEL });
  await expect(slots.first()).toBeVisible();
  const target = slots.last();
  const after = (await target.innerText()).trim();
  expect(after).not.toBe(before);
  await target.click();

  await expect(page.getByText(after, { exact: true })).toBeVisible();
  await expect(page.getByText(before, { exact: true })).toHaveCount(0);
  await expect(page.getByText('Confirmed', { exact: true }).first()).toBeVisible();
});

test('the client withdraws the marketing consent', async ({ page, request }) => {
  const booking = await createBooking(request, { name: 'Manage Withdrawer', marketingConsent: true });
  await openManageLink(page, booking);

  await page.getByRole('button', { name: 'Stop marketing messages' }).click();
  await expect(page.getByText('You will not receive marketing messages.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Stop marketing messages' })).toHaveCount(0);
});

test('the client cancels, the action buttons disappear', async ({ page, request }) => {
  const booking = await createBooking(request, { name: 'Manage Canceller' });
  await openManageLink(page, booking);

  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(page.getByText('Cancelled by client').first()).toBeVisible();
  for (const label of ['Cancel', 'Reschedule', 'Add to calendar']) {
    await expect(page.getByRole('button', { name: label, exact: true })).toHaveCount(0);
  }
  await screenshotWidths(page, 'visit-cancelled');
});

test('a broken token shows an error and no visit data', async ({ page, request }) => {
  const booking = await createBooking(request, { name: 'Manage Intruder' });
  await page.goto(`/b/${booking.id}?token=broken-token`);

  await expect(page.getByText('Booking link is invalid')).toBeVisible();
  const text = page.locator('body');
  await expect(text).not.toContainText(booking.serviceName);
  await expect(text).not.toContainText(booking.name);
  await expect(text).not.toContainText(`#${refOf(booking.id)}`);
  await screenshotWidths(page, 'visit-broken-token');
});

test.describe('in a browser in another time zone', () => {
  test.use({ timezoneId: 'Asia/Tokyo' });

  test('the manage link shows the time in the zone of the business', async ({ page, request }) => {
    const booking = await createBooking(request, { name: 'Manage Traveller' });
    await openManageLink(page, booking);
    await expect(page.getByText(whenOf(booking.serviceStart), { exact: true })).toBeVisible();
  });
});
