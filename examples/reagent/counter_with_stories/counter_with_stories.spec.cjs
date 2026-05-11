/*
 * Counter-with-stories example — Playwright smoke test (rf2-c9mm,
 * Story Stage 8).
 *
 * Two surfaces in one bundle, hash-routed:
 *
 *   - #/        — the live counter app (counter-card view). The
 *                 same assertions as examples/reagent/counter, scoped
 *                 to a different starting value (5) and the parity
 *                 badge.
 *   - #/stories — the Story shell. We confirm the shell mounts
 *                 (the rf-story-shell react-class display-name
 *                 places enough hooks for a robust selector via the
 *                 sidebar root element).
 *
 * The deep CLJS assertions on the Story registry (every reg-* macro
 * landed, play sequences pass, mount/unmount round-trip) live in
 * counter_with_stories.stories-cljs-test under the top-level
 * `npm run test:cljs` build — this spec is the browser-side
 * end-to-end on a real Chromium.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-with-stories',
  url: '/counter-with-stories/',
  run: async (page) => {
    // ---- 1. Live app at # = root ----
    //
    // The counter renders with :count 5 (seeded by the run fn's
    // dispatch-sync of [:counter/initialise 5]). The parity badge
    // reports :odd for 5.
    const count = page.locator('[data-test="count"]').first();
    await expectTextEquals(count, '5', 10000);

    const parity = page.locator('[data-test="parity"]').first();
    await expectTextEquals(parity, 'odd');

    // +1 brings the count to 6 and the parity to :even.
    await page.locator('[data-test="inc"]').first().click();
    await expectTextEquals(count, '6');
    await expectTextEquals(parity, 'even');

    // ---- 2. Switch to the Story shell ----
    //
    // Navigate to #/stories. The Story shell mounts a fresh React
    // root over the #app node. We verify by waiting for the
    // sidebar's known root layout. The shell uses a flexbox row
    // root with the rf-story-shell display-name; a tagged selector
    // is robust enough — we look for the placeholder text that
    // renders when no variant is selected.
    await page.evaluate(() => {
      window.location.hash = '#/stories';
    });

    // The shell renders a placeholder string in its main pane when
    // no variant has been selected. The string is stable across
    // versions — bundled in tools/story/src/re_frame/story/ui/shell.cljs.
    await page
      .getByText(/Select a variant or workspace from the sidebar/i, {
        exact: false,
      })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // ---- 3. Switch back to the live app ----
    //
    // Hash-change back to the live app; counter should reappear.
    // We re-create the React root inside `mount-app!` so the count
    // re-renders against the surviving app-db state (which still
    // says :count 6 after the click above).
    await page.evaluate(() => {
      window.location.hash = '#/';
    });

    const countAfter = page.locator('[data-test="count"]').first();
    await expectTextEquals(countAfter, '6', 10000);
  },
};
