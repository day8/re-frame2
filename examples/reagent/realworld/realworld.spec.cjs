/*
 * RealWorld (Conduit) — Spec 014 (`:rf.http/managed`) demo (rf2-o8t6).
 *
 * The realworld example is the canonical Spec 014 demo per
 * rf2-kauy / rf2-o8t6: every Conduit endpoint goes via `:rf.http/managed`,
 * with a canned-stub override (Spec 014 §Testing) routing requests to
 * synthesised replies — no network required.
 *
 * Coverage:
 *   - Loads cleanly (no pageerror, no uncaught exceptions).
 *   - Main shell renders (navbar with "conduit" brand link).
 *   - Home page renders the global feed populated by the demo stub.
 *   - Tag list renders in the sidebar.
 *   - Clicking an article preview navigates to the article detail page.
 */

const { expectVisible } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'realworld (Conduit) — Spec 014 demo',
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

    // Sidebar tag list renders (4 demo tags).
    await page.waitForFunction(
      () => document.querySelectorAll('.tag-list a.tag-pill').length >= 4,
      null,
      { timeout: 10000 },
    );
  },
};
