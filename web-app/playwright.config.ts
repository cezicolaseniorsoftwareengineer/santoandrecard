import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end configuration.
 *
 * <p>These tests drive a browser against the whole stack — Angular, Keycloak,
 * card-service, PostgreSQL — because that is the only place several of the
 * guarantees are actually visible. A token minted by a real identity provider,
 * a redirect that comes back with a code, a CORS allow-list that either lets the
 * call through or does not: none of it can be asserted from a unit test, and all
 * of it is what breaks in a deployment.
 *
 * <p>The stack is brought up by the caller rather than by Playwright:
 *
 *   docker compose up -d
 *   mvn -pl card-service quarkus:dev        # or the packaged jar
 *   npm run e2e
 *
 * Playwright starts only the front end, which is the one process it owns.
 */
const BASE_URL = process.env.E2E_BASE_URL ?? 'http://localhost:4420';

/** Anything else is already being served by whoever deployed it. */
const E2E_BASE_URL_IS_LOCAL = BASE_URL.includes('localhost:4420');

export default defineConfig({
  testDir: './e2e',
  // The journey is one ordered story per file: registering, issuing and buying
  // in parallel against a shared identity provider races on the account itself.
  fullyParallel: false,
  workers: 1,
  // A redirect through an identity provider is slower than a local click, and a
  // cold Quarkus start is slower still.
  timeout: 90_000,
  expect: { timeout: 15_000 },
  // A test that only passes on the third attempt is a test that found something.
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list']],
  use: {
    baseURL: BASE_URL,
    // Kept for the runs that fail, discarded for the ones that pass: evidence
    // where it is needed, no artefact archive where it is not.
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } }
  ],
  // Started only when the journey is pointed at the local front end. Against a
  // deployed environment — a cluster, a staging host — the application is
  // already being served, and starting a second copy would test that one
  // instead of the one under test.
  webServer: E2E_BASE_URL_IS_LOCAL
    ? {
        // The production build, not the development server. The journey should
        // meet the bundle that would be deployed — optimised, with the same
        // budgets — and a development server also brings its own overlay, which
        // happily covers the button a test is trying to click.
        command: 'npm run start:e2e',
        url: BASE_URL,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000
      }
    : undefined
});
