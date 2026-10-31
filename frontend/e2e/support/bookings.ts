import type { APIRequestContext } from '@playwright/test';
import { API_URL, TENANT_SLUG } from './env';

export type CreatedBooking = {
  id: string;
  status: string;
  serviceStart: string;
  serviceName: string;
  priceMinor: number;
  currencyCode: string;
  accessToken: string;
  managePath: string;
  name: string;
  phone: string;
};

export type BookingOptions = {
  service?: string;
  /** Start of the specialist's display name. */
  specialist?: string;
  /** Index into the free slots of tomorrow..+7 days. */
  slotIndex?: number;
  name?: string;
  phone?: string;
  marketingConsent?: boolean;
};

const PUBLIC_API = `${API_URL}/api/public/tenants/${TENANT_SLUG}`;
let phoneSequence: number = Math.floor(Math.random() * 100_000);

/** +9955550xxxxx, unique within the run. */
export function uniquePhone(): string {
  phoneSequence = (phoneSequence + 1) % 100_000;
  return `+9955550${String(phoneSequence).padStart(5, '0')}`;
}

/**
 * A client name that no other visit of the run shares, so a search by name finds one client. The suffix is letters:
 * a master's search refuses queries with several digits (a phone must not be searchable by a master).
 */
export const uniqueName = (prefix: string): string =>
  `${prefix} ${uniquePhone().slice(-5).replace(/\d/g, (digit) => String.fromCharCode(97 + Number(digit)))}`;

const isoDate = (daysAhead: number): string => new Date(Date.now() + daysAhead * 86_400_000).toISOString().slice(0, 10);

async function getJson<T>(request: APIRequestContext, url: string): Promise<T> {
  const response = await request.get(url);
  if (!response.ok()) throw new Error(`GET ${url} -> ${response.status()}`);
  return (await response.json()) as T;
}

type FreeSlots = { serviceId: string; specialistId: string; slots: string[] };

/** The free slots of tomorrow..+7 days of a service with a specialist, ISO instants in ascending order. */
export async function freeSlots(request: APIRequestContext, service: string, specialist: string): Promise<FreeSlots> {
  const services = await getJson<{ id: string; name: string }[]>(request, `${PUBLIC_API}/services`);
  const serviceId = services.find((s) => s.name === service)?.id;
  if (!serviceId) throw new Error(`Service "${service}" is not in the demo catalog`);
  const specialists = await getJson<{ id: string; displayName: string }[]>(request, `${PUBLIC_API}/services/${serviceId}/specialists`);
  const specialistId = specialists.find((s) => s.displayName.startsWith(specialist))?.id;
  if (!specialistId) throw new Error(`Specialist "${specialist}" does not offer "${service}"`);
  const slots = await getJson<string[]>(
    request,
    `${PUBLIC_API}/slots?serviceId=${serviceId}&specialistId=${specialistId}&from=${isoDate(1)}&to=${isoDate(7)}`
  );
  return { serviceId, specialistId, slots };
}

/** Books a visit through the public API, like a client would; the scenarios never touch the seeded visits. */
export async function createBooking(request: APIRequestContext, options: BookingOptions = {}): Promise<CreatedBooking> {
  const { service = 'Haircut', specialist = 'Nino', slotIndex = 0, name = 'E2E Client', phone = uniquePhone() } = options;
  const { serviceId, specialistId, slots } = await freeSlots(request, service, specialist);
  if (slotIndex >= slots.length) throw new Error(`Only ${slots.length} free slots for ${service} with ${specialist}, wanted index ${slotIndex}`);

  const response = await request.post(`${PUBLIC_API}/bookings`, {
    data: {
      serviceId,
      specialistId,
      serviceStart: slots[slotIndex],
      phone,
      name,
      locale: 'en',
      marketingConsent: options.marketingConsent ?? false,
      countryCode: 'GE',
    },
  });
  if (!response.ok()) throw new Error(`POST ${PUBLIC_API}/bookings -> ${response.status()}: ${await response.text()}`);
  return { ...((await response.json()) as Omit<CreatedBooking, 'name' | 'phone'>), name, phone };
}
