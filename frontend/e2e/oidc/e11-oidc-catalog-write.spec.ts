import { expect, test, type Page, type Request } from '@playwright/test';
import { API_URL, BASE_URL, ISSUER } from '../support/env';
import { realmUser, submitKeycloakLogin } from '../support/keycloak';
import { screenshotWidths } from '../support/screenshots';
import { sql } from '../support/sql';

// E10 runs in dev-headers mode. Here the same writes go through a token Keycloak issued, on the demo tenant of the realm's users;
// the service is deleted afterwards (nothing refers to it), so the other OIDC specs see the demo catalog as it was.
const KEYCLOAK_URL = new RegExp(`^${ISSUER}/protocol/openid-connect/auth\\?`);
const SERVICE = 'OIDC Trim';
const isServicesWrite = (request: Request): boolean => request.method() === 'PUT' && new URL(request.url()).pathname === '/api/app/services';

async function signInAs(page: Page, user: { username: string; password: string }): Promise<void> {
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await page.waitForURL(KEYCLOAK_URL);
  await submitKeycloakLogin(page, user);
  await page.waitForURL(`${BASE_URL}/**`);
}

test.afterEach(() => {
  sql(`DELETE FROM catalog_service WHERE name = '${SERVICE}'`);
});

test('OWNER with a real Keycloak token creates a service and changes its price; the backend records the token subject', async ({ page }) => {
  await page.goto('/services');
  await signInAs(page, realmUser('owner@lumen.studio'));

  await page.getByRole('button', { name: 'Add service' }).click();
  await page.getByLabel('Name').fill(SERVICE);
  await page.getByLabel('Price, GEL').fill('42');
  await page.getByLabel('Duration, min').fill('30');
  const created = page.waitForRequest(isServicesWrite);
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await created).headers().authorization, 'the write carries the bearer token').toMatch(/^Bearer ey/);
  expect((await (await created).response())?.status()).toBe(200);
  const card = page.getByTestId(`service-card-${SERVICE}`);
  await expect(card).toContainText(/GEL\s*42\.00/);

  await card.getByRole('button', { name: 'Edit', exact: true }).click();
  await page.getByLabel('Price, GEL').fill('55,50');
  const edited = page.waitForResponse((response) => isServicesWrite(response.request()));
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  expect((await edited).status()).toBe(200);
  await expect(card).toContainText(/GEL\s*55\.50/);
  await screenshotWidths(page, 'oidc-catalog-owner');

  expect(sql(`SELECT price_minor FROM catalog_service WHERE name = '${SERVICE}'`)).toBe('5550');
  const actor = sql(`SELECT actor_id || ' ' || actor_type FROM audit_log WHERE action = 'service.update' AND after_state LIKE '%"priceMinor":5550,%' ORDER BY created_at DESC LIMIT 1`);
  expect(actor, 'the audit names the Keycloak subject, not a dev-headers actor').toMatch(/^[0-9a-f-]{36} OWNER$/);
});

test('MASTER with a real Keycloak token gets 403 on catalog writes and sees no add button', async ({ page, request }) => {
  await page.goto('/services');
  const me = page.waitForRequest((candidate) => candidate.method() === 'GET' && new URL(candidate.url()).pathname === '/api/app/me');
  await signInAs(page, realmUser('nino@lumen.studio'));
  const authorization = (await me).headers().authorization;
  expect(authorization).toMatch(/^Bearer ey/);
  // The screen is settled once the demo catalog is on it; only then does a missing button mean anything.
  await expect(page.getByTestId('service-card-Haircut')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Add service' })).toHaveCount(0);

  const write = await request.put(`${API_URL}/api/app/services`, {
    headers: { authorization },
    data: { name: SERVICE, durationMinutes: 30, priceMinor: 100, bufferBeforeMinutes: 0, bufferAfterMinutes: 0, color: '#0f766e', active: true, publicVisible: true },
  });

  expect(write.status()).toBe(403);
  expect(await write.json()).toMatchObject({ code: 'FORBIDDEN_RESOURCE' });
  expect(sql(`SELECT count(*) FROM catalog_service WHERE name = '${SERVICE}'`)).toBe('0');
});
