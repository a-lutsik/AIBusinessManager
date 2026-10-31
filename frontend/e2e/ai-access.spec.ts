import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';
import { CONVERSATION_KEY, MASTER_HEADERS, OWNER_HEADERS, openAi, say } from './support/ai';
import { API_URL } from './support/env';
import { useRole } from './support/role';
import { screenshotWidths } from './support/screenshots';

const AI_API = `${API_URL}/api/app/ai`;

test('AI-8 a master gets 403 on all seven AI endpoints', async ({ request }) => {
  const id = randomUUID();
  const calls: [string, string, unknown?][] = [
    ['post', '/chat', { message: 'hi', locale: 'en' }],
    ['get', `/conversations/${id}`],
    ['get', '/drafts'],
    ['post', `/drafts/${id}/confirm`],
    ['post', `/drafts/${id}/reject`],
    ['get', '/settings'],
    ['put', '/settings', { notesToLlm: true }],
  ];
  for (const [method, path, data] of calls) {
    const response = await request.fetch(`${AI_API}${path}`, { method, headers: MASTER_HEADERS, data });
    expect(response.status(), `${method.toUpperCase()} ${path}`).toBe(403);
  }
});

test('AI-8 a message of 4001 characters is refused with AI_MESSAGE_TOO_LONG', async ({ request }) => {
  const response = await request.post(`${AI_API}/chat`, { headers: OWNER_HEADERS, data: { message: 'a'.repeat(4001), locale: 'en' } });
  expect(response.status()).toBe(400);
  expect(((await response.json()) as { code: string }).code).toBe('AI_MESSAGE_TOO_LONG');
});

test('AI-8 an unknown conversation id is a 404 and the screen starts a fresh conversation', async ({ page, request }) => {
  const missing = randomUUID();
  const api = await request.get(`${AI_API}/conversations/${missing}`, { headers: OWNER_HEADERS });
  expect(api.status()).toBe(404);
  expect(((await api.json()) as { code: string }).code).toBe('CONVERSATION_NOT_FOUND');

  await page.addInitScript(([key, id]) => localStorage.setItem(key, id), [CONVERSATION_KEY, missing]);
  await useRole(page, 'OWNER');
  const lookup = page.waitForResponse((r) => new URL(r.url()).pathname === `/api/app/ai/conversations/${missing}`);
  await openAi(page);
  expect((await lookup).status()).toBe(404);
  await expect(page.getByText('No conversation yet')).toBeVisible();
  await expect.poll(() => page.evaluate((key) => localStorage.getItem(key), CONVERSATION_KEY)).toBeNull();
  await expect(page.getByText(/not found/i)).toHaveCount(0);

  const { conversationId } = await say(page, '>>text hello again');
  expect(conversationId).not.toBe(missing);
  await expect(page.getByText('hello again', { exact: true })).toBeVisible();
});

test('AI-8 /ai shows a master the owner-only screen', async ({ page }) => {
  await useRole(page, 'MASTER');
  await page.goto('/ai');
  await expect(page.getByText('The AI assistant is available to the owner only.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Send', exact: true })).toHaveCount(0);
  await screenshotWidths(page, 'ai-owner-only');
});
