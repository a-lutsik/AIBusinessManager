import { expect, test } from '@playwright/test';
import type { DraftAction } from '../src/api/client';
import { calendarIds, decide, draftBooking, draftForVisit, freeHaircutSlot, localTime, openAi, rejectOpenDrafts } from './support/ai';
import { visitOf } from './support/app';
import { createBooking, uniqueName } from './support/bookings';
import { SLOT_LABEL } from './support/bookUi';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

test.beforeEach(async ({ page }) => {
  // Several chat turns per test: 25 s on a loaded machine (AI-5), over the default 30 s once (AI-4).
  test.slow();
  await useRole(page, 'OWNER');
});

test.afterEach(async ({ request }) => {
  await rejectOpenDrafts(request);
});

test('AI-3 a booking draft changes nothing until Confirm, then the visit is in the calendar', async ({ page, request }) => {
  const client = await createBooking(request, { name: uniqueName('Quill Penwright') });
  const visitsBefore = await calendarIds(request);
  await openAi(page);

  const draft = await draftBooking(page, client.name, await freeHaircutSlot(request, 3));
  const card = page.getByTestId(`draft-card-${draft.id}`);
  await expect(card).toContainText('New booking');
  await expect(card).toContainText('Awaiting decision');
  await expect(card).toContainText(client.name);
  await expect(card).toContainText('Nino Beridze');
  await expect(card).toContainText('Haircut');
  expect(await calendarIds(request)).toEqual(visitsBefore);
  await screenshotWidths(page, 'ai-draft-pending');

  const response = await decide(page, draft.id, 'confirm');
  expect(response.status()).toBe(200);
  const confirmed = (await response.json()) as DraftAction;
  expect(confirmed.status).toBe('CONFIRMED');
  await expect(card).toContainText('Confirmed');
  await expect(card.getByRole('button')).toHaveCount(0);
  await screenshotWidths(page, 'ai-draft-confirmed');

  await page.goto('/calendar');
  const row = page.getByTestId(`visit-row-${confirmed.appliedEntityId}`);
  await expect(row).toBeVisible();
  await expect(row).toContainText(`Haircut · ${client.name}`);
});

test('AI-4 rejecting the second card rejects only it and changes nothing in the calendar', async ({ page, request }) => {
  const client = await createBooking(request, { name: uniqueName('Maple Tarnow') });
  const visitsBefore = await calendarIds(request);
  await openAi(page);

  const first = await draftBooking(page, client.name, await freeHaircutSlot(request, 3));
  const second = await draftBooking(page, client.name, await freeHaircutSlot(request, 6));
  const firstCard = page.getByTestId(`draft-card-${first.id}`);
  const secondCard = page.getByTestId(`draft-card-${second.id}`);
  await expect(firstCard).toContainText('Awaiting decision');
  await expect(secondCard).toContainText('Awaiting decision');

  const response = await decide(page, second.id, 'reject');
  expect(response.status()).toBe(200);
  await expect(secondCard).toContainText('Rejected');
  await expect(secondCard.getByRole('button')).toHaveCount(0);
  await expect(firstCard).toContainText('Awaiting decision');
  await expect(firstCard.getByRole('button', { name: 'Confirm', exact: true })).toBeVisible();
  expect(await calendarIds(request)).toEqual(visitsBefore);
  await screenshotWidths(page, 'ai-draft-rejected');
});

test('AI-5 reschedule and cancel drafts move and cancel the visit on Confirm', async ({ page, request }) => {
  const visit = await createBooking(request, { name: uniqueName('Orchid Vance') });
  const newStart = await freeHaircutSlot(request, 3);
  await openAi(page);

  const reschedule = await draftForVisit(page, visit, 'draft_reschedule', { newStart });
  const rescheduleCard = page.getByTestId(`draft-card-${reschedule.id}`);
  await expect(rescheduleCard).toContainText('Reschedule a visit');
  await expect(rescheduleCard).toContainText(visit.name);
  expect(localTime((await visitOf(request, visit.id)).serviceStart)).toBe(localTime(visit.serviceStart));
  expect((await decide(page, reschedule.id, 'confirm')).status()).toBe(200);
  await expect(rescheduleCard).toContainText('Confirmed');
  expect(localTime((await visitOf(request, visit.id)).serviceStart)).toBe(newStart);

  const reason = 'Owner is away that day';
  const moved = { id: visit.id, ...(await visitOf(request, visit.id)) };
  const cancel = await draftForVisit(page, moved, 'draft_cancel', { initiatedBy: 'business', reason });
  const cancelCard = page.getByTestId(`draft-card-${cancel.id}`);
  await expect(cancelCard).toContainText('Cancel a visit');
  await expect(cancelCard).toContainText(reason);
  expect((await visitOf(request, visit.id)).status).not.toBe('CANCELLED_BY_BUSINESS');
  expect((await decide(page, cancel.id, 'confirm')).status()).toBe(200);
  await expect(cancelCard).toContainText('Confirmed');
  expect((await visitOf(request, visit.id)).status).toBe('CANCELLED_BY_BUSINESS');
});

test('AI-5 a draft turns FAILED with the translated DRAFT_STALE when the visit moved meanwhile', async ({ page, request }) => {
  const visit = await createBooking(request, { name: uniqueName('Heron Kell') });
  await openAi(page);
  const cancel = await draftForVisit(page, visit, 'draft_cancel', { initiatedBy: 'business' });
  const card = page.getByTestId(`draft-card-${cancel.id}`);
  await expect(card).toContainText('Awaiting decision');

  // The owner moves the visit in the calendar before confirming the draft.
  await page.goto('/calendar');
  await page.getByTestId(`visit-row-${visit.id}`).click();
  await page.getByTestId('visit-panel').getByRole('button', { name: 'Reschedule' }).click();
  const slots = page.getByRole('button', { name: SLOT_LABEL });
  await expect(slots.first()).toBeVisible();
  await slots.last().click();
  await expect(slots).toHaveCount(0);
  await expect.poll(async () => localTime((await visitOf(request, visit.id)).serviceStart)).not.toBe(localTime(visit.serviceStart));

  await page.goto('/ai');
  await expect(card).toContainText('Awaiting decision');
  const response = await decide(page, cancel.id, 'confirm');
  expect(response.status()).toBe(409);
  expect(((await response.json()) as { code: string }).code).toBe('DRAFT_STALE');
  await expect(card).toContainText('Not applied');
  await expect(card).toContainText('The visit changed after this draft was made, so it was not applied.');
  await expect(card.getByRole('button')).toHaveCount(0);
  expect((await visitOf(request, visit.id)).status).not.toBe('CANCELLED_BY_BUSINESS');
  await screenshotWidths(page, 'ai-draft-failed');
});
