/*
 * Parallel-Frames testbed — browser smoke (rf2-m00rw).
 *
 * THE canonical multi-frame-isolation demo. One app, mounted in TWO
 * frames on ONE page (`:above` and `:below`), with zero cross-frame
 * coupling. The smoke asserts:
 *
 *   1. Initial paint — both frame panels render, both counters read 0,
 *      both clock-tick counters read 0 (rf2-gxgmt: ticks are on-demand
 *      via the per-frame Tick button; the auto-tick chain was retired).
 *
 *   2. Counter isolation — clicking + on :above increments :above's
 *      counter while :below's counter stays at 0. Clicking + on :below
 *      then advances only :below.
 *
 *   3. Clock-tick isolation (rf2-gxgmt) — clicking Tick on :above
 *      increments only :above's tick counter; clicking Tick on :below
 *      increments only :below's. The same `::clock-tick` handler is
 *      registered once globally and resolves against whichever frame
 *      the dispatch envelope targets via the frame-provider context.
 *
 *   4. HTTP / machine isolation — clicking Refresh on :below drives
 *      :below's :title/flow machine into :loading, the mock fx
 *      resolves ~600ms later into :loaded, and :below's title slot
 *      carries a non-empty value. :above's title state stays :idle
 *      throughout.
 *
 *   5. Force-error path — Force-error on :above drives the same
 *      machine into :error legitimately (request fails by design).
 *      :below's :loaded state from step 4 persists.
 *
 *   6. Causa target-frame round-trip (rf2-qd5r6, ex-Tier-2 L-14).
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

    // Both clock-tick counters start at 0 (rf2-gxgmt — on-demand via
    // the per-frame Tick button; no auto-tick chain).
    await expectTextEquals(page.locator('[data-testid="above-clock-ticks"]'), '0 ticks');
    await expectTextEquals(page.locator('[data-testid="below-clock-ticks"]'), '0 ticks');

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

    // --- 2. Clock-tick isolation (rf2-gxgmt) --------------------------------
    //
    // Click Tick on :above twice. :above's tick counter advances to 2;
    // :below's stays at 0. Then click Tick on :below once. :below
    // advances to 1; :above stays at 2. The same `::clock-tick`
    // handler is registered once globally and resolves against
    // whichever frame the dispatch envelope targets via the
    // frame-provider context — proving on-demand per-frame isolation
    // without the spine-pollution cost of the retired auto-tick chain.
    await page.locator('[data-testid="above-clock-tick"]').click();
    await page.locator('[data-testid="above-clock-tick"]').click();
    await expectTextEquals(page.locator('[data-testid="above-clock-ticks"]'), '2 ticks');
    await expectTextEquals(page.locator('[data-testid="below-clock-ticks"]'), '0 ticks');

    await page.locator('[data-testid="below-clock-tick"]').click();
    await expectTextEquals(page.locator('[data-testid="above-clock-ticks"]'), '2 ticks');
    await expectTextEquals(page.locator('[data-testid="below-clock-ticks"]'), '1 tick');

    // --- 3. HTTP / machine isolation — Refresh on :below --------------------
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

    // --- 4. Force-error on :above — legitimate failure cascade --------------
    //
    // The Force-error button dispatches the same :title-refresh event
    // with `:force-error? true`. The mock fx rejects on that flag (it's
    // not a bug — it's a normal request the user asked to fail), and
    // the machine transitions :idle → :loading → :error. :below's
    // :loaded state from step 3 persists.
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

    // --- 6. Causa target-frame round-trip + multi-frame isolation -----------
    //     (rf2-qd5r6 ex-Tier-2 L-14)
    //
    // Spec 008 §State isolation (rf2-tijr Option-C frame-provider lock).
    // The Causa-side surface to verify is two-fold:
    //
    //   - `:rf.causa/set-target-frame` event + `:rf.causa/target-frame`
    //     sub round-trip on Causa's own :rf/causa app-db.
    //   - Per-frame app-db isolation — `:above` and `:below` carry
    //     distinct `:counter` slots (3 and 1 respectively after step 1)
    //     reachable via `rf.get-frame-db`, and neither slot leaks INTO
    //     `:rf/causa`'s app-db.
    //
    // The panel-layer `:rf.causa/target-frame-db` sub is deliberately
    // NOT asserted here: per rf2-fvplw it follows the spine focus
    // (`:rf.causa/observed-frame` = `(or (:frame focus) target)`), so
    // its readback depends on which cascade is currently focused
    // rather than the underlying isolation contract. The contract
    // worth pinning here is the underlying isolation + the target-
    // frame slot round-trip.
    const isolationRoundTrip = await page.evaluate(() => {
      const cljs = window.cljs && window.cljs.core;
      const rf   = window.re_frame && window.re_frame.core;
      if (!cljs || !rf) return { ok: false, reason: 'cljs/rf missing on window' };
      if (typeof rf.dispatch_sync_STAR_ !== 'function' ||
          typeof rf.subscribe_once !== 'function' ||
          typeof rf.get_frame_db !== 'function') {
        return { ok: false, reason: 'rf.dispatch_sync_STAR_ / rf.subscribe_once / rf.get_frame_db missing' };
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
      function readTargetFrameKw() {
        const v = rf.subscribe_once(
          kw('rf/causa'),
          cljs.PersistentVector.fromArray(
            [kw('rf.causa/target-frame')], true),
        );
        return v == null ? null : cljs.pr_str(v);
      }
      // Phase 1 — target-frame round-trips on the :rf/causa slot.
      setTarget(kw('above'));
      const observedAbove = readTargetFrameKw();
      setTarget(kw('below'));
      const observedBelow = readTargetFrameKw();
      setTarget(null);
      const observedReset = readTargetFrameKw();
      // Phase 2 — per-frame app-db isolation. Read each frame's db
      // directly; assert per-frame `:counter` values match what the
      // earlier steps drove + Causa's app-db carries no leak.
      const aboveDb = rf.get_frame_db(kw('above'));
      const belowDb = rf.get_frame_db(kw('below'));
      const causaDb = rf.get_frame_db(kw('rf/causa'));
      const counterAbove = aboveDb == null ? null : cljs.get(aboveDb, kw('counter'));
      const counterBelow = belowDb == null ? null : cljs.get(belowDb, kw('counter'));
      const causaCounter = causaDb == null ? null : cljs.get(causaDb, kw('counter'));
      return {
        ok: true,
        observedAbove,
        observedBelow,
        observedReset,
        counterAbove,
        counterBelow,
        causaCounter,
      };
    });
    if (!isolationRoundTrip.ok) {
      throw new Error(`Causa target-frame round-trip probe failed: ${isolationRoundTrip.reason}`);
    }
    if (isolationRoundTrip.observedAbove !== ':above') {
      throw new Error(
        `Expected :rf.causa/target-frame to read :above after set; got ${isolationRoundTrip.observedAbove}.`,
      );
    }
    if (isolationRoundTrip.observedBelow !== ':below') {
      throw new Error(
        `Expected :rf.causa/target-frame to read :below after flip; got ${isolationRoundTrip.observedBelow}.`,
      );
    }
    // The reset dispatches `:rf.causa/set-target-frame nil`. Per
    // epoch.cljs the handler dissocs `:target-frame` (so the sub
    // falls back to the default) AND seeds `:epoch-history`. The
    // contract is "reset moved away from host frames" — exact reset
    // value depends on defaults.cljs/default-target-frame.
    if (isolationRoundTrip.observedReset === ':above' ||
        isolationRoundTrip.observedReset === ':below') {
      throw new Error(
        `Expected :rf.causa/target-frame to reset away from host frames on nil; got ${isolationRoundTrip.observedReset}.`,
      );
    }
    if (isolationRoundTrip.counterAbove !== 3) {
      throw new Error(
        `Multi-frame isolation broken — expected :above's :counter to be 3 (three +s in step 1); got ${isolationRoundTrip.counterAbove}.`,
      );
    }
    if (isolationRoundTrip.counterBelow !== 1) {
      throw new Error(
        `Multi-frame isolation broken — expected :below's :counter to be 1 (one + in step 1); got ${isolationRoundTrip.counterBelow}.`,
      );
    }
    if (isolationRoundTrip.causaCounter !== null && isolationRoundTrip.causaCounter !== undefined) {
      throw new Error(
        `Multi-frame isolation broken — :rf/causa app-db carries a :counter slot (host frames leaked INTO Causa); got ${isolationRoundTrip.causaCounter}.`,
      );
    }
  },
};
