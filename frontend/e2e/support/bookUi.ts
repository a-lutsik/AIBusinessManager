import type { Page } from '@playwright/test';
import { TENANT_SLUG } from './env';

export type PublicBooking = {
  id: string;
  status: string;
  serviceStart: string;
  accessToken: string;
  managePath: string;
};

export type BookStep = 'service' | 'specialist' | 'slot' | 'contact';

export type UiBooking = {
  /** The free slots the page asked for, ISO instants. */
  slots: string[];
  /** The body the page sent to POST .../bookings. */
  request: { phone: string; countryCode: string; name: string; marketingConsent: boolean };
  booking: PublicBooking;
};

/** A slot button in the reschedule lists: date and time without seconds, as src/utils/datetime.ts writes them in en. */
export const SLOT_LABEL = /^[A-Z][a-z]{2}, [A-Z][a-z]{2} \d{1,2}, \d{4}, \d{2}:\d{2}\s[AP]M$/;
/** A slot button on /book: the day is chosen in the row of days above, the button shows the time. */
export const TIME_LABEL = /^\d{2}:\d{2}\s[AP]M$/;
/** A button of the row of days on /book. */
export const DAY_LABEL = /^[A-Z][a-z]{2}, [A-Z][a-z]{2} \d{1,2}$/;

export type BookOptions = {
  service: string;
  specialist: string;
  name: string;
  /** Typed without the country code, as a client would: the page adds the preselected +995. */
  localPhone: string;
  /** The tenant of the page; the demo tenant when not given. */
  slug?: string;
  /** Called when a step is on screen, before the page moves on. */
  at?: (step: BookStep) => Promise<void>;
};

/** Walks /book/lumen-studio as a client: service, specialist, first slot, contact data, consent, Book. */
export async function bookThroughUi(page: Page, options: BookOptions): Promise<UiBooking> {
  const { service, specialist, name, localPhone, at, slug = TENANT_SLUG } = options;
  await page.goto(`/book/${slug}`);
  await page.getByText(service, { exact: true }).waitFor();
  await at?.('service');

  await page.getByText(service, { exact: true }).click();
  await page.getByText(specialist, { exact: true }).waitFor();
  await at?.('specialist');

  const slotsResponse = page.waitForResponse((r) => r.url().includes('/slots?') && r.ok());
  await page.getByText(specialist, { exact: true }).click();
  const slots = (await (await slotsResponse).json()) as string[];
  await page.getByRole('button', { name: TIME_LABEL }).first().waitFor();
  await at?.('slot');

  await page.getByRole('button', { name: TIME_LABEL }).first().click();
  await page.getByPlaceholder('Name').fill(name);
  await page.getByPlaceholder('Phone (GE)').fill(localPhone);
  await page.getByText('I agree to receive visit messages').click();
  await at?.('contact');

  const bookResponse = page.waitForResponse((r) => r.url().endsWith(`/tenants/${slug}/bookings`) && r.request().method() === 'POST');
  await page.getByRole('button', { name: 'Book', exact: true }).click();
  const response = await bookResponse;
  return {
    slots,
    request: response.request().postDataJSON() as UiBooking['request'],
    booking: (await response.json()) as PublicBooking,
  };
}
