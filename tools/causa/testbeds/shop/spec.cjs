/*
 * Shop testbed — browser smoke (rf2-yhxk3).
 *
 * The canonical Causa demo: one rich page exercising every Causa
 * lens (Event / App-db / Views / Trace / Machines / Issues) across
 * three frames coexisting on one page.
 *
 * This spec walks the cascade-rich UX end-to-end:
 *
 *   1. Initial paint — all three frame panels render; the catalogue
 *      auto-fetch lands; the displayed total reads $5.00 (the empty-
 *      cart edge-case for the wrong-slot sub: subtotal=0, plus tax=0
 *      and shipping=$5.00).
 *
 *   2. Frame-scoped events stay local — clicking +Apple on the cart
 *      only changes the cart's app-db; checkout and admin slots stay
 *      put.
 *
 *   3. The deliberately-wrong sub — adding items leaves the total
 *      stuck because :cart/subtotal-WRONG reads :checkout/snapshot,
 *      not :cart/items. The user-visible bug.
 *
 *   4. Cross-frame send-to-checkout — the cart's snapshot lands on
 *      :checkout-frame; the :checkout/flow machine transitions
 *      cart-empty → ready; the total now matches the snapshot.
 *
 *   5. Machine happy path — clicking Pay drives the machine through
 *      ready → paying → confirmed; Causa's Machines lens has the
 *      transition history.
 *
 *   6. HTTP failure cascade — flip the dev toggle to 500, reload the
 *      catalogue; the canned-failure stub synthesises a :rf.http/http-5xx
 *      reply; the cart's :catalogue-error slot pins the failure-map.
 *
 *   7. Schema violation — admin's 'submit broken item' fans a
 *      cross-frame write of a :qty -1 line; the post-handler
 *      validation step rejects it and rolls back the :db.
 *
 *   8. Handler throw — admin's 'refund-throw: ON' toggle then a
 *      refund click drives the runtime's per-handler try/catch.
 */

const {
  expectTextEquals,
  expectVisible,
  expectCount,
} = require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'shop (Causa demo)',
  url:  '/shop/',
  run:  async (page) => {
    // --- 0. Wait for first paint --------------------------------------------
    await expectVisible(page.locator('[data-testid="shop-root"]'), 10000);
    await expectVisible(page.locator('[data-testid="cart-frame-panel"]'),     5000);
    await expectVisible(page.locator('[data-testid="checkout-frame-panel"]'), 5000);
    await expectVisible(page.locator('[data-testid="admin-frame-panel"]'),    5000);

    // Initial machine state — cart-empty (the :on-create init writes
    // the empty :checkout/snapshot; the machine seeds in its :initial
    // state on first reach).
    // The machine is created lazily on first dispatch — the state is
    // null until the first :checkout/flow event lands. We trigger one
    // below in step 4.

    // --- 1. Empty-cart total = shipping only -------------------------------
    //
    // subtotal-WRONG reads :checkout/snapshot which is [] at boot,
    // so subtotal=0 and tax=0; shipping=500 (flat $5.00). The total
    // reads $5.00.
    await expectTextEquals(page.locator('[data-testid="cart-total-due"]'), '$5.00');

    // --- 2. Frame-scoped event: +Apple on the cart -------------------------
    //
    // Click +Apple — cart-frame's :cart/items grows. The displayed
    // total stays at $5.00 because :cart/subtotal-WRONG is still
    // reading the empty snapshot. THIS is the user-visible bug.
    await page.locator('[data-testid="cart-add-apple"]').click();
    await expectVisible(page.locator('[data-testid="cart-line-apple"]'), 5000);
    await expectTextEquals(page.locator('[data-testid="cart-total-due"]'), '$5.00');

    // --- 3. Send to checkout — cross-frame snapshot hop --------------------
    //
    // Click Send-to-checkout. Two cross-frame envelopes land on
    // checkout-frame: the snapshot write, and :checkout.flow/start
    // which drives the machine from cart-empty → ready (entry action
    // :seed-from-cart bumps the data slot).
    //
    // After: the cart is empty (cleared by send-to-checkout), the
    // checkout snapshot carries Apple ×1, the displayed total catches
    // up to the snapshot total ($1.50 subtotal + $0.15 tax + $5.00
    // shipping = $6.65).
    await page.locator('[data-testid="cart-send-to-checkout"]').click();
    await expectVisible(page.locator('[data-testid="checkout-line-apple"]'), 5000);
    await expectCount(page.locator('[data-testid^="cart-line-"]'), 0);
    await expectTextEquals(page.locator('[data-testid="cart-total-due"]'), '$6.65');
    await expectTextEquals(page.locator('[data-testid="checkout-machine-state"]'), ':ready');

    // --- 4. Machine happy path — Pay → :confirmed --------------------------
    //
    // :checkout/pay dispatches two machine events (the :pay
    // transition with the cart-non-empty? guard, then the
    // :charge-success transition). State lands at :confirmed; the
    // :fire-confirmation! entry action fires.
    await page.locator('[data-testid="checkout-pay"]').click();
    await expectTextEquals(page.locator('[data-testid="checkout-machine-state"]'), ':confirmed');

    // Reset clears the snapshot and drives the machine back to
    // cart-empty.
    await page.locator('[data-testid="checkout-reset"]').click();
    await expectTextEquals(page.locator('[data-testid="checkout-machine-state"]'), ':cart-empty');

    // --- 5. HTTP failure cascade -------------------------------------------
    //
    // Flip the next-outcome toggle to 500, then reload the catalogue.
    // The canned-failure stub synth's a :rf.http/http-5xx reply; the
    // cart pins the failure-map at :catalogue-error and renders the
    // error banner.
    await page.locator('[data-testid="outcome-http-5xx"]').click();
    await page.locator('[data-testid="cart-load-catalogue"]').click();
    await expectVisible(page.locator('[data-testid="cart-catalogue-error"]'), 5000);

    // --- 6. Schema violation -----------------------------------------------
    //
    // Admin clicks 'Submit broken item' — the bridge fans to
    // :cart-frame, the {:qty -1} write fails the CartItems schema,
    // the post-handler validation step rolls back the :db. The
    // visible cart stays as it was (no Gremlin line); the violation
    // surfaces only in Causa's Issues ribbon (we don't assert on
    // Causa's chrome in this spec — Causa's panel-level tests do
    // that; this spec asserts the runtime contract holds end-to-end).
    await page.locator('[data-testid="admin-submit-broken-item"]').click();
    // The Gremlin line MUST NOT appear in the cart — rollback worked.
    await expectCount(page.locator('[data-testid="cart-line-gremlin"]'), 0);

    // --- 7. Handler throw --------------------------------------------------
    //
    // Toggle refund-throw on, then click refund. The handler throws;
    // the runtime traps the exception (per-handler try/catch); the
    // refund doesn't land in the audit log; the testbed keeps
    // running (cascade isolation).
    await page.locator('[data-testid="admin-toggle-refund-throw"]').click();
    await page.locator('[data-testid="admin-refund"]').click();
    // The audit log should NOT carry a refund entry — the handler
    // body threw before the conj. (A normal refund click would have
    // added a row.)
    await expectCount(
      page.locator('[data-testid^="admin-audit-"]'),
      0,
    );
    // The page is still alive — click audit to confirm the runtime
    // didn't tear down.
    await page.locator('[data-testid="admin-audit"]').click();
    await expectVisible(page.locator('[data-testid="admin-audit-0"]'), 5000);
  },
};
