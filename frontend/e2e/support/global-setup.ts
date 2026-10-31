import { API_URL, ISSUER, TENANT_SLUG } from './env';

const ISOLATED_API_PORT = '18080';

async function require200(what: string, url: string): Promise<void> {
  let status: number | string;
  try {
    status = (await fetch(url, { signal: AbortSignal.timeout(5000) })).status;
  } catch (error) {
    status = String(error);
  }
  if (status !== 200) {
    throw new Error(`${what} does not answer at ${url} (${status}). Run the suite through "npm run e2e", it starts the stack.`);
  }
}

/** Fails fast, with the reason, when the run would not hit the isolated backend. */
export default async function globalSetup(): Promise<void> {
  if (new URL(API_URL).port !== ISOLATED_API_PORT) {
    throw new Error(`E2E_API_URL is ${API_URL}, expected port ${ISOLATED_API_PORT}: the suite must never reach the dev backend.`);
  }
  await require200('The isolated backend', `${API_URL}/api/public/tenants/${TENANT_SLUG}`);
  if (ISSUER) await require200('Keycloak', `${ISSUER}/.well-known/openid-configuration`);
  console.log(`[e2e] setup ok: backend ${API_URL}${ISSUER ? `, issuer ${ISSUER}` : ''}`);
}
