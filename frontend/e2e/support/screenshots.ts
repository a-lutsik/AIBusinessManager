import path from 'node:path';
import type { Page } from '@playwright/test';
import { OUT_DIR } from './env';

const VIEWPORTS: { width: number; height: number }[] = [
  { width: 1280, height: 800 },
  { width: 375, height: 812 },
];

const MAX_HEIGHT = 4000;

// Two frames: the layout follows the window size after the resize event.
const settle = (page: Page): Promise<void> =>
  page.evaluate(() => new Promise<void>((done) => requestAnimationFrame(() => requestAnimationFrame(() => done()))));

/**
 * Screenshots at 1280 and 375 px: e2e-results/screenshots/<name>-<width>.png. The console scrolls inside its own
 * ScrollView, so the page itself is never taller than the window: the window grows by the largest scrollable overflow
 * (up to MAX_HEIGHT) to show the whole screen. The viewport is restored.
 */
export async function screenshotWidths(page: Page, name: string): Promise<void> {
  const original = page.viewportSize();
  for (const viewport of VIEWPORTS) {
    await page.setViewportSize(viewport);
    await settle(page);
    const overflow = await page.evaluate(() => Math.max(0, ...Array.from(document.querySelectorAll('*'), (e) => e.scrollHeight - e.clientHeight)));
    await page.setViewportSize({ width: viewport.width, height: Math.min(viewport.height + overflow, MAX_HEIGHT) });
    await settle(page);
    await page.screenshot({ path: path.join(OUT_DIR, 'screenshots', `${name}-${viewport.width}.png`) });
  }
  if (original) await page.setViewportSize(original);
}
