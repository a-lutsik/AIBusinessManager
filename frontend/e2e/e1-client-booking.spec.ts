import { expect, test } from '@playwright/test';
import { taskOfVisit } from './support/app';
import { freeSlots, uniqueName, uniquePhone } from './support/bookings';
import { bookThroughUi, DAY_LABEL, TIME_LABEL } from './support/bookUi';
import { API_URL, BASE_URL, TENANT_SLUG } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

const DAY_MS = 86_400_000;

test('a client books Haircut with Nino through the public page, the owner sees the visit', async ({ page, context, request }) => {
  const phone = uniquePhone();
  const name = uniqueName('Booker');

  const ui = await bookThroughUi(page, {
    service: 'Haircut',
    specialist: 'Nino Beridze',
    name,
    localPhone: phone.slice('+995'.length),
    at: async (step) => {
      if (step === 'service') {
        await page.getByText('RU', { exact: true }).click();
        await expect(page.getByText('Услуга', { exact: true })).toBeVisible();
        await expect(page.getByText('Service', { exact: true })).toHaveCount(0);
        await page.getByText('EN', { exact: true }).click();
        await expect(page.getByText('Service', { exact: true })).toBeVisible();
        await screenshotWidths(page, 'book-service');
      }
      if (step === 'specialist') {
        await expect(page.getByText('Anna Novakova', { exact: true })).toBeVisible();
        await expect(page.getByText('Elena Kravets')).toHaveCount(0);
      }
      if (step === 'slot') {
        // One button per day with free slots; only the slots of the chosen day, whatever the other days hold.
        const days = page.getByRole('button', { name: DAY_LABEL });
        const times = page.getByRole('button', { name: TIME_LABEL });
        await expect(days.nth(1)).toBeVisible();
        const firstDayTimes = await times.allInnerTexts();
        await days.nth(1).click();
        await expect.poll(() => times.allInnerTexts()).not.toEqual(firstDayTimes);
        await days.first().click();
        await expect.poll(() => times.allInnerTexts()).toEqual(firstDayTimes);
        await screenshotWidths(page, 'book-slot');
      }
      if (step === 'contact') await screenshotWidths(page, 'book-contact');
    },
  });

  // Slots: tomorrow..+7 days, every one in the future.
  const now = Date.now();
  expect(ui.slots.length).toBeGreaterThan(0);
  for (const slot of ui.slots) {
    expect(Date.parse(slot)).toBeGreaterThan(now);
    expect(Date.parse(slot)).toBeLessThan(now + 8 * DAY_MS);
  }

  // GE is preselected: the local number got +995 and the booking carries the country.
  expect(ui.request).toMatchObject({ phone, countryCode: 'GE', name, marketingConsent: true });

  // Success: CONFIRMED with the manage link on screen.
  expect(ui.booking.status).toBe('CONFIRMED');
  await expect(page.getByText("You're booked")).toBeVisible();
  const manageUrl = `${BASE_URL}${ui.booking.managePath}`;
  await expect(page.getByText(/^Save this booking link:/)).toContainText(manageUrl);
  await expect(page.locator('body')).not.toContainText(ui.booking.serviceStart);
  await screenshotWidths(page, 'book-success');

  // The client can keep the link: copied in full, and it opens this visit.
  await context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: BASE_URL });
  await page.getByRole('button', { name: 'Copy link', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Copied', exact: true })).toBeVisible();
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(manageUrl);
  const open = page.getByRole('link', { name: 'Open my booking' });
  await expect(open).toHaveAttribute('href', manageUrl);
  await open.click();
  await page.waitForURL(manageUrl);
  await expect(page.getByText(`#${ui.booking.id.replace(/-/g, '').slice(-6).toUpperCase()}`)).toBeVisible();
  await expect(page.getByText(name, { exact: true })).toBeVisible();

  const ics = await request.get(`${API_URL}/api/public/bookings/${ui.booking.id}/calendar.ics?token=${ui.booking.accessToken}`);
  expect(ics.ok()).toBe(true);
  expect(ics.headers()['content-type']).toContain('text/calendar');
  expect(await ics.text()).toContain('BEGIN:VCALENDAR');

  // The owner: a "new booking" task, the visit in the week calendar, the client found by search.
  await useRole(page, 'OWNER');
  const task = await taskOfVisit(request, ui.booking.id);
  await page.goto('/dashboard');
  await expect(page.getByTestId(`owner-task-${task.id}`)).toContainText('New booking — send confirmation manually');

  await page.goto('/calendar');
  const row = page.getByTestId(`visit-row-${ui.booking.id}`);
  await expect(row).toContainText(`Haircut · ${name}`);
  await expect(row).toContainText('Confirmed');

  await page.goto('/clients');
  await expect(page.getByText(/^Client \d+$/).first()).toBeVisible();
  await screenshotWidths(page, 'clients-owner');
  await page.getByPlaceholder('Search').last().fill(name);
  await expect(page.getByText(/^Client \d+$/)).toHaveCount(0);
  await page.getByText(name).click();
  await expect(page.getByText(phone, { exact: true })).toBeVisible();
  await expect(page.getByText('Haircut · Confirmed')).toBeVisible();
  await expect(page.locator('body')).not.toContainText(ui.booking.serviceStart);
  await screenshotWidths(page, 'client-card-owner');
});

test('a late answer for the previous specialist does not replace the slots of the chosen one', async ({ page, request }) => {
  const { specialistId: nino } = await freeSlots(request, 'Haircut', 'Nino');
  // 23:59 UTC is 03:59 AM at the business: a time no real slot has.
  const stale = `${new Date(Date.now() + 2 * DAY_MS).toISOString().slice(0, 10)}T23:59:00Z`;
  let answerStale: () => void = () => undefined;
  const staleAnswered = new Promise<void>((resolve) => (answerStale = resolve));
  await page.route(`**/slots?*specialistId=${nino}*`, async (route) => {
    await staleAnswered;
    await route.fulfill({ json: [stale] });
  });

  await page.goto(`/book/${TENANT_SLUG}`);
  await page.getByText('Haircut', { exact: true }).click();
  await page.getByText('Nino Beridze', { exact: true }).click();
  await page.getByText('Anna Novakova', { exact: true }).click();
  const times = page.getByRole('button', { name: TIME_LABEL });
  await expect(times.first()).toBeVisible();
  const annaTimes = await times.allInnerTexts();

  const late = page.waitForResponse((r) => r.url().includes(`specialistId=${nino}`));
  answerStale();
  await late;
  await page.evaluate(() => new Promise<void>((done) => requestAnimationFrame(() => requestAnimationFrame(() => done()))));
  expect(await times.allInnerTexts()).toEqual(annaTimes);
  await expect(page.getByRole('button', { name: /^03:59/ })).toHaveCount(0);
});

test('a refused or failed slot request is not "no slots": the page says so and the retry works', async ({ page }) => {
  const answers = [429, 500];
  await page.route('**/slots?*', (route) => {
    const status = answers.shift();
    if (status === undefined) return route.fallback();
    return route.fulfill({ status, contentType: 'application/problem+json', json: { code: status === 429 ? 'RATE_LIMITED' : 'INTERNAL', detail: 'refused' } });
  });

  await page.goto(`/book/${TENANT_SLUG}`);
  await page.getByText('Haircut', { exact: true }).click();
  await page.getByText('Nino Beridze', { exact: true }).click();
  const failure = page.getByTestId('slots-failure');
  await expect(failure.getByRole('alert')).toHaveText('Too many requests right now. Please try again in a minute.');
  await expect(page.getByText('No free slots in the next week.'), 'a limit is not a fully booked specialist').toHaveCount(0);
  await screenshotWidths(page, 'book-slots-rate-limited');

  await failure.getByRole('button', { name: 'Try again' }).click();
  await expect(failure.getByRole('alert')).toHaveText('Could not load the free times. Please try again.');
  await expect(page.getByText('No free slots in the next week.')).toHaveCount(0);

  await failure.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByRole('button', { name: TIME_LABEL }).first()).toBeVisible();
  await expect(failure).toHaveCount(0);

  // An answer that really is empty still says so.
  await page.route('**/slots?*', (route) => route.fulfill({ json: [] }));
  await page.getByText('Anna Novakova', { exact: true }).click();
  await expect(page.getByText('No free slots in the next week.')).toBeVisible();
  await expect(failure).toHaveCount(0);
});

test('a booking that lost to a concurrent change asks to retry: the slot stays, the slots are not asked again', async ({ page }) => {
  let refusals = 1;
  await page.route(`**/tenants/${TENANT_SLUG}/bookings`, (route) => {
    if (route.request().method() !== 'POST' || refusals === 0) return route.fallback();
    refusals -= 1;
    return route.fulfill({ status: 409, contentType: 'application/problem+json', json: { code: 'CONCURRENT_RETRY', detail: 'Please retry.' } });
  });
  await page.goto(`/book/${TENANT_SLUG}`);
  await page.getByText('Haircut', { exact: true }).click();
  await page.getByText('Nino Beridze', { exact: true }).click();
  await page.getByRole('button', { name: TIME_LABEL }).first().click();
  await page.getByPlaceholder('Name').fill(uniqueName('Retry Booker'));
  await page.getByPlaceholder('Phone (GE)').fill(uniquePhone().slice('+995'.length));
  let slotReads = 0;
  page.on('request', (r) => {
    if (r.url().includes('/slots?')) slotReads += 1;
  });

  await page.getByRole('button', { name: 'Book', exact: true }).click();

  await expect(page.getByText('The booking did not go through, nothing was saved. Please press Book again.')).toBeVisible();
  await expect(page.getByText('This time is no longer available. Please choose another one.')).toHaveCount(0);
  expect(slotReads).toBe(0);
  await page.getByRole('button', { name: 'Book', exact: true }).click();
  await expect(page.getByText("You're booked")).toBeVisible();
});

test('a slot taken at the last moment: the page says so, drops the slot, asks for slots again, the next try succeeds', async ({ page }) => {
  let refusals = 1;
  await page.route(`**/tenants/${TENANT_SLUG}/bookings`, (route) => {
    if (route.request().method() !== 'POST' || refusals === 0) return route.fallback();
    refusals -= 1;
    return route.fulfill({ status: 409, contentType: 'application/json', json: { code: 'SLOT_OCCUPIED', detail: 'Slot is occupied' } });
  });
  const name = uniqueName('Late Booker');

  await page.goto(`/book/${TENANT_SLUG}`);
  await page.getByText('Haircut', { exact: true }).click();
  await page.getByText('Nino Beridze', { exact: true }).click();
  const times = page.getByRole('button', { name: TIME_LABEL });
  await times.first().click();
  await page.getByPlaceholder('Name').fill(name);
  await page.getByPlaceholder('Phone (GE)').fill(uniquePhone().slice('+995'.length));

  const slotsAgain = page.waitForRequest((r) => r.url().includes('/slots?'));
  await page.getByRole('button', { name: 'Book', exact: true }).click();
  await slotsAgain;
  await expect(page.getByText('This time is no longer available. Please choose another one.')).toBeVisible();
  await expect(page.getByText('Slot is occupied')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Book', exact: true })).toHaveCount(0);

  await times.first().click();
  await expect(page.getByPlaceholder('Name'), 'what the client typed is kept').toHaveValue(name);
  await page.getByRole('button', { name: 'Book', exact: true }).click();
  await expect(page.getByText("You're booked")).toBeVisible();
});
