import { expect, type APIRequestContext, type Page, type Response } from '@playwright/test';
import type { AiChatResponse, DraftAction } from '../../src/api/client';
import { freeSlots } from './bookings';
import { API_URL, NINO_ID, TENANT_ID } from './env';
import { sql } from './sql';

export const OWNER_HEADERS = { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'OWNER' };
export const MASTER_HEADERS = { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'MASTER', 'X-Specialist-Id': NINO_ID };

export const CONVERSATION_KEY = 'cadence.aiConversationId';
const INPUT_PLACEHOLDER = 'Ask about today, no-shows, or a draft cancel';

/** Opens the AI screen; the test starts a new conversation, its browser context has no stored one. */
export async function openAi(page: Page): Promise<void> {
  await page.goto('/ai');
  await page.getByPlaceholder(INPUT_PLACEHOLDER).waitFor();
}

/** Types the message, presses Send and returns the chat response. */
export async function sendMessage(page: Page, text: string): Promise<Response> {
  const response = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/app/ai/chat' && r.request().method() === 'POST');
  await page.getByPlaceholder(INPUT_PLACEHOLDER).fill(text);
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  return response;
}

/** One successful turn. Waits until the screen has taken the answer: its late reset of the input would eat the next message. */
export async function say(page: Page, text: string): Promise<AiChatResponse> {
  const response = await sendMessage(page, text);
  expect(response.status(), await response.text()).toBe(200);
  await expect(page.getByPlaceholder(INPUT_PLACEHOLDER)).toHaveValue('');
  await expect(page.getByRole('button', { name: 'Send', exact: true })).toBeEnabled();
  return (await response.json()) as AiChatResponse;
}

type RefKind = 'CLIENT' | 'MASTER' | 'VISIT' | 'SERVICE';

/**
 * The ref (CLIENT_n, MASTER_n, ...) the conversation gave an entity. The refs never reach the screen, the owner sees names,
 * so the numbers come from the conversation's own map in the database.
 */
export function refOf(conversationId: string, kind: RefKind, by: { display: string } | { entityId: string }): string {
  const filter = 'display' in by ? `display LIKE '${by.display}%'` : `entity_id = '${by.entityId}'`;
  const token = sql(`SELECT token FROM ai_pseudonym WHERE conversation_id = '${conversationId}' AND kind = '${kind}' AND ${filter}`);
  if (!/^[A-Z]+_\d+$/.test(token)) throw new Error(`Expected one ${kind} ref of the conversation for ${JSON.stringify(by)}, got "${token}"`);
  return token;
}

/** Tenant-local `YYYY-MM-DDTHH:MM` of an instant, the form the draft tools take. */
export const localTime = (iso: string): string => new Date(iso).toLocaleString('sv-SE', { timeZone: 'Asia/Tbilisi' }).slice(0, 16).replace(' ', 'T');

/** The `n`-th free Haircut slot of Nino (a local time), counted after the visits already booked. */
export async function freeHaircutSlot(request: APIRequestContext, n: number): Promise<string> {
  const { slots } = await freeSlots(request, 'Haircut', 'Nino');
  if (n >= slots.length) throw new Error(`Only ${slots.length} free Haircut slots, wanted index ${n}`);
  return localTime(slots[n]);
}

/**
 * A draft_booking turn through the stub: a warm-up turn first, because the stub's tokens only stay refs once the
 * conversation knows them, and the names in the warm-up message register the client and the master. The client's name
 * must not share a word with a directive or a tool name: the masker turns "draft_booking" into a ref of a client named
 * "Draft Booker", and the stub then calls no tool.
 */
export async function draftBooking(page: Page, clientName: string, start: string): Promise<DraftAction> {
  const { conversationId } = await say(page, `>>text Booking for ${clientName} with Nino Beridze`);
  const args = {
    clientRef: refOf(conversationId, 'CLIENT', { display: clientName }),
    masterRef: refOf(conversationId, 'MASTER', { display: 'Nino' }),
    serviceRef: refOf(conversationId, 'SERVICE', { display: 'Haircut' }),
    start,
  };
  const { drafts } = await say(page, `>>tool draft_booking ${JSON.stringify(args)}`);
  expect(drafts).toHaveLength(1);
  return drafts[0];
}

/** A tool turn about one visit (get_schedule of its day registers its VISIT ref), then the draft tool on it. */
export async function draftForVisit(
  page: Page,
  visit: { id: string; serviceStart: string },
  tool: 'draft_reschedule' | 'draft_cancel',
  args: Record<string, string>
): Promise<DraftAction> {
  const day = localTime(visit.serviceStart).slice(0, 10);
  const { conversationId } = await say(page, `>>tool get_schedule ${JSON.stringify({ dateFrom: day, dateTo: day })}`);
  const visitRef = refOf(conversationId, 'VISIT', { entityId: visit.id });
  const { drafts } = await say(page, `>>tool ${tool} ${JSON.stringify({ visitRef, ...args })}`);
  expect(drafts).toHaveLength(1);
  return drafts[0];
}

/** Presses Confirm or Reject on the draft's card; returns the response of the request. */
export async function decide(page: Page, draftId: string, decision: 'confirm' | 'reject'): Promise<Response> {
  const response = page.waitForResponse((r) => r.url().endsWith(`/api/app/ai/drafts/${draftId}/${decision}`));
  await page.getByTestId(`draft-card-${draftId}`).getByRole('button', { name: decision === 'confirm' ? 'Confirm' : 'Reject', exact: true }).click();
  return response;
}

/** Ids of the visits from now to two weeks ahead, as the owner API lists them. */
export async function calendarIds(request: APIRequestContext): Promise<string[]> {
  const from = new Date(Date.now() - 3_600_000).toISOString();
  const to = new Date(Date.now() + 14 * 86_400_000).toISOString();
  const response = await request.get(`${API_URL}/api/app/calendar?from=${from}&to=${to}`, { headers: OWNER_HEADERS });
  expect(response.ok()).toBe(true);
  return ((await response.json()) as { id: string }[]).map((visit) => visit.id).sort();
}

/** Leaves no open draft behind: every scenario starts with a screen that lists only its own cards. */
export async function rejectOpenDrafts(request: APIRequestContext): Promise<void> {
  const open = await request.get(`${API_URL}/api/app/ai/drafts?status=PENDING`, { headers: OWNER_HEADERS });
  for (const draft of (await open.json()) as DraftAction[]) {
    await request.post(`${API_URL}/api/app/ai/drafts/${draft.id}/reject`, { headers: OWNER_HEADERS });
  }
}
