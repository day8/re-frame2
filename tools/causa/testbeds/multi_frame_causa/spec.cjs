/*
 * Multi-frame Causa testbed — browser smoke (rf2-2vgog).
 *
 * Three frames coexist on the page — :cart-frame, :checkout-frame,
 * :admin-frame — each owning its own events / subs / app-db slot. One
 * deliberate cross-frame coordination scenario: the cart's
 * `Send to checkout` button dispatches `:cart/send-to-checkout` on
 * :cart-frame; the handler fans out a `[:checkout/start <items>]`
 * envelope at :checkout-frame via the testbed bridge fx.
 *
 * This spec asserts:
 *
 *   1. All three frame panels render and start empty.
 *
 *   2. Frame-scoped events stay local: clicking buttons in one frame's
 *      panel mutates only that frame's app-db (the other two frames'
 *      visible state stays put).
 *
 *   3. Cross-frame coordination: clicking Send-to-checkout on
 *      :cart-frame drains items into :checkout-frame's view, leaves
 *      :cart-frame empty, and never touches :admin-frame.
 *
 *   4. Causa shell mounted via the preload — the ribbon's frame picker
 *      enumerates all three named frames (plus :rf/default that the
 *      runtime always exposes).
 *
 *   5. Switching the picker to each frame in turn keeps Causa's chrome
 *      stable (no errors, the L4 detail panel stays mounted) — the
 *      minimum-viable signal that the framework's per-frame trace
 *      ring buffers exist and are readable.
 */

const {
  expectTextEquals,
  expectVisible,
  expectCount,
} = require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'multi-frame (Causa)',
  url:  '/multi-frame-causa/',
  run:  async (page) => {
    // --- 0. Wait for first paint ---------------------------------------------
    await expectVisible(page.locator('[data-testid="multi-frame-causa-root"]'), 10000);
    await expectVisible(page.locator('[data-testid="cart-frame-panel"]'), 5000);
    await expectVisible(page.locator('[data-testid="checkout-frame-panel"]'), 5000);
    await expectVisible(page.locator('[data-testid="admin-frame-panel"]'), 5000);

    // Initial state — every frame's slot is empty / zero.
    await expectTextEquals(page.locator('[data-testid="cart-total"]'),     '$0.00');
    await expectTextEquals(page.locator('[data-testid="checkout-total"]'), '$0.00');
    await expectTextEquals(page.locator('[data-testid="admin-tx-count"]'), '0');
    await expectTextEquals(page.locator('[data-testid="checkout-status"]'), ':idle');

    // --- 1. Frame-scoped events stay local ------------------------------------
    //
    // Click +Apple (cart) +Bread (cart) +Coffee (cart) → cart-frame's
    // :cart/items grows; checkout and admin slots are unchanged.
    await page.locator('[data-testid="cart-add-apple"]').click();
    await page.locator('[data-testid="cart-add-bread"]').click();
    await page.locator('[data-testid="cart-add-coffee"]').click();
    // Apple ($1.00) + Bread ($3.00) + Coffee ($5.00) = $9.00.
    await expectTextEquals(page.locator('[data-testid="cart-total"]'),     '$9.00');
    await expectTextEquals(page.locator('[data-testid="checkout-total"]'), '$0.00');
    await expectTextEquals(page.locator('[data-testid="admin-tx-count"]'), '0');

    // Click admin-refund + admin-audit → admin-frame populates; cart +
    // checkout slots stay frozen at their current values.
    await page.locator('[data-testid="admin-refund"]').click();
    await page.locator('[data-testid="admin-audit"]').click();
    await expectTextEquals(page.locator('[data-testid="admin-refund-count"]'), '1');
    await expectTextEquals(page.locator('[data-testid="admin-audit-count"]'),  '1');
    await expectTextEquals(page.locator('[data-testid="admin-tx-count"]'),     '2');
    // Cart + checkout — unchanged.
    await expectTextEquals(page.locator('[data-testid="cart-total"]'),     '$9.00');
    await expectTextEquals(page.locator('[data-testid="checkout-total"]'), '$0.00');

    // --- 2. Cross-frame coordination ------------------------------------------
    //
    // Send-to-checkout on :cart-frame drains the cart's items into
    // :checkout-frame via the bridge fx. After the click:
    //   - :cart-frame  → :cart/items empty, total $0.00
    //   - :checkout-frame → carries the snapshot, total $9.00
    //   - :admin-frame → unchanged (cascade isolation)
    await page.locator('[data-testid="cart-send-to-checkout"]').click();
    await expectTextEquals(page.locator('[data-testid="cart-total"]'),     '$0.00');
    await expectTextEquals(page.locator('[data-testid="checkout-total"]'), '$9.00');
    await expectTextEquals(page.locator('[data-testid="admin-tx-count"]'), '2');

    // Checkout lines materialised — three distinct testids, one per item.
    await expectVisible(page.locator('[data-testid="checkout-line-apple"]'),  5000);
    await expectVisible(page.locator('[data-testid="checkout-line-bread"]'),  5000);
    await expectVisible(page.locator('[data-testid="checkout-line-coffee"]'), 5000);

    // The :cart-frame's line testids are gone — cart was cleared by
    // :cart/send-to-checkout before the cross-frame dispatch.
    await expectCount(page.locator('[data-testid^="cart-line-"]'), 0);

    // Pay → :checkout/status flips to :paid. Local to :checkout-frame;
    // cart / admin slots stay put.
    await page.locator('[data-testid="checkout-pay"]').click();
    await expectTextEquals(page.locator('[data-testid="checkout-status"]'), ':paid');
    await expectTextEquals(page.locator('[data-testid="cart-total"]'),     '$0.00');
    await expectTextEquals(page.locator('[data-testid="admin-tx-count"]'), '2');

    // --- 3. Causa shell mounted via the preload -------------------------------
    //
    // The :testbeds/multi-frame build wires day8.re-frame2-causa.preload
    // in `:devtools/:preloads` — Causa auto-mounts inline against the
    // page's `[data-rf-causa-host]` aside without a Ctrl+Shift+C
    // toggle. Bring up the ribbon to read the frame picker.
    await expectVisible(page.locator('[data-testid="rf-causa-shell"]'),  10000);
    await expectVisible(page.locator('[data-testid="rf-causa-ribbon"]'),  5000);

    // --- 4. Frame picker enumerates all three frames --------------------------
    //
    // After the clicks above each of :cart-frame, :checkout-frame, and
    // :admin-frame has at least one event in its ring buffer, so the
    // picker's `distinct-frames` produces ≥ 3 entries — well above the
    // single-entry collapse-to-label branch in `ribbon-frame-picker`.
    const picker = page.locator('[data-testid="rf-causa-ribbon-frame-picker"]');
    await expectVisible(picker, 5000);

    const optionValues = await picker.locator('option').evaluateAll((els) =>
      els.map((el) => el.value),
    );
    const requiredFrames = [':cart-frame', ':checkout-frame', ':admin-frame'];
    for (const f of requiredFrames) {
      if (!optionValues.includes(f)) {
        throw new Error(
          `Frame picker missing ${f}. Got options: ${JSON.stringify(optionValues)}`,
        );
      }
    }

    // --- 5. Switching frames keeps Causa chrome stable ------------------------
    //
    // For each named frame, select it via the picker and confirm the
    // L4 detail panel stays mounted (it rebinds per-tab — the default
    // landing tab is :event). This is the minimum-viable signal that
    // the framework's per-frame trace ring buffers are readable and
    // panel rendering scoped per the selection doesn't blow up.
    for (const f of requiredFrames) {
      await picker.selectOption(f);
      await expectVisible(
        page.locator('[data-testid="rf-causa-detail-panel-event"]'),
        5000,
      );
    }
  },
};
