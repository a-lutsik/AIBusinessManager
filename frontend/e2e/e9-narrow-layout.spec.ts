import { expect, test, type Locator, type Page } from '@playwright/test';
import { visitOf } from './support/app';
import { createBooking, uniqueName } from './support/bookings';
import { NINO_ID, TENANT_SLUG } from './support/env';
import { useRole, type Role } from './support/role';

const WIDTH = 375;

async function required(locator: Locator): Promise<{ x: number; y: number; width: number; height: number }> {
  const box = await locator.boundingBox();
  if (!box) throw new Error(`No bounding box for ${locator}`);
  return box;
}

const insidePanel = (page: Page, x: number, y: number): Promise<boolean> =>
  page.evaluate(([px, py]) => !!document.elementFromPoint(px, py)?.closest('[data-testid="visit-panel"]'), [x, y]);

/**
 * The document width alone is not enough: a react-native-web ScrollView hides its horizontal overflow, so a child can
 * stick out past the screen without widening the document. The only exception is the content of the scroller of the
 * service matrix table, on its own route; the scroller itself has to fit.
 */
async function elementsPastRightEdge(page: Page, allowedScroller?: string): Promise<string[]> {
  return page.evaluate(
    ({ width, scroller }) =>
      Array.from(document.querySelectorAll('body *'))
        .filter((element) => {
          const rect = element.getBoundingClientRect();
          if (rect.width === 0 || rect.height === 0 || rect.right <= width + 0.5) return false;
          if (getComputedStyle(element).visibility === 'hidden') return false;
          return !(scroller && element.parentElement?.closest(`[data-testid="${scroller}"]`));
        })
        .map((element) => `${element.tagName} right=${Math.round(element.getBoundingClientRect().right)} "${(element.textContent ?? '').slice(0, 40)}"`),
    { width: WIDTH, scroller: allowedScroller }
  );
}

type Screen = { path: string; content: (page: Page) => Locator; allowedScroller?: string };

const text = (value: string) => (page: Page): Locator => page.getByText(value, { exact: true }).first();

/** Opens the screen, waits for its own content (an empty page must not pass) and looks for anything past the edge. */
async function expectFits(page: Page, { path, content, allowedScroller }: Screen): Promise<void> {
  await page.goto(path);
  await expect(content(page), `${path}: content`).toBeVisible();
  await page.waitForLoadState('networkidle');
  expect(await page.evaluate(() => document.documentElement.scrollWidth), `${path}: document scrollWidth`).toBeLessThanOrEqual(WIDTH);
  expect(await elementsPastRightEdge(page, allowedScroller), `${path}: elements past the right edge`).toEqual([]);
}

const MATRIX: Screen = { path: `/specialists/${NINO_ID}`, content: text('Haircut'), allowedScroller: 'matrix-scroller' };

const CONSOLE: Record<Role, Screen[]> = {
  OWNER: [
    { path: '/dashboard', content: text('Growth') },
    { path: '/services', content: text('Haircut') },
    { path: '/specialists', content: text('Nino Beridze') },
    MATRIX,
    { path: '/rules', content: text('Clients may reschedule') },
    { path: '/ai', content: text('New conversation') },
  ],
  MASTER: [
    { path: '/dashboard', content: text('Calendar →') },
    { path: '/services', content: text('Haircut') },
    MATRIX,
    { path: '/rules', content: text('Only the owner can view and change booking rules.') },
  ],
};

test.describe(`at ${WIDTH} px`, () => {
  test.use({ viewport: { width: WIDTH, height: 812 } });

  for (const role of ['OWNER', 'MASTER'] as const) {
    test(`${role}: nothing in the console sticks out sideways`, async ({ page, request }) => {
      const name = uniqueName(`Narrow ${role}`);
      const visit = await createBooking(request, { name });
      const { clientId } = await visitOf(request, visit.id);
      await useRole(page, role);
      const screens: Screen[] = [
        ...CONSOLE[role],
        { path: '/calendar', content: (p) => p.getByTestId(`visit-row-${visit.id}`) },
        { path: '/clients', content: (p) => p.getByPlaceholder('Search').last() },
        { path: `/clients/${clientId}`, content: text(name) },
      ];
      for (const screen of screens) await expectFits(page, screen);
    });
  }

  // The icons are ligatures of a web font: until it arrives (slow network) or when it never does, they are plain words.
  test('the menu stays on screen while the icon font is not there', async ({ page }) => {
    let blocked = 0;
    await page.route('**/fonts.gstatic.com/**', (route) => {
      blocked += 1;
      return route.abort();
    });
    await useRole(page, 'OWNER');
    await expectFits(page, { path: '/dashboard', content: text('Growth') });
    expect(blocked, 'the font requests really were blocked').toBeGreaterThan(0);
  });

  test('nothing on the public pages sticks out sideways', async ({ page, request }) => {
    const visit = await createBooking(request, { name: 'Narrow Client' });
    await expectFits(page, { path: `/book/${TENANT_SLUG}`, content: text('Haircut') });
    await expectFits(page, { path: visit.managePath, content: text('Narrow Client') });
  });

  test('service matrix: the title and the lead stay on screen, only the table scrolls sideways', async ({ page }) => {
    await useRole(page, 'OWNER');
    await page.goto(MATRIX.path);
    await expect(page.getByText('Haircut', { exact: true })).toBeVisible();
    for (const label of ['Service matrix', 'Defaults come from the service']) {
      const box = await required(page.getByText(label).last());
      expect(box.x + box.width, `${label}: right edge`).toBeLessThanOrEqual(WIDTH);
    }
    await expect(page.locator('[data-testid^="matrix-row-"]')).toHaveCount(8);
    const scroller = page.getByTestId('matrix-scroller');
    const box = await required(scroller);
    expect(box.x + box.width, 'scroller right edge').toBeLessThanOrEqual(WIDTH);
    expect(await scroller.evaluate((element) => element.scrollWidth > element.clientWidth), 'the table scrolls inside it').toBe(true);
  });

  test('calendar: the visit panel covers the feed and takes the width of the content', async ({ page, request }) => {
    const visit = await createBooking(request, { name: 'Narrow Calendar' });
    await useRole(page, 'OWNER');
    await page.goto('/calendar');
    const row = page.getByTestId(`visit-row-${visit.id}`);
    await expect(row).toBeVisible();
    // The seeded week can push the row below the window, where elementFromPoint finds nothing.
    await row.scrollIntoViewIfNeeded();
    const feedRow = await required(row);
    expect(feedRow.width, 'feed row width').toBeGreaterThan(WIDTH / 2);

    await row.click();
    const panel = page.getByTestId('visit-panel');
    await expect(panel).toContainText('Narrow Calendar');
    const box = await required(panel);
    expect(box.x, 'panel left edge').toBeCloseTo(feedRow.x, 0);
    expect(box.width, 'panel width').toBeCloseTo(feedRow.width, 0);
    expect(await insidePanel(page, feedRow.x + feedRow.width / 2, feedRow.y + feedRow.height / 2), 'the row centre is under the panel').toBe(true);
    expect(await elementsPastRightEdge(page), 'elements past the right edge with the panel open').toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth), 'document scrollWidth').toBeLessThanOrEqual(WIDTH);

    await page.getByRole('button', { name: 'Close' }).click();
    await expect(panel).toHaveCount(0);
    expect((await required(row)).width, 'feed row width after closing').toBeCloseTo(feedRow.width, 0);
  });

  test('calendar: a refused action is readable in the panel that covers the feed', async ({ page, request }) => {
    const visit = await createBooking(request, { name: 'Narrow Refused' });
    await useRole(page, 'OWNER');
    await page.route(`**/api/app/appointments/${visit.id}/transition*`, (route) =>
      route.fulfill({ status: 409, contentType: 'application/json', json: { code: 'VISIT_CONFLICT', detail: 'The visit has changed, reload it.' } })
    );
    await page.goto('/calendar');
    await page.getByTestId(`visit-row-${visit.id}`).click();
    const panel = page.getByTestId('visit-panel');
    await panel.getByRole('button', { name: 'Cancel', exact: true }).click();
    const error = panel.getByText('The visit has changed, reload it.');
    await expect(error).toBeVisible();
    const box = await required(error);
    expect(await insidePanel(page, box.x + box.width / 2, box.y + box.height / 2), 'the error is on top').toBe(true);
  });
});

test('calendar at 1280 px: the panel is a 320 px column next to the feed', async ({ page, request }) => {
  const visit = await createBooking(request, { name: 'Wide Calendar' });
  await useRole(page, 'OWNER');
  await page.goto('/calendar');
  const row = page.getByTestId(`visit-row-${visit.id}`);
  await row.click();
  const panel = page.getByTestId('visit-panel');
  await expect(panel).toContainText('Wide Calendar');
  const box = await required(panel);
  expect(box.width, 'panel width').toBeCloseTo(320, 0);
  const feedRow = await required(row);
  expect(feedRow.x + feedRow.width, 'the feed ends before the panel').toBeLessThanOrEqual(box.x);
  expect(await insidePanel(page, feedRow.x + feedRow.width / 2, feedRow.y + feedRow.height / 2), 'the row is not covered').toBe(false);
});
