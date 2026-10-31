import type { Page } from '@playwright/test';
import { NINO_ID } from './env';

export type Role = 'OWNER' | 'MASTER';

/** dev-headers session: the console reads the actor from localStorage before its first request. MASTER is Nino. */
export async function useRole(page: Page, role: Role): Promise<void> {
  await page.addInitScript(
    ({ role: actor, specialistId }) => {
      localStorage.setItem('cadence.role', actor);
      if (actor === 'MASTER') localStorage.setItem('cadence.specialistId', specialistId);
      else localStorage.removeItem('cadence.specialistId');
    },
    { role, specialistId: NINO_ID }
  );
}
