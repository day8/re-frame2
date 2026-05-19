/*
 * counter-slim-and-fast — Playwright smoke (rf2-6hyy Stage 4-G).
 *
 * Drop-in parity with the canonical counter example
 * (examples/reagent/counter/) — identical user-visible behaviour, just
 * mounted on the day8/reagent-slim rewrite instead of the day8/re-frame2-
 * reagent thin bridge. The substrate swap is observable only through
 * the bundle-isolation grep
 * (implementation/scripts/check-reagent-slim-bundle-isolation.cjs); the
 * user-visible dataflow is identical.
 *
 * Assertions (mirrored from the canonical counter's spec shape):
 *
 *   1. Initial render lands the seeded value :count = 5.
 *      (run fn dispatches [:counter/initialise]; the event handler
 *      seeds {:count 5} per core.cljs.)
 *   2. Clicking "+" once bumps the count to 6 (inc handler wired
 *      through the slim adapter's render Reaction + queue-render!).
 *   3. Clicking "-" twice brings the count back to 4 (dec handler
 *      wired symmetrically; second click proves the Reaction's dep
 *      tracking re-fires on each app-db change, not just the first).
 *
 * The boot also exercises reagent2.dom.server/render-to-static-markup
 * (logged to the browser console for DCE-resistance). The Playwright
 * spec doesn't assert on that log line — the binding contract for the
 * pure-CLJS SSR claim is grep-based and lives in the bundle-isolation
 * verifier — but a passing smoke confirms the SSR call doesn't throw
 * at boot.
 *
 * Cross-references:
 *   - examples/reagent/counter_slim_and_fast/core.cljs — the example source
 *   - implementation/scripts/check-reagent-slim-bundle-isolation.cjs —
 *     the binding S3-008 + S3-005 bundle-isolation contract
 *   - implementation/adapters/reagent-slim/IMPL-SPEC.md §12 — the test
 *     strategy this smoke participates in
 */

const { expectTextEquals, expectVisible } =
  require('../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-slim-and-fast smoke',
  url:  '/counter-slim-and-fast/',
  run:  async (page) => {
    // The counter value sits inside [data-testid="counter-value"] —
    // a <span> the view's reg-view'd component emits. Wait for the
    // span to be visible before reading text (the boot path mounts
    // via rdc/render which is async w.r.t. dispatch-sync, so the
    // first paint may not be on the synchronous tick after navigation).
    const value = page.locator('[data-testid="counter-value"]');
    await expectVisible(value, 10000);

    // 1. The :counter/initialise handler seeded :count = 5.
    await expectTextEquals(value, '5', 10000);

    // 2. "+" → :counter/inc → :count = 6. The button has no
    //    test-id (the canonical counter doesn't carry one either),
    //    so we resolve by accessible text. role=button name=text is
    //    the most stable shape across re-renders.
    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(value, '6');

    // 3. "-" twice → :counter/dec → :count = 4. The second click
    //    proves the slim's Reaction-around-render dependency tracking
    //    survives an arbitrary number of app-db deltas (the first
    //    click could pass via a one-shot effect; the second can't).
    const minus = page.getByRole('button', { name: '-' });
    await minus.click();
    await minus.click();
    await expectTextEquals(value, '4');
  },
};
