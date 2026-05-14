/*
 * Login-form testbed — Playwright spec (rf2-0sg12).
 *
 * Two surfaces in one bundle, hash-routed:
 *
 *   - #/          — the live login card. The spec drives the
 *                   form through a happy-path login (good password →
 *                   :authenticated → welcome banner) and an error
 *                   path (bad password → :error → error message).
 *
 *   - #/stories   — the Story shell. The spec mounts the shell and
 *                   verifies every one of the five variants is
 *                   reachable from the sidebar.
 *
 * The deep CLJS assertions (every variant's play sequence resolves
 * green, fx-stub records the call, FSM lands in the right state)
 * run under `npm run test:cljs` against the assertion machinery in
 * tools/story/test; this browser smoke proves the surface as the
 * tutorial reader will encounter it.
 */

const {
  expectTextEquals,
  expectVisible,
} = require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'login-form (Story tutorial)',
  url: '/login-form/',
  run: async (page) => {
    // ====================================================================
    // 1. Live page — happy path
    // ====================================================================
    //
    // The page lands on `#/` (the live card). The card renders the
    // form with empty inputs and the FSM in :idle.
    const heading = page.locator('[data-test="login-heading"]');
    await expectTextEquals(heading, 'Sign in', 10000);

    const statePill = page.locator('[data-test="login-state-pill"]');
    await expectTextEquals(statePill, 'state: :idle');

    // Type the success password and submit.
    await page.locator('[data-test="login-email"]').fill('ada@example.com');
    await page.locator('[data-test="login-password"]').fill('correct-horse');
    await page.locator('[data-test="login-submit"]').click();

    // The live demo fx has a 250ms artificial delay so the busy state
    // is observable. Re-poll until the FSM lands in :authenticated;
    // the welcome banner replaces the form.
    await expectVisible(page.locator('[data-test="login-welcome"]'), 10000);
    await expectTextEquals(statePill, 'state: :authenticated');

    // ====================================================================
    // 2. Live page — sign out, then error path
    // ====================================================================
    await page.locator('[data-test="login-sign-out"]').click();
    await expectVisible(page.locator('[data-test="login-form"]'), 5000);
    await expectTextEquals(statePill, 'state: :idle');

    await page.locator('[data-test="login-email"]').fill('ada@example.com');
    await page.locator('[data-test="login-password"]').fill('wrong-password');
    await page.locator('[data-test="login-submit"]').click();

    // After the artificial latency, the FSM lands in :error and the
    // error message appears under the submit button.
    await expectVisible(page.locator('[data-test="login-error"]'), 10000);
    await expectTextEquals(statePill, 'state: :error');

    // ====================================================================
    // 3. Story shell — five variants reachable from the sidebar
    // ====================================================================
    //
    // Persist the "seen-help" flag before navigating so the
    // first-visit help overlay is dismissed automatically (same
    // mechanism counter_with_stories.spec.cjs uses).
    await page.evaluate(() => {
      try {
        localStorage.setItem('re-frame.story/seen-help-v1', '1');
      } catch (_) {
        /* ignore — private mode etc */
      }
    });

    await page.goto(`${page.url().split('#')[0]}#/stories`, { waitUntil: 'load' });

    // The shell renders a `<nav>` landmark with one row per variant.
    // Anchor on stable text — the variant ids — to dodge any class-
    // name churn.
    const variantNames = [
      '/idle',
      '/submitting',
      '/error',
      '/submitting-retry',
      '/authenticated',
    ];

    const nav = page.getByRole('navigation');
    await nav.first().waitFor({ state: 'visible', timeout: 10000 });

    for (const name of variantNames) {
      const row = nav.getByText(name, { exact: false }).first();
      await row.waitFor({ state: 'visible', timeout: 10000 });
    }

    // Click into the :authenticated variant and confirm the canvas
    // renders the welcome banner with the test email.
    const authedRow = nav.getByText('/authenticated', { exact: false }).first();
    await authedRow.click();
    await expectVisible(page.locator('[data-test="login-welcome"]'), 10000);
  },
};
