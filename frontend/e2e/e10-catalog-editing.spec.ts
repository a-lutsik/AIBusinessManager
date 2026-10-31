import { randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { uniqueName, uniquePhone } from './support/bookings';
import { bookThroughUi, SLOT_LABEL } from './support/bookUi';
import { API_URL } from './support/env';
import { screenshotWidths } from './support/screenshots';
import { sql } from './support/sql';

/**
 * Stage 07: the owner fills an empty catalog through the console and clients book it. Every test works in a tenant of
 * its own, so the catalog really is empty and the counts of the demo tenant in the other specs (eight services, three
 * masters) stay true whatever order the files run in. The console always names the demo tenant in dev-headers mode, so
 * the page's requests are pointed at the test's tenant on the way out.
 */

const DAY_MS = 86_400_000;
const MONEY = /GEL\s*\d|\d\s*GEL|₾/;

type Studio = { id: string; slug: string };
type Created = { id: string };
type Booked = { id: string; status: string; priceMinor: number; managePath: string };

function newStudio(currency = 'GEL'): Studio {
  const id = randomUUID();
  const slug = `e10-${id.slice(0, 8)}`;
  sql(`INSERT INTO tenant (id, slug, display_name, timezone, currency_code, country_code) VALUES ('${id}', '${slug}', 'E10 Studio', 'Asia/Tbilisi', '${currency}', 'GE')`);
  return { id, slug };
}

/** The console of this studio, as the owner or as the master with this specialist id. */
async function openAs(page: Page, studio: Studio, role: 'OWNER' | 'MASTER', specialistId?: string): Promise<void> {
  await page.addInitScript(
    ({ actor, specialist }) => {
      localStorage.setItem('cadence.role', actor);
      if (specialist) localStorage.setItem('cadence.specialistId', specialist);
      else localStorage.removeItem('cadence.specialistId');
    },
    { actor: role, specialist: specialistId }
  );
  await page.route(`${API_URL}/api/app/**`, (route) =>
    route.continue({ headers: { ...route.request().headers(), 'x-tenant-id': studio.id } })
  );
}

const ownerHeaders = (studio: Studio) => ({ 'X-Tenant-Id': studio.id, 'X-Actor-Role': 'OWNER' });
const masterHeaders = (studio: Studio, specialist: string) => ({ 'X-Tenant-Id': studio.id, 'X-Actor-Role': 'MASTER', 'X-Specialist-Id': specialist });

async function ownerPut<T>(request: APIRequestContext, studio: Studio, path: string, data: unknown): Promise<T> {
  const response = await request.put(`${API_URL}/api/app${path}`, { headers: ownerHeaders(studio), data });
  if (!response.ok()) throw new Error(`PUT ${path} -> ${response.status()}: ${await response.text()}`);
  return (await response.json()) as T;
}

const serviceBody = (name: string, overrides: Record<string, unknown> = {}) => ({
  id: null,
  name,
  description: null,
  durationMinutes: 60,
  priceMinor: 8000,
  bufferBeforeMinutes: 0,
  bufferAfterMinutes: 0,
  color: '#0f766e',
  active: true,
  publicVisible: true,
  ...overrides,
});

const specialistBody = (displayName: string, overrides: Record<string, unknown> = {}) => ({
  id: null,
  displayName,
  keycloakUserId: null,
  calendarColor: '#7c3aed',
  active: true,
  ...overrides,
});

/** A service and a master who offers it and works every day, set up over the API for the tests about later edits. */
async function readyStudio(request: APIRequestContext, currency = 'GEL'): Promise<Studio & { service: string; specialist: string }> {
  const studio = newStudio(currency);
  const service = await ownerPut<Created>(request, studio, '/services', serviceBody('Fade'));
  const specialist = await ownerPut<Created>(request, studio, '/specialists', specialistBody('Tamar'));
  await ownerPut(request, studio, `/specialists/${specialist.id}/matrix`, { serviceId: service.id, offered: true });
  await ownerPut(
    request,
    studio,
    `/specialists/${specialist.id}/schedule`,
    [1, 2, 3, 4, 5, 6, 7].map((weekday) => ({ weekday, startMinute: 0, endMinute: 1440 }))
  );
  return { ...studio, service: service.id, specialist: specialist.id };
}

const isoDate = (daysAhead: number): string => new Date(Date.now() + daysAhead * DAY_MS).toISOString().slice(0, 10);

async function publicSlots(request: APIRequestContext, studio: Studio, service: string, specialist: string, from: string, to: string): Promise<string[]> {
  const response = await request.get(`${API_URL}/api/public/tenants/${studio.slug}/slots?serviceId=${service}&specialistId=${specialist}&from=${from}&to=${to}`);
  if (!response.ok()) throw new Error(`slots -> ${response.status()}: ${await response.text()}`);
  return (await response.json()) as string[];
}

/** Books the slot at this index of the coming days through the public API. */
async function bookOverApi(request: APIRequestContext, studio: Studio, service: string, specialist: string, slotIndex: number): Promise<Booked> {
  const slots = await publicSlots(request, studio, service, specialist, isoDate(1), isoDate(7));
  const response = await request.post(`${API_URL}/api/public/tenants/${studio.slug}/bookings`, {
    data: { serviceId: service, specialistId: specialist, serviceStart: slots[slotIndex], phone: uniquePhone(), name: uniqueName('Snapshot'), locale: 'en', marketingConsent: false, countryCode: 'GE' },
  });
  if (!response.ok()) throw new Error(`booking -> ${response.status()}: ${await response.text()}`);
  return (await response.json()) as Booked;
}

async function visitFacts(request: APIRequestContext, studio: Studio, id: string): Promise<{ status: string; serviceStart: string; priceSnapshot: number; durationSnapshot: number }> {
  const response = await request.get(`${API_URL}/api/app/appointments/${id}`, { headers: ownerHeaders(studio) });
  expect(response.status()).toBe(200);
  return ((await response.json()) as { appointment: { status: string; serviceStart: string; priceSnapshot: number; durationSnapshot: number } }).appointment;
}

const toggleOf = (page: Page, label: string) => page.getByText(label, { exact: true }).locator('..').getByRole('switch');
const save = (page: Page) => page.getByRole('button', { name: 'Save', exact: true });

test('on an empty catalog the owner sets up everything in the console and a client books it', async ({ page, request }) => {
  const studio = newStudio();
  await openAs(page, studio, 'OWNER');

  // The service: every refusal is readable, carries its code and leaves what was typed.
  await page.goto('/services');
  await expect(page.getByText('No services yet.')).toBeVisible();
  await page.getByRole('button', { name: 'Add service' }).click();
  const form = page.getByTestId('service-form');
  await save(page).click();
  await expect(form.getByTestId('form-error')).toHaveText('Enter a price. (PRICE_REQUIRED)');
  await page.getByLabel('Price, GEL').fill('-5');
  await save(page).click();
  await expect(form.getByTestId('form-error')).toHaveText('The price must not be negative. (PRICE_NEGATIVE)');
  await page.getByLabel('Price, GEL').fill('80,50');
  await save(page).click();
  await expect(form.getByTestId('form-error')).toHaveText('Enter a name, up to 256 characters. (INVALID_NAME)');
  await page.getByLabel('Name').fill('Beard trim');
  await page.getByLabel('Duration, min').fill('0');
  await save(page).click();
  await expect(form.getByTestId('form-error')).toHaveText('The duration must be from 1 to 1440 minutes. (INVALID_DURATION)');
  await expect(page.getByLabel('Name')).toHaveValue('Beard trim');
  await expect(page.getByLabel('Price, GEL')).toHaveValue('80,50');
  await page.getByLabel('Duration, min').fill('30');
  await page.getByLabel('Color').fill('red');
  await save(page).click();
  await expect(form.getByTestId('form-error')).toHaveText('The color must be a code like #0f766e. (INVALID_COLOR)');
  await screenshotWidths(page, 'catalog-service-form-error');
  await page.getByRole('button', { name: '#0369a1' }).click();
  await save(page).click();
  const card = page.getByTestId('service-card-Beard trim');
  await expect(card).toContainText(/GEL\s*80\.50/);
  await expect(form).toHaveCount(0);
  await screenshotWidths(page, 'catalog-services-owner');

  // The master.
  await page.goto('/specialists');
  await expect(page.getByText('No specialists yet.')).toBeVisible();
  await page.getByRole('button', { name: 'Add specialist' }).click();
  await save(page).click();
  await expect(page.getByTestId('specialist-form').getByTestId('form-error')).toContainText('INVALID_NAME');
  await page.getByLabel('Name').fill('Giorgi');
  await page.getByRole('button', { name: '#7c3aed' }).click();
  await screenshotWidths(page, 'catalog-specialist-form');
  await save(page).click();
  await page.getByText('Giorgi', { exact: true }).click();
  await expect(page).toHaveURL(/\/specialists\/[0-9a-f-]{36}$/);
  const specialistId = page.url().split('/').pop() ?? '';
  await expect(page.getByTestId('specialist-name')).toHaveText('Giorgi');

  // Nothing on the booking page yet: the master does not offer the service.
  const row = page.getByTestId('matrix-row-Beard trim');
  await expect(row).toContainText('No');
  await row.getByRole('button', { name: 'Edit' }).click();
  const matrixForm = page.getByTestId('matrix-form');
  await expect(matrixForm.getByLabel('Price, GEL')).toHaveAttribute('placeholder', '80.50');
  await toggleOf(page, 'Offers this service').click();
  await matrixForm.getByLabel('Duration, min').fill('-1');
  await save(page).click();
  await expect(matrixForm.getByTestId('form-error')).toContainText('INVALID_DURATION');
  await expect(toggleOf(page, 'Offers this service')).toBeChecked();
  await matrixForm.getByLabel('Duration, min').fill('');
  await screenshotWidths(page, 'catalog-matrix-form');
  await save(page).click();
  await expect(matrixForm).toHaveCount(0);
  await expect(row).toContainText('Yes');

  // The week: the overlap is the server's refusal, and the typed intervals stay.
  const schedule = page.getByTestId('weekly-schedule');
  for (let weekday = 1; weekday <= 7; weekday += 1) {
    await page.getByTestId(`schedule-day-${weekday}`).getByRole('button', { name: 'Add interval' }).click();
  }
  await page.getByTestId('schedule-day-1').getByRole('button', { name: 'Add interval' }).click();
  await schedule.getByRole('button', { name: 'Save schedule' }).click();
  await expect(schedule.getByTestId('form-error')).toHaveText('Intervals of one day must not overlap. (SCHEDULE_OVERLAP)');
  await page.getByTestId('schedule-day-1').getByRole('button', { name: 'Remove' }).last().click();
  await page.getByTestId('schedule-day-2').getByLabel('To').fill('late');
  await schedule.getByRole('button', { name: 'Save schedule' }).click();
  await expect(schedule.getByTestId('form-error')).toContainText('TIME_FORMAT');
  await page.getByTestId('schedule-day-2').getByLabel('To').fill('18:30');
  await schedule.getByRole('button', { name: 'Save schedule' }).click();
  await expect(schedule.getByText('Schedule saved.')).toBeVisible();

  // A day off takes that date's slots away, deleting it brings them back.
  const exceptions = page.getByTestId('schedule-exceptions');
  await expect(exceptions.getByText('No upcoming exceptions.')).toBeVisible();
  const off = isoDate(2);
  await exceptions.getByLabel('Date, YYYY-MM-DD').fill('tomorrow');
  await exceptions.getByRole('button', { name: 'Add exception' }).click();
  await expect(exceptions.getByTestId('form-error')).toContainText('DATE_FORMAT');
  await exceptions.getByLabel('Date, YYYY-MM-DD').fill(off);
  await exceptions.getByLabel('Note').fill('Training');
  await exceptions.getByRole('button', { name: 'Add exception' }).click();
  const offRow = exceptions.getByTestId(`exception-${off}`);
  await expect(offRow).toContainText('Day off');
  await expect(offRow).toContainText('Training');
  await exceptions.getByLabel('Date, YYYY-MM-DD').fill(off);
  await exceptions.getByRole('button', { name: 'Add exception' }).click();
  await expect(exceptions.getByTestId('form-error')).toContainText('EXCEPTION_DATE_TAKEN');
  await expect(exceptions.getByTestId(`exception-${off}`)).toHaveCount(1);
  await exceptions.getByLabel('Date, YYYY-MM-DD').fill('');
  await screenshotWidths(page, 'catalog-specialist-detail');

  await page.reload();
  await expect(page.getByTestId('schedule-day-2').getByLabel('To')).toHaveValue('18:30');
  await expect(offRow).toBeVisible();

  const services = (await (await request.get(`${API_URL}/api/public/tenants/${studio.slug}/services`)).json()) as Created[];
  expect(services).toHaveLength(1);
  const serviceId = services[0].id;
  expect(await publicSlots(request, studio, serviceId, specialistId, off, off)).toEqual([]);
  await offRow.getByRole('button', { name: 'Delete' }).click();
  await expect(offRow).toHaveCount(0);
  expect((await publicSlots(request, studio, serviceId, specialistId, off, off)).length).toBeGreaterThan(0);

  // Changed hours of one date replace the week on that date.
  const shifted = isoDate(3);
  await exceptions.getByLabel('Date, YYYY-MM-DD').fill(shifted);
  await exceptions.getByText('Changed hours', { exact: true }).click();
  await exceptions.getByLabel('From').fill('14:00');
  await exceptions.getByLabel('To').fill('13:00');
  await exceptions.getByRole('button', { name: 'Add exception' }).click();
  await expect(exceptions.getByTestId('form-error')).toContainText('INVALID_EXCEPTION_MINUTES');
  await exceptions.getByLabel('To').fill('15:00');
  await exceptions.getByRole('button', { name: 'Add exception' }).click();
  await expect(exceptions.getByTestId(`exception-${shifted}`)).toContainText('14:00 – 15:00');
  const shiftedSlots = await publicSlots(request, studio, serviceId, specialistId, shifted, shifted);
  expect(shiftedSlots.length).toBeGreaterThan(0);
  expect(shiftedSlots.length).toBeLessThanOrEqual(3);

  // The client books what the owner set up, through the public page.
  const phone = uniquePhone();
  const name = uniqueName('First');
  const ui = await bookThroughUi(page, { slug: studio.slug, service: 'Beard trim', specialist: 'Giorgi', name, localPhone: phone.slice('+995'.length) });
  expect(ui.slots.length).toBeGreaterThan(0);
  expect(ui.booking.status).toBe('CONFIRMED');
  expect(await visitFacts(request, studio, ui.booking.id)).toMatchObject({ priceSnapshot: 8050, durationSnapshot: 30 });

  await page.goto('/calendar');
  await expect(page.getByTestId(`visit-row-${ui.booking.id}`)).toBeVisible();
});

test('a new price and duration change only new visits, a matrix override wins for the new ones', async ({ page, request }) => {
  const studio = await readyStudio(request);
  const before = await bookOverApi(request, studio, studio.service, studio.specialist, 0);
  expect(before.priceMinor).toBe(8000);
  await openAs(page, studio, 'OWNER');

  await page.goto('/services');
  await page.getByTestId('service-card-Fade').getByRole('button', { name: 'Edit' }).click();
  await expect(page.getByLabel('Price, GEL')).toHaveValue('80.00');
  await page.getByLabel('Price, GEL').fill('95.5');
  await page.getByLabel('Duration, min').fill('90');
  await save(page).click();
  await expect(page.getByTestId('service-card-Fade')).toContainText(/GEL\s*95\.50/);
  await expect(page.getByTestId('service-card-Fade')).toContainText('90 min');

  const after = await bookOverApi(request, studio, studio.service, studio.specialist, 20);
  expect(after.priceMinor).toBe(9550);
  expect(await visitFacts(request, studio, before.id)).toMatchObject({ priceSnapshot: 8000, durationSnapshot: 60, status: before.status });
  expect(await visitFacts(request, studio, after.id)).toMatchObject({ priceSnapshot: 9550, durationSnapshot: 90 });

  // What the client sees of the first visit is the old price, of the second the new one.
  await page.goto(before.managePath);
  await expect(page.getByText(/GEL\s*80\.00/)).toBeVisible();
  await page.goto(after.managePath);
  await expect(page.getByText(/GEL\s*95\.50/)).toBeVisible();

  await page.goto(`/specialists/${studio.specialist}`);
  const row = page.getByTestId('matrix-row-Fade');
  await row.getByRole('button', { name: 'Edit' }).click();
  await page.getByTestId('matrix-form').getByLabel('Price, GEL').fill('70');
  await page.getByTestId('matrix-form').getByLabel('Duration, min').fill('45');
  await save(page).click();
  await expect(row).toContainText(/GEL\s*70\.00/);
  await expect(row).toContainText('45 min');
  const overridden = await bookOverApi(request, studio, studio.service, studio.specialist, 40);
  expect(overridden.priceMinor).toBe(7000);
  expect(await visitFacts(request, studio, overridden.id)).toMatchObject({ priceSnapshot: 7000, durationSnapshot: 45 });
  expect(await visitFacts(request, studio, after.id)).toMatchObject({ priceSnapshot: 9550, durationSnapshot: 90 });

  // Clearing the override goes back to the value of the service.
  await row.getByRole('button', { name: 'Edit' }).click();
  await page.getByTestId('matrix-form').getByLabel('Price, GEL').fill('');
  await save(page).click();
  await expect(row).not.toContainText(/GEL\s*70\.00/);
  await expect(row).toContainText(/GEL\s*95\.50/);
});

test('a master sees the same screens without editing or money and cannot write over the API', async ({ page, request }) => {
  const studio = await readyStudio(request);
  await ownerPut(request, studio, `/specialists/${studio.specialist}/exceptions`, { date: isoDate(5), kind: 'DAY_OFF', note: 'Holiday' });
  await openAs(page, studio, 'MASTER', studio.specialist);

  await page.goto('/services');
  await expect(page.getByText('Fade', { exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await expect(page.getByText('Dashboard', { exact: true }), 'the header names the page').toHaveCount(0);
  await expect(page.getByText('Services', { exact: true })).toHaveCount(2);
  await expect(page.getByRole('button', { name: /Edit|Add service|Save/ })).toHaveCount(0);
  await expect(page.locator('body')).not.toContainText(MONEY);
  await screenshotWidths(page, 'catalog-services-master');

  await page.goto('/specialists');
  await expect(page.getByText('Tamar', { exact: true })).toBeVisible();
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('button', { name: /Edit|Add specialist/ })).toHaveCount(0);

  await page.goto(`/specialists/${studio.specialist}`);
  await expect(page.getByTestId('matrix-row-Fade')).toBeVisible();
  await expect(page.getByTestId('specialist-name')).toHaveText('Tamar');
  await expect(page.getByText('Specialists', { exact: true }), 'the header names the page').toBeVisible();
  await expect(page.getByText('Dashboard', { exact: true })).toHaveCount(0);
  await expect(page.getByTestId('schedule-day-1')).toContainText('00:00 – 24:00');
  await expect(page.getByTestId(`exception-${isoDate(5)}`)).toContainText('Holiday');
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('button', { name: /Edit|Save|Add|Remove|Delete/ })).toHaveCount(0);
  for (const section of ['weekly-schedule', 'schedule-exceptions']) {
    await expect(page.getByTestId(section).locator('input, textarea'), section).toHaveCount(0);
  }
  await expect(page.getByRole('switch')).toHaveCount(0);
  await expect(page.locator('body')).not.toContainText(MONEY);
  await screenshotWidths(page, 'catalog-specialist-detail-master');

  const headers = masterHeaders(studio, studio.specialist);
  const base = `${API_URL}/api/app`;
  const writes = [
    request.put(`${base}/services`, { headers, data: serviceBody('Sneaky', { id: studio.service, priceMinor: 1 }) }),
    request.put(`${base}/specialists`, { headers, data: specialistBody('Sneaky', { id: studio.specialist }) }),
    request.put(`${base}/specialists/${studio.specialist}/matrix`, { headers, data: { serviceId: studio.service, offered: true, priceMinorOverride: 1 } }),
    request.put(`${base}/specialists/${studio.specialist}/schedule`, { headers, data: [] }),
    request.put(`${base}/specialists/${studio.specialist}/exceptions`, { headers, data: { date: isoDate(6), kind: 'DAY_OFF' } }),
    request.delete(`${base}/specialists/${studio.specialist}/exceptions/${randomUUID()}`, { headers }),
  ];
  for (const write of await Promise.all(writes)) {
    expect(write.status(), write.url()).toBe(403);
    expect(((await write.json()) as { code: string }).code).toBe('FORBIDDEN_RESOURCE');
  }

  const prices = (await (await request.get(`${base}/services`, { headers })).json()) as { priceMinor: number | null }[];
  expect(prices.map((s) => s.priceMinor)).toEqual([null]);
  const matrix = (await (await request.get(`${base}/specialists/${studio.specialist}/matrix`, { headers })).json()) as { defaultPriceMinor: number | null }[];
  expect(matrix.map((r) => r.defaultPriceMinor)).toEqual([null]);
});

test('a week that did not load has no form to save over it, and a retry brings it back', async ({ page, request }) => {
  const studio = await readyStudio(request);
  await openAs(page, studio, 'OWNER');
  let fail = true;
  await page.route('**/api/app/specialists/*/schedule', (route) =>
    route.request().method() === 'GET' && fail ? route.fulfill({ status: 500, json: { code: 'BOOM' } }) : route.fallback()
  );
  await page.goto(`/specialists/${studio.specialist}`);
  const schedule = page.getByTestId('weekly-schedule');
  await expect(schedule.getByTestId('load-failed')).toContainText('Could not load the data.');
  await expect(schedule.getByRole('button', { name: 'Save schedule' })).toHaveCount(0);
  await expect(page.getByTestId('schedule-day-1')).toHaveCount(0);

  fail = false;
  await schedule.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByTestId('schedule-day-1').getByLabel('To')).toHaveValue('24:00');
  await expect(schedule.getByTestId('load-failed')).toHaveCount(0);
  await page.getByTestId('schedule-day-1').getByLabel('To').fill('20:00');
  await schedule.getByRole('button', { name: 'Save schedule' }).click();
  await expect(schedule.getByText('Schedule saved.')).toBeVisible();
  const stored = (await (await request.get(`${API_URL}/api/app/specialists/${studio.specialist}/schedule`, { headers: ownerHeaders(studio) })).json()) as unknown[];
  expect(stored).toHaveLength(7);
});

test('a read that fails keeps the list and the open form', async ({ page, request }) => {
  const studio = await readyStudio(request);
  await openAs(page, studio, 'OWNER');
  let fail = false;
  await page.route('**/api/app/services', (route) =>
    route.request().method() === 'GET' && fail ? route.fulfill({ status: 500, json: { code: 'BOOM' } }) : route.fallback()
  );
  await page.goto('/services');
  await expect(page.getByTestId('service-card-Fade')).toBeVisible();

  // Saving a second service reads the list again: the read fails, the service that is on screen stays.
  fail = true;
  await page.getByRole('button', { name: 'Add service' }).click();
  await page.getByLabel('Name').fill('Second');
  await page.getByLabel('Price, GEL').fill('10');
  await save(page).click();
  await expect(page.getByTestId('load-failed')).toBeVisible();
  await expect(page.getByTestId('service-card-Fade')).toBeVisible();
  await expect(page.getByText('No services yet.')).toHaveCount(0);

  // An open form survives another failed read.
  await page.getByTestId('service-card-Fade').getByRole('button', { name: 'Edit' }).click();
  await page.getByLabel('Name').fill('Fade long');
  const refused = page.waitForResponse((r) => r.url().endsWith('/api/app/services') && r.status() === 500);
  await page.getByRole('button', { name: 'Try again' }).click();
  await refused;
  await expect(page.getByLabel('Name')).toHaveValue('Fade long');

  fail = false;
  await page.getByRole('button', { name: 'Try again' }).click();
  await expect(page.getByTestId('service-card-Second')).toBeVisible();
  await expect(page.getByTestId('load-failed')).toHaveCount(0);
  await expect(page.getByLabel('Name')).toHaveValue('Fade long');
});

test('a client of another tenant moves the visit by the very link the booking gave', async ({ page, request }) => {
  const studio = await readyStudio(request);
  const visit = await bookOverApi(request, studio, studio.service, studio.specialist, 0);
  expect(visit.managePath).not.toContain('slug');
  const before = (await visitFacts(request, studio, visit.id)).serviceStart;

  await page.goto(visit.managePath);
  await page.getByRole('button', { name: 'Reschedule' }).click();
  const moved = page.waitForResponse((r) => r.url().includes(`/bookings/${visit.id}/reschedule`));
  await page.getByRole('button', { name: SLOT_LABEL }).last().click();
  expect((await moved).status()).toBe(200);

  const after = await visitFacts(request, studio, visit.id);
  expect(after.serviceStart).not.toBe(before);
  expect(after.status).toBe('CONFIRMED');

  // A link from before the slug came with the visit still works, whatever its parameter says.
  await page.goto(`${visit.managePath}&slug=lumen-studio`);
  await page.getByRole('button', { name: 'Reschedule' }).click();
  await expect(page.getByRole('button', { name: SLOT_LABEL }).first()).toBeVisible();
});

test('a deactivated master loses the console and their visits can only be cancelled; reactivation brings the access back', async ({ page, context, request }) => {
  const studio = await readyStudio(request);
  const visit = await bookOverApi(request, studio, studio.service, studio.specialist, 0);
  const { managePath } = visit;
  await openAs(page, studio, 'OWNER');

  await page.goto(managePath);
  await page.getByRole('button', { name: 'Reschedule' }).click();
  await expect(page.getByRole('button', { name: SLOT_LABEL }).first()).toBeVisible();

  await page.goto('/specialists');
  await page.getByTestId('specialist-card-Tamar').getByRole('button', { name: 'Edit' }).click();
  await toggleOf(page, 'Active').click();
  await expect(page.getByText('loses access to the console', { exact: false })).toBeVisible();
  await expect(page.getByText('can only be cancelled, not moved', { exact: false })).toBeVisible();
  await save(page).click();
  await expect(page.getByTestId('specialist-card-Tamar')).toContainText('Inactive');

  // The client: a clear message by code, no text of the server.
  await page.goto(managePath);
  await page.getByRole('button', { name: 'Reschedule' }).click();
  await expect(page.getByText('This visit can no longer be moved', { exact: false })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('does not offer');
  await screenshotWidths(page, 'catalog-visit-of-inactive-master');

  // The owner: the same in the visit panel, cancelling still works.
  await page.goto('/calendar');
  await page.getByTestId(`visit-row-${visit.id}`).click();
  const panel = page.getByTestId('visit-panel');
  await panel.getByRole('button', { name: 'Reschedule' }).click();
  await expect(page.getByText('This visit can no longer be moved', { exact: false })).toBeVisible();
  await expect(page.locator('body')).not.toContainText('does not offer');
  await panel.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect.poll(async () => (await visitFacts(request, studio, visit.id)).status).toBe('CANCELLED_BY_BUSINESS');

  // The master: every console request is refused, the screen says why.
  const headers = masterHeaders(studio, studio.specialist);
  const refused = await request.get(`${API_URL}/api/app/me`, { headers });
  expect(refused.status()).toBe(403);
  expect(((await refused.json()) as { code: string }).code).toBe('SPECIALIST_INACTIVE');
  const masterPage = await context.newPage();
  await openAs(masterPage, studio, 'MASTER', studio.specialist);
  await masterPage.goto('/dashboard');
  await expect(masterPage.getByTestId('profile-problem')).toContainText('This specialist is inactive, so the console is closed.');
  await screenshotWidths(masterPage, 'catalog-master-inactive');

  await ownerPut(request, studio, '/specialists', specialistBody('Tamar', { id: studio.specialist, active: true }));
  expect((await request.get(`${API_URL}/api/app/me`, { headers })).status()).toBe(200);
  await masterPage.reload();
  await expect(masterPage.getByText('Today', { exact: true }).first()).toBeVisible();
  await expect(masterPage.getByText('This specialist is inactive', { exact: false })).toHaveCount(0);
  await masterPage.close();
});

test('a master deactivated while the console is open sees why at the next request, without a loop of requests', async ({ page, request }) => {
  const studio = await readyStudio(request);
  await openAs(page, studio, 'MASTER', studio.specialist);
  let calls = 0;
  page.on('request', (r) => {
    if (r.url().includes('/api/app/')) calls += 1;
  });
  await page.goto('/calendar');
  await page.waitForLoadState('networkidle');

  await ownerPut(request, studio, '/specialists', specialistBody('Tamar', { id: studio.specialist, active: false }));
  await page.locator('a[href="/clients"]').click();
  const reason = page.getByTestId('profile-problem');
  await expect(reason).toContainText('This specialist is inactive, so the console is closed.');
  await page.waitForLoadState('networkidle');
  const settled = calls;
  await expect(page.locator('a[href="/clients"]')).toBeVisible();
  await page.waitForLoadState('networkidle');
  expect(calls, 'nothing keeps asking').toBe(settled);

  await ownerPut(request, studio, '/specialists', specialistBody('Tamar', { id: studio.specialist, active: true }));
  await reason.getByRole('button', { name: 'Try again' }).click();
  await expect(reason).toHaveCount(0);
});

test('an owner whose profile did not load is told so, gets no price forms and can retry', async ({ page, request }) => {
  const studio = await readyStudio(request);
  await openAs(page, studio, 'OWNER');
  let fail = true;
  await page.route('**/api/app/me', (route) => (fail ? route.fulfill({ status: 500, json: { code: 'BOOM' } }) : route.fallback()));
  const notice = page.getByTestId('profile-problem');
  const generic = 'Could not load your profile, so prices and editing are unavailable.';

  await page.goto('/dashboard');
  await expect(notice).toContainText(generic);

  await page.goto(`/specialists/${studio.specialist}`);
  await expect(notice).toContainText(generic);
  await expect(page.getByTestId('matrix-row-Fade').getByRole('button', { name: 'Edit' })).toHaveCount(0);

  await page.goto('/services');
  await expect(notice).toContainText(generic);
  await expect(page.getByTestId('service-card-Fade')).not.toContainText('GEL');
  await expect(page.getByRole('button', { name: /Edit|Add service/ })).toHaveCount(0);
  await screenshotWidths(page, 'catalog-profile-not-loaded');

  fail = false;
  await notice.getByRole('button', { name: 'Try again' }).click();
  await expect(notice).toHaveCount(0);
  await expect(page.getByTestId('service-card-Fade')).toContainText(/GEL\s*80\.00/);
  await expect(page.getByRole('button', { name: 'Add service' })).toBeVisible();
});

test('a sign-in with no specialist behind it is told why and sees no screen', async ({ page, request }) => {
  const studio = await readyStudio(request);
  await openAs(page, studio, 'MASTER', studio.specialist);
  await page.route('**/api/app/me', (route) => route.fulfill({ status: 403, json: { code: 'SPECIALIST_UNMAPPED', detail: 'Master is not mapped to a specialist' } }));
  await page.goto('/services');
  const notice = page.getByTestId('profile-problem');
  await expect(notice).toContainText('This sign-in is not linked to a specialist.');
  await expect(notice).toContainText('SPECIALIST_UNMAPPED');
  await expect(page.locator('body')).not.toContainText('not mapped');
  await expect(page.getByTestId('service-card-Fade')).toHaveCount(0);
});

test('invalid input over the API gets a 4xx with a code, never a 500', async ({ request }) => {
  const studio = await readyStudio(request);
  const headers = ownerHeaders(studio);
  const base = `${API_URL}/api/app`;
  const ofSpecialist = `${base}/specialists/${studio.specialist}`;
  const cases: [string, string, unknown, number, string][] = [
    ['services', `${base}/services`, serviceBody(' '), 400, 'INVALID_NAME'],
    ['services', `${base}/services`, serviceBody('X', { durationMinutes: 0 }), 400, 'INVALID_DURATION'],
    ['services', `${base}/services`, serviceBody('X', { priceMinor: -1 }), 400, 'INVALID_PRICE'],
    ['services', `${base}/services`, serviceBody('X', { bufferAfterMinutes: -1 }), 400, 'INVALID_BUFFER'],
    ['services', `${base}/services`, serviceBody('X', { color: 'teal' }), 400, 'INVALID_COLOR'],
    ['services', `${base}/services`, serviceBody('X', { id: randomUUID() }), 404, 'SERVICE_NOT_FOUND'],
    ['specialists', `${base}/specialists`, specialistBody('X', { calendarColor: null }), 400, 'INVALID_COLOR'],
    ['matrix', `${ofSpecialist}/matrix`, { serviceId: randomUUID(), offered: true }, 404, 'SERVICE_NOT_FOUND'],
    ['schedule', `${ofSpecialist}/schedule`, [{ weekday: 9, startMinute: 0, endMinute: 60 }], 400, 'INVALID_SCHEDULE_INTERVAL'],
    ['schedule', `${ofSpecialist}/schedule`, [{ weekday: 1, startMinute: 0, endMinute: 60 }, { weekday: 1, startMinute: 30, endMinute: 90 }], 400, 'SCHEDULE_OVERLAP'],
    ['exceptions', `${ofSpecialist}/exceptions`, { date: isoDate(1), kind: 'VACATION' }, 400, 'INVALID_EXCEPTION_KIND'],
    ['exceptions', `${ofSpecialist}/exceptions`, { date: isoDate(1), kind: 'SHIFT' }, 400, 'INVALID_EXCEPTION_MINUTES'],
    ['exceptions', `${ofSpecialist}/exceptions`, { date: 'soon', kind: 'DAY_OFF' }, 400, 'INVALID_REQUEST_BODY'],
  ];
  for (const [what, url, data, status, code] of cases) {
    const response = await request.put(url, { headers, data });
    expect(response.status(), `${what} ${JSON.stringify(data)}`).toBe(status);
    expect(((await response.json()) as { code: string }).code, `${what} ${JSON.stringify(data)}`).toBe(code);
  }
});

test('deactivating a service or a master takes them off the booking page, their visits stay', async ({ page, request }) => {
  const studio = await readyStudio(request);
  const visit = await bookOverApi(request, studio, studio.service, studio.specialist, 0);
  await openAs(page, studio, 'OWNER');

  await page.goto(`/book/${studio.slug}`);
  await expect(page.getByText('Fade', { exact: true })).toBeVisible();

  await page.goto('/services');
  await page.getByTestId('service-card-Fade').getByRole('button', { name: 'Edit' }).click();
  await toggleOf(page, 'Active').click();
  await expect(page.getByText('An inactive service cannot be booked.', { exact: false })).toBeVisible();
  await screenshotWidths(page, 'catalog-service-inactive-warning');
  await save(page).click();
  await expect(page.getByTestId('service-card-Fade')).toContainText('Inactive');
  const listed = page.waitForResponse((r) => r.url().endsWith(`/tenants/${studio.slug}/services`));
  await page.goto(`/book/${studio.slug}`);
  expect(await (await listed).json()).toEqual([]);
  await expect(page.getByText('Service', { exact: true })).toBeVisible();
  await expect(page.getByText('Fade', { exact: true })).toHaveCount(0);
  expect((await request.get(`${API_URL}/api/public/tenants/${studio.slug}/slots?serviceId=${studio.service}&specialistId=${studio.specialist}&from=${isoDate(1)}&to=${isoDate(2)}`)).status()).toBe(409);
  expect((await visitFacts(request, studio, visit.id)).status).toBe('CONFIRMED');

  await page.goto('/services');
  await page.getByTestId('service-card-Fade').getByRole('button', { name: 'Edit' }).click();
  await toggleOf(page, 'Active').click();
  await save(page).click();
  await expect(page.getByTestId('service-card-Fade')).not.toContainText('Inactive');

  await page.goto('/specialists');
  await page.getByTestId('specialist-card-Tamar').getByRole('button', { name: 'Edit' }).click();
  await toggleOf(page, 'Active').click();
  await expect(page.getByText('An inactive specialist gets no new bookings', { exact: false })).toBeVisible();
  await save(page).click();
  await expect(page.getByTestId('specialist-card-Tamar')).toContainText('Inactive');
  await page.goto(`/book/${studio.slug}`);
  await page.getByText('Fade', { exact: true }).click();
  await expect(page.getByText('No specialist offers this service.')).toBeVisible();
  await expect(page.getByText('Tamar', { exact: true })).toHaveCount(0);
  expect((await request.get(`${API_URL}/api/public/tenants/${studio.slug}/slots?serviceId=${studio.service}&specialistId=${studio.specialist}&from=${isoDate(1)}&to=${isoDate(2)}`)).status()).toBe(409);
  expect((await visitFacts(request, studio, visit.id)).status).toBe('CONFIRMED');
  await page.goto('/calendar');
  await expect(page.getByTestId(`visit-row-${visit.id}`)).toBeVisible();
});

test('editing a master keeps the sign-in link the form does not show', async ({ page, request }) => {
  const studio = newStudio();
  const link = `kc-${studio.id}`;
  await ownerPut(request, studio, '/specialists', specialistBody('Nana', { keycloakUserId: link }));
  await openAs(page, studio, 'OWNER');

  await page.goto('/specialists');
  await page.getByTestId('specialist-card-Nana').getByRole('button', { name: 'Edit' }).click();
  await expect(page.getByText(link)).toHaveCount(0);
  await page.getByLabel('Name').fill('Nana K.');
  await save(page).click();
  await expect(page.getByTestId('specialist-card-Nana K.')).toBeVisible();

  const stored = (await (await request.get(`${API_URL}/api/app/specialists`, { headers: ownerHeaders(studio) })).json()) as { displayName: string; keycloakUserId: string }[];
  expect(stored).toEqual([expect.objectContaining({ displayName: 'Nana K.', keycloakUserId: link })]);
});

test('prices follow the digits of the currency of the tenant, in both directions', async ({ page, request }) => {
  for (const [currency, typed, minor, shown] of [
    ['JPY', '1500', 1500, '1500'],
    ['KWD', '1.234', 1234, '1.234'],
  ] as const) {
    const studio = newStudio(currency);
    await openAs(page, studio, 'OWNER');
    await page.goto('/services');
    await page.getByRole('button', { name: 'Add service' }).click();
    await page.getByLabel('Name').fill('Priced');
    await page.getByLabel(`Price, ${currency}`).fill(typed);
    await save(page).click();
    await expect(page.getByTestId('service-card-Priced')).toBeVisible();
    const services = (await (await request.get(`${API_URL}/api/app/services`, { headers: ownerHeaders(studio) })).json()) as { priceMinor: number }[];
    expect(services.map((s) => s.priceMinor), currency).toEqual([minor]);
    await page.getByTestId('service-card-Priced').getByRole('button', { name: 'Edit' }).click();
    await expect(page.getByLabel(`Price, ${currency}`), currency).toHaveValue(shown);
    await page.unroute(`${API_URL}/api/app/**`);
  }
});

test('the forms and their refusals speak Russian', async ({ page }) => {
  const studio = newStudio();
  await openAs(page, studio, 'OWNER');
  await page.goto('/services');
  await page.getByText('RU', { exact: true }).click();
  await page.getByRole('button', { name: 'Добавить услугу' }).click();
  await page.getByLabel('Название').fill('Стрижка');
  await page.getByLabel('Цена, GEL').fill('12,5');
  await page.getByLabel('Длительность, мин').fill('0');
  await page.getByRole('button', { name: 'Сохранить', exact: true }).click();
  await expect(page.getByTestId('service-form').getByTestId('form-error')).toHaveText('Длительность должна быть от 1 до 1440 минут. (INVALID_DURATION)');
  await screenshotWidths(page, 'catalog-service-form-ru');
});
