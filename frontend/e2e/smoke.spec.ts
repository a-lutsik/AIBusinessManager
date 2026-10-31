import { expect, test } from '@playwright/test';
import { useRole } from './support/role';

test('OWNER opens the dashboard and sees the tenant', async ({ page }) => {
  await useRole(page, 'OWNER');
  await page.goto('/dashboard');

  // The title is in the shell header and in the page itself.
  await expect(page.getByText('Dashboard', { exact: true }).first()).toBeVisible();
  await expect(page.getByText(/^Lumen Studio · /)).toBeVisible();
});
