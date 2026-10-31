import path from 'node:path';
import { defineConfig, devices, type PlaywrightTestConfig } from '@playwright/test';
import { BASE_URL, OUT_DIR } from './env';

/** Shared settings of both runs; the OIDC config only changes where the tests live. */
export function e2eConfig(overrides: PlaywrightTestConfig): PlaywrightTestConfig {
  return defineConfig({
    workers: 1,
    fullyParallel: false,
    retries: 0,
    timeout: 30_000,
    expect: { timeout: 10_000 },
    outputDir: path.join(OUT_DIR, 'test-results'),
    reporter: [['list'], ['html', { outputFolder: path.join(OUT_DIR, 'report'), open: 'never' }]],
    globalSetup: require.resolve('./global-setup'),
    use: {
      baseURL: BASE_URL,
      locale: 'en-US',
      // The tenant lives in Asia/Tbilisi; the browser shows times in the same zone whatever the host is.
      timezoneId: 'Asia/Tbilisi',
      trace: 'retain-on-failure',
      screenshot: 'only-on-failure',
    },
    projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } }],
    ...overrides,
  });
}
