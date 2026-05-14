/*
 * Cross-cutting scenario #6 of 6 from rf2-fe84r §Section B.
 *
 *   "Drain-depth-exceeded produces app-db rollback + `:halted-depth`
 *    epoch record (rf2-v0jwt)."
 *
 * Exercises `testbeds/drain_depth_trigger/` (rf2-kzcim). The surface
 * ships ONE handler `::recurse` that always dispatches another
 * `::recurse` in its `:fx`. The frame's `:drain-depth` ceiling is
 * configurable (defaults to 25 in the testbed; the framework
 * default is 100); the runtime's run-to-completion drain hits the
 * ceiling, atomically rolls back to the pre-drain `app-db` snapshot,
 * and emits `:rf.error/drain-depth-exceeded`.
 *
 * What "`:halted-depth` epoch record" means here — per Spec
 * Spec-Schemas §`:rf/epoch-record` Outcomes + rf2-v0jwt, the failing
 * drain commits a single epoch record with:
 *
 *   :outcome     :halted-depth
 *   :db-before   == :db-after   (atomic rollback semantics)
 *   :halt-reason {:operation :rf.error/drain-depth-exceeded
 *                 :depth <ceiling>}
 *   :event-id    ::recurse              (the trigger event)
 *   :trace-events seq of trace emits leading up to the halt
 *
 * The cross-cutting contract reads:
 *   1. The halted record IS in `rf/epoch-history :rf/default`.
 *   2. `:outcome` is `:halted-depth`.
 *   3. `:db-before` equals `:db-after` — the rollback is atomic.
 *   4. `:depth-reached` mirror in app-db reads back to 0 — the
 *      handler's increments did NOT survive (the rollback wiped
 *      them).
 *
 * Trigger choice — keep the testbed's default drain-depth of 25 (low
 * enough for sub-second halt). Click Start. After the halt, the
 * surface's `install-halt-listener!` dispatches `::halt-observed`
 * which flips `:halted? true` — that drain runs cleanly AFTER the
 * rollback, so we can also assert the listener-fired mirror to
 * confirm the post-halt state machinery survived.
 */

const path = require('path');
const { expectVisible, expectTextEquals } = require(
  path.join('..', '..', 'examples', 'scripts', 'spec-helpers.cjs'),
);
const { readEpochHistoryAsEdn, pollUntil } = require(
  path.join('..', 'spec-helpers.cjs'),
);

module.exports = {
  name: 'cross-cutting #6 — drain-depth-exceeded rollback + :halted-depth epoch',
  url: '/testbeds/drain-depth-trigger/',
  run: async (page) => {
    await expectVisible(page.locator('[data-testid="drain-depth-trigger"]'), 10000);

    // Pre-conditions: the depth-reached mirror starts at 0, the
    // halted? mirror reads "false", the ceiling is 25 (the testbed's
    // default — we don't override).
    await expectTextEquals(page.locator('[data-testid="depth-reached"]'), '0', 5000);
    await expectTextEquals(page.locator('[data-testid="halted"]'), 'false', 5000);
    await expectTextEquals(page.locator('[data-testid="drain-depth-mirror"]'), '25', 5000);

    // Drive Start — `::recurse` dispatches itself in its `:fx`. The
    // runtime processes the queue in a tight drain; each `::recurse`
    // appends one more; the queue never empties; depth grows linearly
    // with iteration count.
    //
    // Per Spec 002 §Run-to-completion rule 3: the drain is depth-
    // bounded. When `:drain-depth` (25) is reached, the runtime
    // atomically rolls back to the snapshot taken at the start of
    // the drain. The handler's increments to `:depth-reached` are
    // discarded.
    await page.locator('[data-testid="start"]').click();

    // ---- Rollback evidence — `:depth-reached` reads back to 0 ------
    // Per Spec 002 rule 3 the rollback is atomic — the frame's
    // app-db is restored to the snapshot taken at the START of the
    // drain. `:depth-reached` was 0 at the start; the handler bumped
    // it to 25 during the runaway cascade; the rollback wiped those
    // bumps. The mirror reads 0 after the halt.
    //
    // Polling is required because the drain runs synchronously but
    // the React re-render lands on the next microtask. The post-
    // rollback mirror reading 0 — instead of 25 — is the canonical
    // proof rule 3's atomic-rollback fired.
    await expectTextEquals(page.locator('[data-testid="depth-reached"]'), '0', 10000);

    // ---- Epoch record — `:outcome :halted-depth` --------------------
    // Per rf2-v0jwt the failing drain commits a partial epoch record
    // with `:outcome :halted-depth`. Read `rf/epoch-history :rf/default`
    // (the testbed runs on the default frame) and walk for the record.
    //
    // The post-halt drain (from `::halt-observed`) commits a SECOND
    // epoch record with `:outcome :ok`; we pin our assertion to the
    // record whose outcome is `:halted-depth` to isolate from the
    // post-halt success.
    const records = await pollUntil(async () => {
      const probe = await readEpochHistoryAsEdn(page);
      if (!probe.ok) throw new Error(`could not read epoch history: ${probe.reason}`);
      const halted = probe.records.find((s) => /:outcome\s+:halted-depth/.test(s));
      if (halted) return { halted, all: probe.records };
      return null;
    }, ':outcome :halted-depth epoch record in rf/epoch-history :rf/default', 10000);

    // The halted record carries the structured halt descriptor
    // pointing at `:rf.error/drain-depth-exceeded`. Per the spec
    // `:halt-reason` is a sub-map; we regex-match on the operation
    // keyword inside it (pr-str renders nested maps inline so a
    // single regex over the record's string form works).
    if (!/:halt-reason\s+\{[^}]*:operation\s+:rf\.error\/drain-depth-exceeded/.test(records.halted)) {
      throw new Error(
        `:halt-reason should carry :operation :rf.error/drain-depth-exceeded; sample: ${records.halted}`,
      );
    }

    // The record's `:depth` slot pins the drain's ceiling — 25 (the
    // testbed's default-drain-depth). Reading `:depth` proves the
    // record captured the frame's ceiling, not a stale framework
    // default of 100.
    if (!/:depth\s+25/.test(records.halted)) {
      throw new Error(
        `:halt-reason should carry :depth 25 (testbed's drain-depth ceiling); sample: ${records.halted}`,
      );
    }

    // The record's `:event-id` carries the cascade's trigger event,
    // namespaced by the testbed: `:drain-depth-trigger.core/recurse`.
    if (!/:event-id\s+:drain-depth-trigger\.core\/recurse/.test(records.halted)) {
      throw new Error(
        `:event-id on the halted record should be :drain-depth-trigger.core/recurse; sample: ${records.halted}`,
      );
    }

    // Atomic-rollback check — per Spec 002 rule 3, `:db-before` and
    // `:db-after` are equal on the halted record. CLJS maps are
    // unordered, so a string regex on the pr-str form would false-
    // negative on key-order drift. Compare with `cljs.core/=` instead
    // by reaching into the epoch-history vector inside `page.evaluate`
    // — the same record we matched on by pr-str above.
    const dbsEqual = await page.evaluate(() => {
      const cljs = window.cljs && window.cljs.core;
      const rf = window.re_frame && window.re_frame.core;
      if (!cljs || !rf || typeof rf.epoch_history !== 'function') {
        return { ok: false, reason: 'cljs.core / re_frame.core.epoch_history not on window' };
      }
      const kw = (s) => cljs.keyword.call ? cljs.keyword.call(null, s) : cljs.keyword(s);
      const eq = (a, b) => cljs._EQ_.call ? cljs._EQ_.call(null, a, b) : cljs._EQ_(a, b);
      const get = (m, k) => cljs.get.call ? cljs.get.call(null, m, k) : cljs.get(m, k);
      const history = rf.epoch_history(kw('rf/default'));
      const halted = kw('halted-depth');
      const outcomeKw = kw('outcome');
      const beforeKw = kw('db-before');
      const afterKw = kw('db-after');
      let s = cljs.seq(history);
      while (s) {
        const r = cljs.first(s);
        if (eq(get(r, outcomeKw), halted)) {
          return { ok: true, equal: eq(get(r, beforeKw), get(r, afterKw)) };
        }
        s = cljs.next(s);
      }
      return { ok: false, reason: 'no :halted-depth record found' };
    });
    if (!dbsEqual.ok) {
      throw new Error(`atomic-rollback check failed: ${dbsEqual.reason}`);
    }
    if (!dbsEqual.equal) {
      throw new Error(
        ':db-before must equal :db-after on a :halted-depth record (atomic rollback per Spec 002 rule 3)',
      );
    }
  },
};
