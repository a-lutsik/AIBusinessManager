import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { CONVERSATION_KEY, OWNER_HEADERS, openAi, say } from './support/ai';
import { createBooking, uniqueName } from './support/bookings';
import { API_URL } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

const REF = /\b(CLIENT|MASTER|VISIT|SERVICE|DRAFT)_\d+\b/;
const UUID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;

async function expectNoInternals(page: Page): Promise<void> {
  await expect(page.locator('body')).not.toContainText(REF);
  await expect(page.locator('body')).not.toContainText(UUID);
}

/** A past day of the seed with visits, and the names of their clients. */
async function seedDay(request: APIRequestContext): Promise<{ day: string; clients: string[] }> {
  for (let daysAgo = 30; daysAgo < 60; daysAgo += 1) {
    const day = new Date(Date.now() - daysAgo * 86_400_000).toLocaleDateString('sv-SE', { timeZone: 'Asia/Tbilisi' });
    const from = new Date(`${day}T00:00:00+04:00`).toISOString();
    const to = new Date(`${day}T23:59:59+04:00`).toISOString();
    const response = await request.get(`${API_URL}/api/app/calendar?from=${from}&to=${to}`, { headers: OWNER_HEADERS });
    const visits = (await response.json()) as { clientDisplayName: string }[];
    if (visits.length > 0) return { day, clients: visits.map((visit) => visit.clientDisplayName) };
  }
  throw new Error('The seed has no visit on a day 30..59 days ago');
}

test.beforeEach(async ({ page }) => {
  await useRole(page, 'OWNER');
});

test('AI-1 a chat turn answers, remembers the conversation and shows real names after a reload', async ({ page, request }) => {
  const client = await createBooking(request, { name: uniqueName('Chat Visitor') });
  await openAi(page);

  const reply = `Please call ${client.name} and ask Nino Beridze to review the plan`;
  const { conversationId } = await say(page, `>>text ${reply}`);
  await expect(page.getByText(reply, { exact: true })).toBeVisible();
  expect(await page.evaluate((key) => localStorage.getItem(key), CONVERSATION_KEY)).toBe(conversationId);
  await expectNoInternals(page);

  await page.reload();
  await expect(page.getByText(`>>text ${reply}`, { exact: true })).toBeVisible();
  await expect(page.getByText(reply, { exact: true })).toBeVisible();
  await expectNoInternals(page);
  await screenshotWidths(page, 'ai-chat');
});

test('AI-2 get_schedule of a seed day answers with the real client names', async ({ page, request }) => {
  const { day, clients } = await seedDay(request);
  await openAi(page);

  const { reply } = await say(page, `>>tool get_schedule ${JSON.stringify({ dateFrom: day, dateTo: day })}`);
  expect(clients.some((name) => reply.includes(name)), `${reply} names none of ${clients.join(', ')}`).toBe(true);
  await expect(page.getByText(/^Tool results \(stub model\): get_schedule/)).toBeVisible();
  await expectNoInternals(page);
});
