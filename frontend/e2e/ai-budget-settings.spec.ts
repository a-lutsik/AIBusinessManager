import { expect, test } from '@playwright/test';
import type { AiSettingsView, DraftAction } from '../src/api/client';
import { OWNER_HEADERS, decide, draftBooking, freeHaircutSlot, openAi, rejectOpenDrafts, sendMessage } from './support/ai';
import { createBooking, uniqueName } from './support/bookings';
import { API_URL, TENANT_ID } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';
import { sql } from './support/sql';

test.beforeEach(async ({ page }) => {
  await useRole(page, 'OWNER');
});

test.afterEach(async ({ request }) => {
  sql(`UPDATE ai_tenant_settings SET monthly_token_budget = NULL WHERE tenant_id = '${TENANT_ID}'`);
  const settings = await request.put(`${API_URL}/api/app/ai/settings`, { headers: OWNER_HEADERS, data: { notesToLlm: false } });
  expect(settings.ok()).toBe(true);
  await rejectOpenDrafts(request);
});

test('AI-6 a spent budget answers 429 with a banner, while confirming a draft and the calendar still work', async ({ page, request }) => {
  const client = await createBooking(request, { name: uniqueName('Cobalt Reyes') });
  await openAi(page);
  const draft = await draftBooking(page, client.name, await freeHaircutSlot(request, 3));

  sql(
    `INSERT INTO ai_tenant_settings (tenant_id, monthly_token_budget, updated_at) VALUES ('${TENANT_ID}', 0, now()) ` +
      'ON CONFLICT (tenant_id) DO UPDATE SET monthly_token_budget = 0, updated_at = now()'
  );
  const response = await sendMessage(page, '>>text hi again');
  expect(response.status()).toBe(429);
  expect(((await response.json()) as { code: string }).code).toBe('AI_BUDGET_EXCEEDED');
  const banner = page.getByRole('alert');
  await expect(banner).toContainText('The monthly AI budget is spent');
  await expect(banner).toContainText('of 0 tokens');
  await screenshotWidths(page, 'ai-budget-banner');

  const confirm = await decide(page, draft.id, 'confirm');
  expect(confirm.status()).toBe(200);
  const confirmed = (await confirm.json()) as DraftAction;
  await expect(page.getByTestId(`draft-card-${draft.id}`)).toContainText('Confirmed');

  await page.goto('/calendar');
  await expect(page.getByTestId(`visit-row-${confirmed.appliedEntityId}`)).toBeVisible();
});

test('AI-7 the notes switch is saved and the masking warning is shown', async ({ page }) => {
  await openAi(page);
  const panel = page.getByText('AI settings', { exact: true });
  const warning = page.getByText(/^Automatic masking can miss sensitive data/);
  const notes = page.getByRole('switch');
  await expect(panel).toBeVisible();
  await expect(warning).toBeVisible();
  await expect(notes).not.toBeChecked();
  await screenshotWidths(page, 'ai-settings');

  const saved = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/app/ai/settings' && r.request().method() === 'PUT');
  await notes.click();
  expect(((await (await saved).json()) as AiSettingsView).notesToLlm).toBe(true);
  await expect(notes).toBeChecked();

  await page.reload();
  await expect(notes).toBeChecked();
  await expect(warning).toBeVisible();
});
