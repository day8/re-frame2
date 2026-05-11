/*
 * boot — Pattern-Boot example app.
 *
 * The boot example demonstrates the canonical Pattern-Boot shape: a
 * boot state machine owns the application's initialisation graph,
 * fans out parallel dependency loads via `:invoke-all`, and the main
 * view does NOT mount until the boot reaches `:ready`.
 *
 * Coverage:
 *   - Loads cleanly (no pageerror, no uncaught exceptions).
 *   - Boot-progress screen is visible at first paint (the main app
 *     view does not mount before :ready).
 *   - After the canned :rf.http/managed stub resolves the four
 *     mocked endpoints, the boot machine reaches :ready and the
 *     main app screen swaps in.
 *   - The hydrated config / flags / user / routes slices populate
 *     the main app view (title from config; routes list from
 *     /api/routes.json).
 */

const { expectVisible } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'boot — Pattern-Boot example',
  url: '/boot/',
  run: async (page) => {
    // The boot-progress view is visible at first paint — the
    // boot machine has not yet reached :ready.
    await expectVisible(page.locator('[data-testid="boot-progress"]'), 5000);

    // After the four canned-stub replies resolve (each on a
    // 60 ms timeout), the boot machine reaches :ready and the
    // main app view swaps in.
    await expectVisible(page.locator('[data-testid="main-app"]'), 15000);

    // The main app title comes from the staged config payload.
    await expectVisible(
      page.locator('[data-testid="main-title"]:has-text("Pattern-Boot example app")'),
      5000,
    );

    // The routes list is populated from the canned /api/routes.json reply.
    await page.waitForFunction(
      () =>
        document.querySelectorAll('[data-testid="routes-list"] li').length >= 3,
      null,
      { timeout: 5000 },
    );

    // The config :env value is rendered from the staged config map.
    await expectVisible(
      page.locator('[data-testid="config-env"]:has-text(":prod")'),
      5000,
    );
  },
};
