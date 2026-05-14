/*
 * Cart-total testbed — browser smoke (rf2-0sg12).
 *
 * Runnable version of the 3pm cart-total scenario from the Causa
 * tutorial (docs/causa/index.md:13-30). The page ships:
 *
 *   - A three-row catalogue (Apple $1.50, Bread $3.50, Coffee $4.50).
 *   - A cart panel that subscribes to `:cart/total`.
 *   - A deliberately-wrong projection sub: `:cart/total` reads
 *     `:checkout/items` instead of `:cart/items` (the bug from the
 *     tutorial walk-through, line 27 of the tutorial's index).
 *
 * This spec proves the testbed faithfully demonstrates the bug:
 *
 *   1. Initial cart (seeded Apple ×2, Bread ×1) shows $6.50 total.
 *      (The seed runs before checkout-start, so the wrong-slot sub
 *      reading `:checkout/items` still reads `[]` and the wrong path
 *      is invisible — total reads $0.00.) ← initial-render check
 *
 *   2. Click Checkout — the cart UI's `:cart/items` slot empties,
 *      `:checkout/items` populates, the banner appears, the total
 *      catches up to the snapshot ($6.50).
 *
 *   3. Click +Apple — the cart UI shows the line again (Apple ×1),
 *      but the displayed total stays locked at $6.50 (the snapshot's
 *      total) instead of jumping to $1.50 (the live basket's total).
 *      This is the user-visible bug the tutorial walks through.
 *
 *   4. Verify the data-rf2-source-coord attribute is on the cart-total
 *      span — the very first step of the tutorial's debugging cascade
 *      (the tester right-clicks here, copies element, the coord routes
 *      the dev back to the line).
 */

const { expectTextEquals, expectVisible } =
  require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'cart-total (Causa tutorial)',
  url: '/cart-total/',
  run: async (page) => {
    // --- 0. Wait for first paint --------------------------------------------
    const total = page.locator('[data-test="cart-total"]');
    await expectVisible(total, 10000);

    // --- 1. Initial state — the wrong-slot bug is INVISIBLE here -----------
    //
    // The seed dispatches Apple ×2 + Bread ×1 into `:cart/items`, but
    // `:checkout/items` is still `[]` because checkout hasn't started.
    // The buggy `:cart/total` reads off `:checkout/items` (the empty
    // snapshot), so the displayed total is $0.00 even though the cart
    // visibly contains $6.50 of items. THIS IS THE TUTORIAL'S 3pm
    // SYMPTOM — wrong number, cart looks fine.
    await expectTextEquals(total, '$0.00');

    // The cart should still render the three seeded lines so the user
    // sees "obviously this should be $6.50, but the total says $0.00".
    await expectVisible(page.locator('[data-test="line-apple"]'), 5000);
    await expectVisible(page.locator('[data-test="line-bread"]'), 5000);

    // --- 2. data-rf2-source-coord on the cart-total span -------------------
    //
    // Step 1 of the tutorial's debugging cascade: the tester right-
    // clicks the wrong number and copies element; the dev reads the
    // coord and jumps to the line. The attribute is dev-only — present
    // here because the testbed runs un-minified through the examples
    // orchestrator.
    const enclosing = page.locator(
      'div:has(> [data-test="cart-total"])[data-rf2-source-coord]',
    );
    if ((await enclosing.count()) === 0) {
      throw new Error(
        'Expected data-rf2-source-coord on the cart-total enclosing div ' +
          '(step 1 of the tutorial scenario). ' +
          'Got none — re-check reg-view is producing the dev-only attribute.',
      );
    }
    const coord = await enclosing.first().getAttribute('data-rf2-source-coord');
    if (!coord || !/^cart-total\.core:[^:]+:\d+:\d+$/.test(coord)) {
      throw new Error(
        `data-rf2-source-coord shape unexpected: ${JSON.stringify(coord)}. ` +
          'Expected ns:sym:line:col under cart-total.core.',
      );
    }

    // --- 3. Click Checkout — snapshot drifts, total catches up -------------
    await page.locator('[data-test="checkout-start"]').click();
    await expectVisible(page.locator('[data-test="checkout-banner"]'), 5000);
    // Now `:checkout/items` carries the snapshot (Apple ×2 + Bread ×1
    // = $6.50). The wrong-slot sub reads through, so the displayed
    // total moves to $6.50. The live `:cart/items` is now empty, so
    // the "Cart is empty" hint replaces the line rows.
    await expectTextEquals(total, '$6.50');

    // --- 4. Click +Apple — live basket repopulates, total stays locked -----
    //
    // This is the user-visible bug from line 27 of the tutorial: the
    // basket NOW has one Apple, but the total still reads $6.50 (the
    // snapshot's total), not $1.50 (the new live basket).
    await page.locator('[data-test="add-apple"]').click();
    await expectVisible(page.locator('[data-test="line-apple"]'), 5000);
    await expectTextEquals(total, '$6.50');

    // --- 5. Reset and confirm the testbed isn't quietly self-healing -------
    await page.locator('[data-test="checkout-reset"]').click();
    // Banner gone; snapshot empty again; wrong-slot sub reads `[]`;
    // total flips back to $0.00 despite the live basket having an
    // Apple ($1.50) in it.
    await expectTextEquals(total, '$0.00');
  },
};
