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

test('a client links Telegram from the booking-success page and the bot confirms (stub Bot API)', async ({ page, context, request }) => {
  await context.route('https://t.me/**', (route) => route.fulfill({ status: 200, contentType: 'text/html', body: 'bot' }));
  const phone = uniquePhone();
  await bookThroughUi(page, { service: 'Haircut', specialist: 'Nino Beridze', name: uniqueName('Tg'), localPhone: phone.slice('+995'.length) });

  const popup = context.waitForEvent('page');
  await page.getByRole('button', { name: 'Get reminders in Telegram' }).click();
  const url = (await popup).url();
  expect(url).toMatch(/^https:\/\/t\.me\/cadence_e2e_bot\?start=[A-Za-z0-9_-]+$/);
  const token = new URL(url).searchParams.get('start');

  // What Telegram does when the client presses Start; a wrong secret is refused.
  const chatId = String(900_000_000 + Math.floor(Math.random() * 90_000_000));
  const update = { update_id: 1, message: { chat: { id: Number(chatId), type: 'private' }, from: { language_code: 'en' }, text: `/start ${token}` } };
  const refused = await request.post(`${API_URL}/api/public/telegram/webhook`, { data: update, headers: { 'X-Telegram-Bot-Api-Secret-Token': 'wrong' } });
  expect(refused.status()).toBe(401);
  const accepted = await request.post(`${API_URL}/api/public/telegram/webhook`, { data: update, headers: { 'X-Telegram-Bot-Api-Secret-Token': SECRET } });
  expect(accepted.status()).toBe(200);
  await expect.poll(async () => (await sentTo(chatId)).join('\n')).toContain('Done!');

  // The token is one-time: the same link again binds nothing and is answered as expired.
  await request.post(`${API_URL}/api/public/telegram/webhook`, { data: update, headers: { 'X-Telegram-Bot-Api-Secret-Token': SECRET } });
  await expect.poll(async () => (await sentTo(chatId)).join('\n')).toContain('expired');
});
