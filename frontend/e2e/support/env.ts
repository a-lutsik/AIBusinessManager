import path from 'node:path';

// Set by scripts/e2e.mjs; the defaults only matter for `npx playwright test` against an already running stack.
export const API_URL: string = process.env.E2E_API_URL ?? 'http://localhost:18080';
export const BASE_URL: string = process.env.E2E_BASE_URL ?? 'http://localhost:19005';
export const OUT_DIR: string = process.env.E2E_OUT_DIR ?? path.resolve(__dirname, '..', '..', 'e2e-results');
export const COMPOSE_FILE: string = process.env.E2E_COMPOSE_FILE ?? path.resolve(__dirname, '..', '..', '..', 'docker-compose.e2e.yml');
export const COMPOSE_PROJECT: string = process.env.E2E_COMPOSE_PROJECT ?? 'cadence-e2e';
export const ISSUER: string | undefined = process.env.E2E_ISSUER;

export const TENANT_SLUG = 'lumen-studio';
export const TENANT_ID = '00000000-0000-4000-8000-000000000001';
/** Nino Beridze, the master of the dev-headers session (frontend DEV_SPECIALIST_ID). */
export const NINO_ID = '00000000-0000-4000-8000-000000000011';
/** Anna Novakova, a master other than the one of the dev-headers session. */
export const ANNA_ID = '00000000-0000-4000-8000-000000000012';
