/*
 * Cross-cutting scenario #4 of 6 from rf2-fe84r §Section B.
 *
 *   "Subscribe → re-render → trace ordering preserved."
 *
 * Exercises `testbeds/non_trivial_app_db/` (rf2-kzcim). The surface
 * ships a 55-leaf app-db nested up to 5 levels deep, with six buttons
 * each triggering a structurally distinct diff at a known path.
 *
 * What "trace ordering preserved" means here — Spec 009 §:op-type
 * vocabulary + Spec 002 §Cascade propagation guarantee a strict
 * temporal order in the trace stream for a single dispatched event:
 *
 *   1. `:event/dispatched`   — at queue/enqueue time (router emits)
 *   2. `:event/db-changed`   — after the handler's `:db` commits
 *
 * (Sub-runs and view-renders re-fire downstream of `:db` commit; per
 * the bead's "subscribe → re-render" half of the contract those land
 * in the same drain as the `:db` commit but emit `:sub/run` /
 * `:view/render` trace events on separate `:op-type` axes — the
 * ordering we test here is the event/* axis only. `:event/do-fx`
 * fires when the handler returns `:fx`, but this testbed's handlers
 * are all `reg-event-db` (`:db`-only), so `:event/do-fx` is OUT of
 * scope for this surface.)
 *
 * The cross-cutting contract for this scenario: a click that triggers
 * a structural mutation lands its `:event/dispatched` BEFORE its
 * `:event/db-changed` trace event (the buffer is append-order; index
 * comparison preserves the emit order). The substrate re-renders
 * against the new app-db; the DOM updates; reading the mirror
 * confirms the cascade settled. The trace ordering + the
 * subscribe-fired DOM update prove the six-domino cascade fired in
 * spec order.
 *
 * Trigger choice — Button 5 (`:register-new-sku`) mutates the deepest
 * path in the surface: `[:catalog :categories :books :groups :tech
 * :skus]` (5 levels). The depth doesn't change the trace ordering
 * (`:event/db-changed` fires regardless of mutation depth) but it
 * exercises the most realistic post-handler state.
 *
 * Anchoring on event-id `non-trivial-app-db.core/register-new-sku`
 * lets us scope the find() to the trace events FROM THIS CLICK —
 * subscriptions firing on init, layout-debug toggles, etc all add
 * unrelated events to the buffer before and after the click.
 */

const path = require('path');
const { expectVisible } = require(
  path.join('..', '..', 'examples', 'scripts', 'spec-helpers.cjs'),
);
const { readTraceEventsAsEdn, clearTraceBus, pollUntil } = require(
  path.join('..', 'spec-helpers.cjs'),
);

module.exports = {
  name: 'cross-cutting #4 — subscribe→re-render→trace ordering preserved',
  url: '/testbeds/non-trivial-app-db/',
  run: async (page) => {
    await expectVisible(page.locator('[data-testid="non-trivial-app-db"]'), 10000);

    // Pre-condition: the app-db pretty-print does NOT contain "BK-099"
    // (the SKU Button 5 adds). The mirror is a sub off `:catalog`,
    // which subscribes to the whole `:catalog` slice — proves the
    // initial app-db shape landed.
    const dbPre = page.locator('[data-testid="app-db-pr-str"]');
    if (((await dbPre.textContent()) || '').includes('BK-099')) {
      throw new Error('app-db starts with BK-099 already present — testbed init drifted');
    }

    // Clear the trace bus so the ordering assertion below scopes to
    // the events emitted by THIS click. The bootstrap dispatch
    // (`::initialise`) and substrate init landed many `:event/*` /
    // `:sub/*` traces before the test runs.
    const cleared = await clearTraceBus(page);
    if (!cleared.ok) {
      throw new Error(`could not clear trace bus: ${cleared.reason}`);
    }

    // Drive Button 5 — `:register-new-sku` is a `reg-event-db` whose
    // body adds "BK-099" to a 5-level-deep set under :catalog.
    await page.locator('[data-testid="register-new-sku"]').click();

    // Poll until the cascade's three canonical trace events are
    // present in the buffer. Each carries `:id` (monotonic across
    // emit order) so the buffer's append-order index can stand in
    // for the emit ordering.
    //
    // The trace bus was cleared just before the click, so any
    // `:event/dispatched` / `:event/db-changed` / `:event/do-fx` in
    // the buffer below originate from THIS cascade or whatever
    // initialise / reg-* emissions ran in the same tick (which all
    // happened BEFORE the cascade we're testing — they'd be at
    // earlier indices if present). Scoping by event-id keyword
    // would be more rigorous but is sensitive to namespace-munge
    // surprises; the empty-buffer-before-click design provides the
    // same cascade isolation more robustly.
    const observed = await pollUntil(async () => {
      const probe = await readTraceEventsAsEdn(page);
      if (!probe.ok) throw new Error(`could not read trace bus: ${probe.reason}`);
      // `:event/dispatched`'s tags carry `:event [<event-vector>]`
      // (not `:event-id ...` directly — that key only appears on
      // `:event/db-changed`, per `re-frame.router/emit-dispatched-
      // trace!` vs `commit-effect-:db!`). Match the event-vector's
      // shape with "register-new-sku" inside it.
      const dispatched = probe.events
        .map((s, i) =>
          /:operation\s+:event\/dispatched/.test(s) && /register-new-sku/.test(s)
            ? i
            : -1,
        )
        .filter((i) => i >= 0)
        .pop();
      if (dispatched == null || dispatched < 0) return null;
      // `:event/db-changed` fires from `commit-effect-:db!` after the
      // handler's `:db` lands. The buffer's monotonic `:id` means a
      // later append-order index proves a later emit time. Find the
      // first `:event/db-changed` AFTER the dispatch index.
      const dbChanged = probe.events.findIndex(
        (s, i) => i > dispatched && /:operation\s+:event\/db-changed/.test(s),
      );
      if (dbChanged >= 0) {
        return { dispatched, dbChanged };
      }
      return null;
    }, 'an :event/dispatched for register-new-sku followed by :event/db-changed', 10000);

    // Ordering contract — per Spec 009 §:op-type vocabulary and Spec
    // 002 §Cascade propagation: `:event/dispatched < :event/db-changed`.
    // The buffer is append-order, so the index comparison preserves
    // the emit order. A spec violation (e.g. `:event/db-changed`
    // firing before its enqueue-time `:event/dispatched`) would
    // surface as an out-of-order index here.
    if (!(observed.dispatched < observed.dbChanged)) {
      throw new Error(
        `:event/dispatched must precede :event/db-changed for the same cascade; ` +
          `got indices dispatched=${observed.dispatched} dbChanged=${observed.dbChanged}`,
      );
    }

    // Sub re-fired → view re-rendered → DOM updated. Reading the
    // mirror confirms the subscribe → render half of the cascade
    // settled after the trace bus saw the `:event/do-fx` emit. The
    // pretty-print sub aggregates every top-level slice; the
    // mirror's text picks up "BK-099" once the new sku lands in the
    // `:tech :skus` set.
    //
    // This is the "subscribe → re-render → trace ordering preserved"
    // contract's second half: the view's re-render (downstream of the
    // sub firing on the app-db write) lands AFTER the trace bus's
    // db-changed emit but in the SAME drain — the substrate commits
    // to the DOM as part of the same RAF tick that flushed the
    // re-frame queue. We poll the mirror because the React commit
    // settles a tick after the trace emit.
    await pollUntil(async () => {
      const txt = (await dbPre.textContent()) || '';
      return txt.includes('BK-099');
    }, 'app-db mirror to re-render with "BK-099" after :register-new-sku cascade', 10000);
  },
};
