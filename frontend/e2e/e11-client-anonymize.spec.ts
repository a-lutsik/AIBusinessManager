import { expect, test } from '@playwright/test';
import { cancelVisit, mergeClients, sellPackage, visitOf } from './support/app';
import { createBooking, uniqueName } from './support/bookings';
import { API_URL, NINO_ID, TENANT_ID } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

const ACTION = 'Anonymize client';
const CONFIRM = 'Anonymize permanently';

test('the owner anonymizes a client on request: asked once, refused while a visit is upcoming, name and phone gone', async ({ page, request }) => {
  const name = uniqueName('Erased Client');
  const visit = await createBooking(request, { name });
  const { clientId } = await visitOf(request, visit.id);
  await sellPackage(request, clientId, 3);
  await sellPackage(request, clientId, 2);
  await useRole(page, 'OWNER');
  await page.goto(`/clients/${clientId}`);
  await expect(page.getByText(name, { exact: true }).first()).toBeVisible();
  await expect(page.getByText(visit.phone, { exact: true })).toBeVisible();

  // Nothing is sent before the explicit confirmation, and it can be called off.
  await page.getByRole('button', { name: ACTION }).click();
  const confirm = page.getByTestId('anonymize-confirm');
  await expect(confirm).toContainText('This cannot be undone.');
  await expect(page.getByTestId('anonymize-packages')).toHaveText('5 unused package sessions will be lost.');
  await screenshotWidths(page, 'client-anonymize-confirm');
  await confirm.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(confirm).toHaveCount(0);
  await expect(page.getByText(visit.phone, { exact: true })).toBeVisible();

  // The upcoming visit stops it, and the card says what to do.
  await page.getByRole('button', { name: ACTION }).click();
  await confirm.getByRole('button', { name: CONFIRM }).click();
  await expect(page.getByRole('alert')).toHaveText('This client has upcoming visits. Cancel them first, then anonymize.');
  await expect(page.getByText(visit.phone, { exact: true })).toBeVisible();

  await cancelVisit(request, visit.id);
  await confirm.getByRole('button', { name: CONFIRM }).click();

  await expect(page.getByText('Anonymized client', { exact: true })).toBeVisible();
  await expect(page.getByText(/erased on request/)).toBeVisible();
  const card = page.locator('body');
  await expect(card).not.toContainText(name);
  await expect(card).not.toContainText(visit.phone);
  await expect(card).not.toContainText('anon-');
  await expect(page.getByRole('button', { name: ACTION })).toHaveCount(0);
  await expect(page.getByText(`${visit.serviceName} · Cancelled by business`), 'the visit fact stays').toBeVisible();
  await screenshotWidths(page, 'client-anonymized');

  // Gone from the search, the manage link is dead, and the calendar names nobody.
  await page.goto('/clients');
  await page.getByPlaceholder('Search').last().fill(name);
  await expect(page.getByText(name, { exact: true })).toHaveCount(0);
  const link = await request.get(`${API_URL}/api/public/bookings/${visit.id}?token=${visit.accessToken}`);
  expect(link.status()).toBe(404);
  await page.goto('/calendar');
  const row = page.getByTestId(`visit-row-${visit.id}`);
  await expect(row).toContainText('Anonymized client');
  await row.click();
  const panel = page.getByTestId('visit-panel');
  await expect(panel).not.toContainText(name);
  await expect(panel).not.toContainText(visit.phone);
});

test('MASTER has no such action and the API refuses it with 403', async ({ page, request }) => {
  const name = uniqueName('Kept Client');
  const visit = await createBooking(request, { name });
  const { clientId } = await visitOf(request, visit.id);
  await useRole(page, 'MASTER');
  await page.goto(`/clients/${clientId}`);
  await expect(page.getByText(name, { exact: true }).first()).toBeVisible();
  await expect(page.getByRole('button', { name: ACTION })).toHaveCount(0);

  const refused = await request.post(`${API_URL}/api/app/clients/${clientId}/anonymize`, {
    headers: { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'MASTER', 'X-Specialist-Id': NINO_ID },
  });

  expect(refused.status()).toBe(403);
  expect(await refused.json()).toMatchObject({ code: 'FORBIDDEN_RESOURCE' });
  await page.reload();
  await expect(page.getByText(name, { exact: true }).first()).toBeVisible();
});

test('the card goes by what happened to the profile, not by its name: a person called "anonymized", a merged profile', async ({ page, request }) => {
  const called = await createBooking(request, { name: 'anonymized' });
  const calledId = (await visitOf(request, called.id)).clientId;
  await useRole(page, 'OWNER');
  await page.goto(`/clients/${calledId}`);
  await expect(page.getByText(called.phone, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: ACTION }).click();
  await expect(page.getByTestId('anonymize-confirm')).toBeVisible();
  await expect(page.getByTestId('anonymize-packages'), 'no packages, no warning').toHaveCount(0);

  const survivor = (await visitOf(request, (await createBooking(request, { name: uniqueName('Survivor') })).id)).clientId;
  const absorbed = (await visitOf(request, (await createBooking(request, { name: uniqueName('Absorbed'), slotIndex: 1 })).id)).clientId;
  await mergeClients(request, survivor, absorbed);
  await page.goto(`/clients/${absorbed}`);
  await expect(page.getByText('Merged client', { exact: true })).toBeVisible();
  await expect(page.getByText(/merged into another client/)).toBeVisible();
  await expect(page.getByText('Anonymized client', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: ACTION })).toHaveCount(0);
});
