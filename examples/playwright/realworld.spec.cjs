/*
 * RealWorld (Conduit) — smoke test (rf2-w3vn).
 *
 * The realworld example is large: ~13 namespaces, multiple feature
 * slices, full routing. Per the rf2-w3vn bead this spec is scoped to:
 *
 *   - Loads cleanly (no pageerror, no uncaught exceptions).
 *   - Main shell renders (navbar with "conduit" brand link).
 *   - Home page renders the global feed populated by the demo :http stub.
 *
 * The stub returns 2 articles for the global feed, so we expect to see
 * 2 .article-preview cards on the home page.
 */

const { expectVisible } = require('./_helpers.cjs');

module.exports = {
  name: 'realworld (Conduit)',
  url: '/realworld/',
  run: async (page) => {
    // App shell mounts.
    await expectVisible(page.locator('nav.navbar'), 15000);

    // The brand link reads "conduit".
    await expectVisible(
      page.locator('a.navbar-brand:has-text("conduit")'),
      5000,
    );

    // Main feed populates from the demo stub: 2 article previews.
    await page.waitForFunction(
      () => document.querySelectorAll('.article-preview').length >= 2,
      null,
      { timeout: 10000 },
    );
  },
};
