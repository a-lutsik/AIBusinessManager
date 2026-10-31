import { expect, test, type Page } from '@playwright/test';
import { BASE_URL, ISSUER, NINO_ID } from '../support/env';
import { realmUser, submitKeycloakLogin } from '../support/keycloak';
import { screenshotWidths } from '../support/screenshots';

// The signed-in page is settled once the tenant is shown, so the soft checks need no long wait (they must not eat the test timeout).
const SOFT = { timeout: 2_000 };
const SIGN_IN = { name: 'Sign in', exact: true } as const;
const KEYCLOAK_URL = new RegExp(`^${ISSUER}/protocol/openid-connect/auth\\?`);
const OWNER = realmUser('owner@lumen.studio');
const NINO = realmUser('nino@lumen.studio');

/** From the console's sign-in screen through the Keycloak form back to the app. */
async function signInAs(page: Page, user: { username: string; password: string }): Promise<void> {
  await page.getByRole('button', SIGN_IN).click();
  await page.waitForURL(KEYCLOAK_URL);
  await submitKeycloakLogin(page, user);
  await page.waitForURL(`${BASE_URL}/**`);
}

test('OWNER signs in through Keycloak, survives a reload, signs out and is asked for the password again', async ({ page }) => {
  await page.goto('/dashboard');
  await expect(page.getByText('Sign in with your Cadence account to open the console.')).toBeVisible();
  await screenshotWidths(page, 'oidc-sign-in');

  await page.getByRole('button', SIGN_IN).click();
  await page.waitForURL(KEYCLOAK_URL);
  await expect(page.locator('#username')).toBeVisible();
  await screenshotWidths(page, 'oidc-keycloak-login');

  await submitKeycloakLogin(page, OWNER);
  // The session and the tenant come first; where the callback lands is checked softly so the rest of the flow still runs.
  await expect(page.getByText(/^Lumen Studio · /).first()).toBeVisible();
  // The callback route returns to the page that asked for the sign-in: the dashboard.
  await expect.soft(page).toHaveURL(`${BASE_URL}/dashboard`, SOFT);
  await expect.soft(page.getByText('Dashboard', { exact: true }).first()).toBeVisible(SOFT);
  await expect.soft(page.getByTestId('metric-card')).toHaveCount(6, SOFT);

  // A reload restores the session from the refresh token: same page, no Keycloak form.
  const signedInUrl = page.url();
  await page.reload();
  await expect(page).toHaveURL(signedInUrl);
  await expect(page.getByText(/^Lumen Studio · /).first()).toBeVisible();
  await expect(page.getByRole('button', SIGN_IN)).toHaveCount(0);

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('button', SIGN_IN)).toBeVisible();
  await page.goto('/dashboard');
  await expect(page.getByText(/^Lumen Studio · /)).toHaveCount(0);
  await page.getByRole('button', SIGN_IN).click();
  // The SSO session ended with the sign-out: Keycloak asks for the credentials again instead of signing in silently.
  await page.waitForURL(KEYCLOAK_URL);
  await expect(page.locator('#username')).toBeVisible();
  await expect(page.locator('#password')).toBeVisible();
});

test('the screen behind the sign-in is not mounted: no console API request before "Sign in"', async ({ page }) => {
  const appApiCalls: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    if (url.pathname.startsWith('/api/app/')) appApiCalls.push(url.pathname);
  });
  for (const path of ['/dashboard', '/calendar', '/rules', `/specialists/${NINO_ID}`]) {
    await page.goto(path);
    await expect(page.getByRole('button', SIGN_IN)).toBeVisible();
    await page.waitForLoadState('networkidle');
  }
  expect(appApiCalls).toEqual([]);
});

test('the sign-in returns to the page that was requested', async ({ page }) => {
  await page.goto('/calendar');
  await signInAs(page, OWNER);
  await expect(page).toHaveURL(`${BASE_URL}/calendar`);
  await expect(page.getByText('Calendar', { exact: true }).first()).toBeVisible();
});

test('the sign-in keeps the URL of a deep link, query included, and returns to it', async ({ page }) => {
  await page.goto(`/specialists/${NINO_ID}?from=link`);
  await expect(page.getByRole('button', SIGN_IN)).toBeVisible();
  await expect(page).toHaveURL(`${BASE_URL}/specialists/${NINO_ID}?from=link`);
  await signInAs(page, OWNER);
  await expect(page).toHaveURL(`${BASE_URL}/specialists/${NINO_ID}?from=link`);
  await expect(page.getByText('Service matrix', { exact: true })).toBeVisible();
});

test('a direct entry on the root lands on the dashboard after the sign-in', async ({ page }) => {
  await page.goto('/');
  await signInAs(page, OWNER);
  await expect(page).toHaveURL(`${BASE_URL}/dashboard`);
  await expect(page.getByTestId('metric-card')).toHaveCount(6);
});

test('MASTER who asked for the calendar gets the calendar, not an owner-only page', async ({ page }) => {
  await page.goto('/calendar');
  await signInAs(page, NINO);
  await expect(page).toHaveURL(`${BASE_URL}/calendar`);
  await expect(page.getByText('Calendar', { exact: true }).first()).toBeVisible();
  await expect(page.getByText('The AI assistant is available to the owner only.')).toHaveCount(0);
});

test('MASTER signs in and gets the master console: three menu items, role MASTER from /api/app/me', async ({ page }) => {
  await page.goto('/dashboard');
  const me = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/app/me');
  await signInAs(page, NINO);

  const response = await me;
  expect(response.status()).toBe(200);
  expect(await response.json()).toMatchObject({ role: 'MASTER' });

  await expect(page.getByText(/^Lumen Studio · /).first()).toBeVisible();
  await expect.soft(page).toHaveURL(`${BASE_URL}/dashboard`, SOFT);
  await expect.soft(page.getByText('Today', { exact: true }).first()).toBeVisible(SOFT);
  await expect.soft(page.getByText('Dashboard', { exact: true })).toHaveCount(0, SOFT);
  // The rail shows icons only: expand it to read the labels.
  await page.getByText('monitor_heart').click();
  await expect(page.getByRole('link')).toHaveCount(3);
  await expect(page.getByRole('link')).toContainText(['Today', 'Calendar', 'Clients']);
  // The development aids exist only in the dev-headers mode.
  await expect(page.getByText('Debug / locale')).toHaveCount(0);
  await expect(page.getByText('settings', { exact: true })).toHaveCount(0);
  for (const role of ['Owner', 'Master']) await expect(page.getByText(role, { exact: true })).toHaveCount(0);
});

test('a callback with a state that does not match shows the error card with a retry', async ({ page }) => {
  await page.goto('/auth?code=x&state=bad');
  await expect(page.getByRole('alert')).toContainText('could not be verified');
  const retry = page.getByRole('button', { name: 'Try again' });
  await expect(retry).toBeVisible();
  // The spent code and state are gone from the address bar.
  await expect(page).toHaveURL(`${BASE_URL}/auth`);
  await screenshotWidths(page, 'oidc-auth-error');

  await retry.click();
  await page.waitForURL(KEYCLOAK_URL);
  await expect(page.locator('#username')).toBeVisible();
});
