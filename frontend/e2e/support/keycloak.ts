import { readFileSync } from 'node:fs';
import path from 'node:path';
import type { Page } from '@playwright/test';

const REALM_FILE = path.resolve(__dirname, '..', '..', '..', 'docker', 'keycloak', 'realm-cadence.json');

type RealmUser = { username: string; credentials: { type: string; value: string }[] };

/** Dev realm user as the Keycloak container imports it: the credentials are read from the realm file, never copied. */
export function realmUser(username: string): { username: string; password: string } {
  const users = (JSON.parse(readFileSync(REALM_FILE, 'utf8')) as { users: RealmUser[] }).users;
  const password = users.find((user) => user.username === username)?.credentials.find((credential) => credential.type === 'password')?.value;
  if (!password) throw new Error(`${username} has no password in ${REALM_FILE}`);
  return { username, password };
}

/** Fills the Keycloak login form (the page must already be on it) and submits. */
export async function submitKeycloakLogin(page: Page, user: { username: string; password: string }): Promise<void> {
  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click();
}
