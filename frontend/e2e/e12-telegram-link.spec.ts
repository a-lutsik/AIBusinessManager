import { expect, test } from '@playwright/test';
import { bookThroughUi } from './support/bookUi';
import { uniqueName, uniquePhone } from './support/bookings';
import { API_URL } from './support/env';

const STUB = process.env.E2E_TELEGRAM_STUB_URL ?? 'http://127.0.0.1:18090';
const SECRET = 'e2e-webhook-secret';

async function sentTo(chatId: string): Promise<string[]> {
  const sent = (await (await fetch(`${STUB}/__sent`)).json()) as { chat_id: string; text: string }[];
  return sent.filter((m) => m.chat_id === chatId).map((m) => m.text);
}

test('a client links Telegram from the booking-success page and the bot confirms (stub Bot API)', async ({ page, request }) => {
  const phone = uniquePhone();
  await bookThroughUi(page, { service: 'Haircut', specialist: 'Nino Beridze', name: uniqueName('Tg'), localPhone: phone.slice('+995'.length) });

  await page.getByRole('button', { name: 'Get reminders in Telegram' }).click();
  const url = (await page.getByRole('link', { name: 'Open Telegram' }).getAttribute('href')) ?? '';
  expect(url).toMatch(/^https:\/\/t\.me\/cadence_e2e_bot\?start=[A-Za-z0-9_-]+$/);
  const token = new URL(url).searchParams.get('start');

  // What Telegram does: Start, then the contact the person shares. A wrong secret is refused.
  const chatId = String(900_000_000 + Math.floor(Math.random() * 90_000_000));
  const hook = (data: unknown, secret = SECRET) =>
    request.post(`${API_URL}/api/public/telegram/webhook`, { data, headers: { 'X-Telegram-Bot-Api-Secret-Token': secret } });
  const start = { update_id: 1, message: { chat: { id: Number(chatId), type: 'private' }, from: { id: Number(chatId), language_code: 'en' }, text: `/start ${token}` } };
  expect((await hook(start, 'wrong')).status()).toBe(401);
  expect((await hook(start)).status()).toBe(200);
  await expect.poll(async () => (await sentTo(chatId)).join('\n')).toContain('share your contact');
  const contact = { update_id: 2, message: { chat: { id: Number(chatId), type: 'private' }, from: { id: Number(chatId), language_code: 'en' }, contact: { phone_number: phone.slice(1), user_id: Number(chatId) } } };
  expect((await hook(contact)).status()).toBe(200);
  await expect.poll(async () => (await sentTo(chatId)).join('\n')).toContain('Done!');

  // The token is spent: the same link again is answered as expired.
  expect((await hook(start)).status()).toBe(200);
  await expect.poll(async () => (await sentTo(chatId)).join('\n')).toContain('expired');
});
