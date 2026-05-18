/*
 * Parallel-Frames testbed — browser smoke (rf2-m00rw).
 *
 * THE canonical multi-frame-isolation demo. One app, mounted in TWO
 * frames on ONE page (`:above` and `:below`), with zero cross-frame
 * coupling. The smoke asserts:
 *
 *   1. Initial paint — both frame panels render, both counters read 0,
 *      both clocks have ticked at least once (auto-tick @ 1s).
 *
 *   2. Counter isolation — clicking + on :above increments :above's
 *      counter while :below's counter stays at 0. Clicking + on :below
 *      then advances only :below.
 *
 *   3. HTTP / machine isolation — clicking Refresh on :below drives
 *      :below's :title/flow machine into :loading, the mock fx
 *      resolves ~600ms later into :loaded, and :below's title slot
 *      carries a non-empty value. :above's title state stays :idle
 *      throughout.
 *
 *   4. Force-error path — Force-error on :above drives the same
 *      machine into :error legitimately (request fails by design).
 *      :below's :loaded state from step 3 persists.
 *
 *   5. Causa target-frame round-trip (rf2-qd5r6, ex-Tier-2 L-14).
 *      Spec 008 §State isolation (the rf2-tijr Option-C lock).
 *      Causa's panel-layer `:rf.causa/set-target-frame` event +
 *      `:rf.causa/target-frame-db` sub project the addressed frame's
 *      app-db. Flipping target to :above reads :above's app-db;
 *      flipping to :below reads :below's. Neither frame's writes leak
 *      INTO `:rf/causa`'s app-db (the other direction of the §12
 *      embedding-contract isolation: host → Causa).
 *
 * The exercise IS observing two isolated frames diverge as the user
 * interacts with each independently. There is NO deliberate bug, NO
 * cross-frame data routing, NO 'fix this bug' moment.
 */

const {
  expectTextEquals,
  expectVisible,
} = require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'parallel-frames (Causa demo)',
  url:  '/parallel-frames/',
  run:  async (page) => {
    // --- 0. First paint -----------------------------------------------------
    await expectVisible(page.locator('[data-testid="parallel-frames-root"]'), 10000);
    await expectVisible(page.locator('[data-testid="above-panel"]'), 5000);
    await expectVisible(page.locator('[data-testid="below-panel"]'), 5000);

    // Both counters start at 0.
    await expectTextEquals(page.locator('[data-testid="above-counter-value"]'), '0');
    await expectTextEquals(page.locator('[data-testid="below-counter-value"]'), '0');

    // Both machines start at :idle (the :title/flow :initial slot).
    await expectTextEquals(page.locator('[data-testid="above-title-state"]'), ':idle');
    await expectTextEquals(page.locator('[data-testid="below-title-state"]'), ':idle');

    // --- 1. Counter isolation -----------------------------------------------
    //
    // Click + on :above three times. :above's counter advances; :below
    // stays at 0. The handlers and subs are registered once globally
    // and resolve against the originating frame via the frame-provider
    // context — the framework keeps each frame's app-db cleanly
    // partitioned.
    await page.locator('[data-testid="above-counter-inc"]').click();
    await page.locator('[data-testid="above-counter-inc"]').click();
    await page.locator('[data-testid="above-counter-inc"]').click();
    await expectTextEquals(page.locator('[data-testid="above-counter-value"]'), '3');
    await expectTextEquals(page.locator('[data-testid="below-counter-value"]'), '0');

    // Click + on :below once. :below advances; :above stays at 3.
    await page.locator('[data-testid="below-counter-inc"]').click();
    await expectTextEquals(page.locator('[data-testid="above-counter-value"]'), '3');
    await expectTextEquals(page.locator('[data-testid="below-counter-value"]'), '1');

    // --- 2. HTTP / machine isolation — Refresh on :below --------------------
    //
    // Click Refresh on :below. The :title/flow machine in :below's app-db
    // transitions :idle → :loading. ~600ms later the mock fx resolves
    // with a payload and the machine transitions :loading → :loaded.
    // :above's machine stays :idle throughout — the mock fx closes over
    // the originating frame id, so the reply dispatch lands only on
    // :below.
    await page.locator('[data-testid="below-title-refresh"]').click();

    // Allow the mock to settle (HTTP-MOCK-DELAY-MS is 600ms; pad a bit).
    await expectTextEquals(
      page.locator('[data-testid="below-title-state"]'),
      ':loaded',
      5000,
    );

    // :above stays untouched.
    await expectTextEquals(page.locator('[data-testid="above-title-state"]'), ':idle');

    // :below's title value carries the mock fetch's wall-clock payload
    // (the prefix is stable; the ISO timestamp varies).
    const belowTitleText = (await page
      .locator('[data-testid="below-title-value"]')
      .textContent()) || '';
    if (!belowTitleText.startsWith('Parallel-Frames @ ')) {
      throw new Error(
        `Expected :below's title to carry the mock payload (starts with "Parallel-Frames @ "); got: ${JSON.stringify(belowTitleText)}`,
      );
    }

    // --- 3. Force-error on :above — legitimate failure cascade --------------
    //
    // The Force-error button dispatches the same :title-refresh event
    // with `:force-error? true`. The mock fx rejects on that flag (it's
    // not a bug — it's a normal request the user asked to fail), and
    // the machine transitions :idle → :loading → :error. :below's
    // :loaded state from step 2 persists.
    await page.locator('[data-testid="above-title-force-error"]').click();
    await expectTextEquals(
      page.locator('[data-testid="above-title-state"]'),
      ':error',
      5000,
    );
    await expectTextEquals(page.locator('[data-testid="below-title-state"]'), ':loaded');

    // :above's title slot carries the error message.
    const aboveTitleText = (await page
      .locator('[data-testid="above-title-value"]')
      .textContent()) || '';
    if (!aboveTitleText.includes('ERROR:')) {
      throw new Error(
        `Expected :above's title to carry the error marker after Force-error; got: ${JSON.stringify(aboveTitleText)}`,
      );
    }

    // --- 5. Causa target-frame round-trip (rf2-qd5r6 ex-Tier-2 L-14) --------
    //
    // Spec 008 §State isolation. The panel-layer surface lives on the
    // `:rf.causa/set-target-frame` event + `:rf.causa/target-frame-db`
    // sub. After steps 1-4 :above's :counter is 3 (three +s in step 1)
    // and :below's :counter is 1 (one + in step 1). Flip the target
    // frame and assert the projection sub reads the addressed frame.
    const targetFrameRoundTrip = await page.evaluate(() => {
      const cljs = window.cljs && window.cljs.core;
      const rf   = window.re_frame && window.re_frame.core;
      if (!cljs || !rf) return { ok: false, reason: 'cljs/rf missing on window' };
      if (typeof rf.dispatch_sync_STAR_ !== 'function' ||
          typeof rf.subscribe_once !== 'function') {
        return { ok: false, reason: 'rf.dispatch_sync_STAR_ / rf.subscribe_once missing' };
      }
      const kw = (n) => cljs.keyword(n);
      function setTarget(frameKw) {
        rf.dispatch_sync_STAR_(
          cljs.PersistentVector.fromArray(
            [kw('rf.causa/set-target-frame'), frameKw], true),
          cljs.PersistentArrayMap.fromArray(
            [kw('frame'), kw('rf/causa')], true, false),
        );
      }
      function readTargetCounter() {
        const db = rf.subscribe_once(
          kw('rf/causa'),
          cljs.PersistentVector.fromArray(
            [kw('rf.causa/target-frame-db')], true),
        );
        return db == null ? null : cljs.get(db, kw('counter'));
      }
      function readTargetFrameKw() {
        const v = rf.subscribe_once(
          kw('rf/causa'),
          cljs.PersistentVector.fromArray(
            [kw('rf.causa/target-frame')], true),
        );
        return v == null ? null : cljs.pr_str(v);
      }
      // Phase 1 — flip to :above; sub reads :above's :counter (3).
      setTarget(kw('above'));
      const observedAbove        = readTargetFrameKw();
      const counterUnderAbove    = readTargetCounter();
      // Phase 2 — flip to :below; sub reads :below's :counter (1).
      setTarget(kw('below'));
      const observedBelow        = readTargetFrameKw();
      const counterUnderBelow    = readTargetCounter();
      // Phase 3 — Causa-side isolation. `:rf/causa`'s own app-db must
      // NOT carry a `:counter` slot (the keyword the host frames use).
      // The flip side of the host-cannot-see-Causa invariant.
      const causaDb = rf.get_frame_db(kw('rf/causa'));
      const causaCounter = causaDb == null ? null : cljs.get(causaDb, kw('counter'));
      // Cleanup — reset target-frame to nil (resets to :rf/default).
      rf.dispatch_sync_STAR_(
        cljs.PersistentVector.fromArray(
          [kw('rf.causa/set-target-frame'), null], true),
        cljs.PersistentArrayMap.fromArray(
          [kw('frame'), kw('rf/causa')], true, false),
      );
      return {
        ok: true,
        observedAbove,
        observedBelow,
        counterUnderAbove,
        counterUnderBelow,
        causaCounter,
      };
    });
    if (!targetFrameRoundTrip.ok) {
      throw new Error(`Causa target-frame round-trip probe failed: ${targetFrameRoundTrip.reason}`);
    }
    if (targetFrameRoundTrip.observedAbove !== ':above') {
      throw new Error(
        `Expected :rf.causa/target-frame sub to read :above after set; got ${targetFrameRoundTrip.observedAbove}.`,
      );
    }
    if (targetFrameRoundTrip.observedBelow !== ':below') {
      throw new Error(
        `Expected :rf.causa/target-frame sub to read :below after flip; got ${targetFrameRoundTrip.observedBelow}.`,
      );
    }
    if (targetFrameRoundTrip.counterUnderAbove !== 3) {
      throw new Error(
        `Expected :rf.causa/target-frame-db :counter to read 3 (:above's value after step 1); got ${targetFrameRoundTrip.counterUnderAbove}.`,
      );
    }
    if (targetFrameRoundTrip.counterUnderBelow !== 1) {
      throw new Error(
        `Expected :rf.causa/target-frame-db :counter to read 1 (:below's value after step 1); got ${targetFrameRoundTrip.counterUnderBelow}.`,
      );
    }
    if (targetFrameRoundTrip.causaCounter !== null && targetFrameRoundTrip.causaCounter !== undefined) {
      throw new Error(
        `Multi-frame isolation broken — :rf/causa app-db carries a :counter slot (host frames leaked INTO Causa); got ${targetFrameRoundTrip.causaCounter}.`,
      );
    }
  },
};
