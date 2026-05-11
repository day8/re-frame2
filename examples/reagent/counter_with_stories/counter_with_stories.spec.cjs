/*
 * Counter-with-stories example — Playwright smoke test (Story Stage 8).
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

    // ---- 2b. Click a variant directly — no workspace first (rf2-zme7) ----
    //
    // Regression for rf2-zme7. Mike's repro: open the Story playground,
    // click a variant row in the sidebar WITHOUT first clicking a
    // workspace; the page used to blank because the canvas's
    // `decorated-view` call propagated any `:wrap` exception up the
    // Reagent tree, unmounting the shell. The fix is
    // `canvas/safe-decorated-view` — exceptions become an inline error
    // block alongside the uncoated body.
    //
    // We click /clicked-three-times, the variant that registers the
    // largest decorator stack (story-level + variant-level
    // log-decorator references), and assert the canvas mounts content
    // rather than blanking.
    await page.locator('text=/clicked-three-times').first().click();

    // The canvas titles itself with the variant id (pr-str'd as a
    // keyword) and follows with the view-id arrow + share button. The
    // variant id text suffices — it only appears in the canvas frame
    // header, not the sidebar row (which uses the leading-slash form
    // `/clicked-three-times`).
    await page
      .getByText(':story.counter/clicked-three-times', { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // The variant's render output also reaches the DOM — the
    // log-decorator from rf2-zme7 (after the fix) wraps the body and
    // both story-level + variant-level label strings appear.
    await page
      .getByText(/decorator: story-level/i)
      .first()
      .waitFor({ state: 'visible', timeout: 5000 });
    await page
      .getByText(/decorator: variant-level/i)
      .first()
      .waitFor({ state: 'visible', timeout: 5000 });

    // Switching between variants directly (no workspace) keeps the
    // shell mounted — pick /loaded next and assert its title surfaces.
    await page.locator('text=/loaded').first().click();
    await page
      .getByText(':story.counter/loaded', { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // ---- 2c. Click a workspace — variant cells render (rf2-zme7) ----
    //
    // Second half of rf2-zme7. Mike's screenshot of #/stories with
    // :Workspace.counter/auto-grid selected showed every variant card
    // rendering empty: title + "variant frame: <id>" stub, no counter
    // UI inside. Root cause: workspace.cljc's `variant-cell` was a
    // Stage-4-era stub that never called `rf/view` — it shipped a
    // label and a placeholder div instead of invoking the registered
    // view in the variant's allocated frame. The fix is the new
    // `variant-cell` Reagent component that mounts the variant's
    // frame via `run-variant` and renders `(rf/view :counter-with-
    // stories.views/counter-card)` wrapped in `frame-provider` so each
    // cell's subscriptions scope to its own per-variant app-db.
    //
    // Assertion: after clicking :Workspace.counter/auto-grid, the
    // workspace renders four variant cards and each card contains
    // counter-buttons (look for the `[data-test="count"]` span the
    // counter-buttons view emits). Each cell's count reflects its
    // variant's `:events` slot — variant-isolated app-db.
    await page.locator('text=/auto-grid').first().click();

    // The workspace titles itself with the workspace id + layout.
    await page
      .getByText(':Workspace.counter/auto-grid', { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    // Each variant cell renders the counter-card view. The view emits
    // a `[data-test="count"]` span; we expect at least one of those to
    // appear in the workspace pane. Without the rf2-zme7 fix every
    // cell rendered the stub div and zero data-test=count elements
    // existed under the workspace.
    await page
      .locator('[data-test="count"]')
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
