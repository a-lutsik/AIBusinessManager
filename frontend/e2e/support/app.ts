import type { APIRequestContext } from '@playwright/test';
import type { BookingRulesView, OwnerTaskView } from '../../src/api/client';
import { API_URL, TENANT_ID } from './env';

const OWNER_HEADERS = { 'X-Tenant-Id': TENANT_ID, 'X-Actor-Role': 'OWNER' };

async function ownerJson<T>(request: APIRequestContext, method: 'get' | 'put', path: string, data?: unknown): Promise<T> {
  const response = await request[method](`${API_URL}/api/app${path}`, { headers: OWNER_HEADERS, data });
  if (!response.ok()) throw new Error(`${method.toUpperCase()} /api/app${path} -> ${response.status()}: ${await response.text()}`);
  return (await response.json()) as T;
}

export const getRules = (request: APIRequestContext): Promise<BookingRulesView> => ownerJson(request, 'get', '/rules');

export async function putRules(request: APIRequestContext, rules: BookingRulesView): Promise<void> {
  await ownerJson(request, 'put', '/rules', rules);
}

/** The open owner task that the booking of this visit created ("new booking"). */
export async function taskOfVisit(request: APIRequestContext, appointmentId: string): Promise<OwnerTaskView> {
  const tasks = await ownerJson<OwnerTaskView[]>(request, 'get', '/tasks');
  const task = tasks.find((candidate) => candidate.appointmentId === appointmentId);
  if (!task) throw new Error(`No open owner task for visit ${appointmentId}`);
  return task;
}

type VisitFacts = { status: string; serviceStart: string; clientId: string };

/** Status and start of a visit as the owner API reports them. */
export async function visitOf(request: APIRequestContext, appointmentId: string): Promise<VisitFacts> {
  const detail = await ownerJson<{ appointment: VisitFacts }>(request, 'get', `/appointments/${appointmentId}`);
  return detail.appointment;
}
