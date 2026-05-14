/*
 * Cross-cutting scenario #5 of 6 from rf2-fe84r §Section B.
 *
 *   "Flow `:rf.flow/failed` shows four-rule failure semantics
 *    (rf2-hrqvg)."
 *
 * Exercises `testbeds/long_flow_w_failure/` (rf2-kzcim). The surface
 * drives a cascade of ticks; each tick bumps `:input` and triggers
 * three flows in topo order (`::flow-a` → `::flow-b` → `::flow-c`).
 * `::flow-b` throws when `:input ≥ :fail-at`. Per Spec 013 §Failure
 * semantics (resolved by rf2-hrqvg) the four-rule contract is:
 *
 *   Rule 1 — prior-flow writes preserved: `::flow-a`'s output IS
 *            flushed on the tick `::flow-b` throws.
 *   Rule 2 — failing flow's output NOT written; `last-inputs` is
 *            NOT advanced; the flow re-attempts on every subsequent
 *            tick that bumps the input.
 *   Rule 3 — cascade halts at the failing flow: `::flow-c` does
 *            NOT run on the drain `::flow-b` throws.
 *   Rule 4 — per-flow `:rf.flow/failed` trace fires first; then the
 *            cascade-level `:rf.error/flow-eval-exception` at the
 *            router's outer catch.
 *
 * Trigger choice — use the testbed's defaults (`:fail-at 5`,
 * `:total-ticks 20`). Tick 5 first throws (`:input 5 ≥ :fail-at 5`);
 * ticks 6..20 each re-throw per Rule 2. The full cascade is 5
 * seconds (20 ticks × 250ms) but we don't wait for completion —
 * scoping the assertion to "first 4-5 failures observed in trace
 * order" is sufficient to demonstrate all four rules:
 *
 *   - Rule 1 observable mid-cascade: `:a-result` advances at every
 *     tick (we sample after the first failure lands).
 *   - Rule 2 observable mid-cascade: multiple `:rf.flow/failed`
 *     emits accumulate while `:input` keeps advancing past
 *     `:fail-at` — proves `:last-inputs` stayed unadvanced.
 *   - Rule 3 observable mid-cascade: `:c-result` stays at its
 *     pre-failure value (sum of `:a-result` + `:b-result` at the
 *     last successful tick, = 4×2 + 4×3 = 20).
 *   - Rule 4 observable on the trace bus: `:rf.flow/failed` emits
 *     precede the matching `:rf.error/flow-eval-exception`.
 *
 * Avoiding the configure-via-input race — Playwright's `.fill()`
 * clears the input first, firing an intermediate `parseInt("", 10)
 * === NaN` dispatch through `::set-fail-at` / `::set-total-ticks`
 * that pollutes the cascade's read-time state. Using the testbed's
 * built-in defaults sidesteps that entirely; the cascade is
 * deterministic without any pre-Start tweaking.
 */

const path = require('path');
const { expectVisible, expectTextEquals } = require(
  path.join('..', '..', 'examples', 'scripts', 'spec-helpers.cjs'),
);
const { readTraceEventsAsEdn, clearTraceBus, pollUntil } = require(
  path.join('..', 'spec-helpers.cjs'),
);

module.exports = {
  name: 'cross-cutting #5 — flow :rf.flow/failed four-rule semantics',
  url: '/testbeds/long-flow-w-failure/',
  run: async (page) => {
    await expectVisible(page.locator('[data-testid="long-flow-w-failure"]'), 10000);

    // Clear the trace bus so the find() below scopes to events
    // emitted by THIS cascade. Earlier `:rf.flow/registered` and
    // `:event/dispatched` traces from substrate init are otherwise
    // in the buffer.
    const cleared = await clearTraceBus(page);
    if (!cleared.ok) {
      throw new Error(`could not clear trace bus: ${cleared.reason}`);
    }

    // Start the cascade. The testbed pre-schedules 20 ticks at 250ms
    // each = 5-second total cascade. Tick 5 first throws; ticks 6..20
    // each re-throw.
    await page.locator('[data-testid="start"]').click();

    // Sanity probe — wait for the FIRST tick to bump `:input` off 0.
    // If the cascade scheduled correctly the input mirror reads >0
    // within ~250ms. If this hangs the cascade itself isn't firing
    // — surface that with a clear error before the trace assertion
    // times out with the same root cause.
    await pollUntil(async () => {
      const txt = (await page.locator('[data-testid="input"]').textContent()) || '';
      const v = parseInt(txt.trim(), 10);
      return Number.isFinite(v) && v > 0 ? v : null;
    }, ':input mirror to advance past 0 (cascade scheduled at least one tick)', 10000);

    // ---- Rule 4 — `:rf.flow/failed` before matching `:rf.error/flow-eval-exception` ----
    // Per `re-frame.flows/dirty-check-and-rerun-flow`:
    //   1. The flow's `:output` throws inside the inner try/catch.
    //   2. The catch emits `:rf.flow/failed` with `:flow-id ::flow-b`.
    //   3. The catch re-throws.
    //   4. `run-flows!` propagates to the router's outer catch which
    //      emits the cascade-level `:rf.error/flow-eval-exception`.
    //
    // The buffer is append-order; index comparison preserves the
    // emit order. Poll until we see the pair land (first failure
    // arrives ~tick-5 × 250ms = 1.25s after Start).
    const observed = await pollUntil(async () => {
      const probe = await readTraceEventsAsEdn(page);
      if (!probe.ok) throw new Error(`could not read trace bus: ${probe.reason}`);
      const failedIdx = probe.events.findIndex((s) =>
        /:operation\s+:rf\.flow\/failed/.test(s) &&
        /:flow-id\s+:long-flow-w-failure\.core\/flow-b/.test(s),
      );
      const errIdx = probe.events.findIndex((s) =>
        /:operation\s+:rf\.error\/flow-eval-exception/.test(s),
      );
      if (failedIdx >= 0 && errIdx >= 0 && failedIdx < errIdx) {
        return { failedIdx, errIdx };
      }
      return null;
    }, ':rf.flow/failed for ::flow-b followed by :rf.error/flow-eval-exception', 15000);
    void observed; // load-bearing assertion above

    // ---- Rule 1 — prior-flow writes preserved (`::flow-a` advanced) ---
    // `::flow-a` doubles `:input`; the cascade started with `:input 0`
    // and pre-schedules 20 ticks. By the time the first
    // `:rf.flow/failed` lands at tick 5, `:a-result` has advanced
    // through 2 (tick 1), 4 (tick 2), 6 (tick 3), 8 (tick 4), 10
    // (tick 5). The flow ran on EVERY tick — Rule 1 is "prior-flow
    // writes preserved".
    //
    // Sample `:a-result` after the failure observation above; it
    // must be ≥ 10 (the value at tick 5, where `:flow-b` first
    // throws). Reading the live value via DOM lets the assertion be
    // robust to ticks landing AFTER the failure observation (the
    // cascade keeps running for ~3.75s more by the time we read).
    const aResultText = (await page.locator('[data-testid="a-result"]').textContent()) || '';
    const aResult = parseInt(aResultText.trim(), 10);
    if (!Number.isFinite(aResult) || aResult < 10) {
      throw new Error(
        `Rule 1 evidence — expected :a-result >= 10 after first :flow-b failure (=:2*input at tick 5); got "${aResultText}"`,
      );
    }

    // ---- Rule 2 — failing flow's output NOT written --------------
    // `::flow-b` triples `:input` UNTIL `:input ≥ :fail-at`. Last
    // successful tick was 4 (input < 5), so `:b-result` reads 12.
    // Subsequent ticks throw — Rule 2 says `:last-inputs` did NOT
    // advance, so the path slot stays at 12 regardless of how many
    // re-attempts the runtime fires.
    //
    // Poll for the value because the testbed's React commit lands
    // a tick after the underlying app-db write.
    await expectTextEquals(page.locator('[data-testid="b-result"]'), '12', 10000);

    // ---- Rule 3 — cascade halts at failing flow ------------------
    // `::flow-c` sums `:a-result + :b-result`. Last successful tick
    // (where BOTH upstreams advanced) was 4: a=8, b=12, c=20. After
    // tick 4 every drain halts at `:flow-b`'s throw — `::flow-c`
    // does NOT run, so `:c-result` stays at 20.
    await expectTextEquals(page.locator('[data-testid="c-result"]'), '20', 10000);

    // ---- Rule 2 supplemental — re-attempt cardinality ------------
    // Each tick past `:fail-at` re-throws because `:last-inputs`
    // stayed unadvanced. So the trace buffer accumulates MULTIPLE
    // `:rf.flow/failed` emits (one per post-fail tick). Asserting
    // ≥ 3 distinct emits proves the re-attempt loop fired more than
    // once — the "every subsequent tick re-attempts" half of Rule 2.
    await pollUntil(async () => {
      const probe = await readTraceEventsAsEdn(page);
      if (!probe.ok) throw new Error(`could not read trace bus: ${probe.reason}`);
      const failures = probe.events.filter((s) =>
        /:operation\s+:rf\.flow\/failed/.test(s) &&
        /:flow-id\s+:long-flow-w-failure\.core\/flow-b/.test(s),
      );
      return failures.length >= 3 ? failures.length : null;
    }, '≥3 :rf.flow/failed emits (Rule 2 re-attempt cardinality)', 15000);
  },
};
