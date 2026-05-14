/*
 * Cross-cutting scenario #3 of 6 from rf2-fe84r §Section B.
 *
 *   "State-machine transition cascade shows `:rf.machine/*` events."
 *
 * Exercises `testbeds/deep_machine/` (rf2-kzcim). The surface ships
 * one parent machine `:deep/main` with parallel regions (`:work` +
 * `:health`), a 5-deep compound hierarchy under `:work`, and `:invoke`
 * / `:invoke-all` declarations that spawn child machines.
 *
 * What "`:rf.machine/*` events" means here — the machines artefact
 * emits a family of trace events through `re-frame.trace/emit!` whose
 * `:operation` keywords are all in the `:rf.machine/*` namespace
 * (per `implementation/machines/src/.../lifecycle_fx/`):
 *
 *   - `:rf.machine/transition`           on every state transition
 *   - `:rf.machine/snapshot-updated`     after snapshot commit
 *   - `:rf.machine/event-received`       on event arrival
 *   - `:rf.machine/spawned`              on `:invoke` child start
 *   - `:rf.machine/destroyed`            on `:invoke` child stop
 *   - `:rf.machine/system-id-bound`      on spawn-id binding
 *   - `:rf.machine/system-id-released`   on destroy
 *
 * Asserting the trace bus carries `:rf.machine/transition` (and at
 * least one `:rf.machine/snapshot-updated`) after a `:work/go` click
 * proves the cascade emitted through the machine layer's trace path —
 * the cross-cutting observable the bead names.
 *
 * Trigger choice — Button 1 (`:work/go`) descends five compound levels
 * in one transition (region root → `:phase-a` → `:sub-a` → `:nested-a`
 * → `:deep-a` → `:leaf-a`), then `:invoke`'s the `:helper/tick` child
 * on `:leaf-a` entry. The cascade therefore emits BOTH transition
 * traces AND spawn traces in one click — the densest single
 * observation of the machine layer's emit surface.
 */

const path = require('path');
const { expectVisible, expectTextEquals } = require(
  path.join('..', '..', 'examples', 'scripts', 'spec-helpers.cjs'),
);
const { readTraceEventsAsEdn, clearTraceBus, pollUntil } = require(
  path.join('..', 'spec-helpers.cjs'),
);

module.exports = {
  name: 'cross-cutting #3 — state-machine transition cascade',
  url: '/testbeds/deep-machine/',
  run: async (page) => {
    await expectVisible(page.locator('[data-testid="deep-machine"]'), 10000);

    // The testbed's `::initialise` dispatches the parent machine's
    // `:rf.machine/bootstrap` event on boot. By the time the root
    // testid is visible, the parent has settled into its initial
    // parallel-region state — `:work` at `:idle`, `:health` at
    // `:cold`. The work-state mirror reflects that.
    //
    // Reading the state from app-db via the rendered text proves
    // the framework's `:rf/machine` sub-graph resolves correctly
    // — but isn't the cross-cutting contract. The contract is the
    // trace stream; the DOM mirror is the precondition check that
    // says "init landed cleanly, fire the trigger".
    await expectTextEquals(page.locator('[data-testid="work-state"]'), ':idle', 10000);

    // Clear the trace bus so the find() below scopes to the events
    // emitted by this click — the bootstrap cascade and substrate
    // init landed dozens of unrelated `:event/*` traces beforehand.
    const cleared = await clearTraceBus(page);
    if (!cleared.ok) {
      throw new Error(`could not clear trace bus: ${cleared.reason}`);
    }

    // Drive `:work/go` — the transition descends five compound levels
    // in one cascade. Per Spec 005 §Compound state entry: the runtime
    // walks `:phase-a` → `:sub-a` → `:nested-a` → `:deep-a` → `:leaf-a`,
    // firing `:entry` actions at each rung. The `:leaf-a` rung also
    // hosts the `:invoke {:machine-id :helper/tick ...}` declaration,
    // so a `:helper/tick` child spawns on entry.
    await page.locator('[data-testid="work-go"]').click();

    // The work-state mirror flips off `:idle` once the transition
    // cascade settles. Per `re-frame.machines.transition/denormalise-
    // state` the 5-level descent renders as a vector path
    // `[:phase-a :sub-a :nested-a :deep-a :leaf-a]` (a single-keyword
    // form is reserved for length-1 paths). Asserting "no longer
    // :idle" is the substrate-observable that says the cascade
    // committed; the exact path form is a separate scenario.
    {
      const workState = page.locator('[data-testid="work-state"]');
      const start = Date.now();
      let last = '';
      while (Date.now() - start < 10000) {
        last = (await workState.textContent())?.trim() || '';
        if (last && last !== ':idle') break;
        await new Promise((r) => setTimeout(r, 50));
      }
      if (!last || last === ':idle') {
        throw new Error(
          `:work region did not transition off :idle after :work/go click (last="${last}")`,
        );
      }
    }

    // Walk the trace bus for the cross-cutting contract — at least
    // one `:rf.machine/transition` AND one `:rf.machine/snapshot-
    // updated` from the cascade. Both fire from
    // `re-frame.machines.lifecycle_fx.registration` (lines 331, 337
    // per the canonical implementation). A cascade that drove
    // FIVE transitions in one drain should produce FIVE
    // `:rf.machine/transition` emits, but the cross-cutting contract
    // only requires presence — counting them would test the spawn
    // semantics, which is a separate scenario.
    const events = await pollUntil(async () => {
      const probe = await readTraceEventsAsEdn(page);
      if (!probe.ok) throw new Error(`could not read trace bus: ${probe.reason}`);
      const transitions = probe.events.filter((s) =>
        /:operation\s+:rf\.machine\/transition/.test(s),
      );
      const snapshots = probe.events.filter((s) =>
        /:operation\s+:rf\.machine\/snapshot-updated/.test(s),
      );
      if (transitions.length >= 1 && snapshots.length >= 1) {
        return { transitions, snapshots, all: probe.events };
      }
      return null;
    }, ':rf.machine/transition + :rf.machine/snapshot-updated trace events after :work/go click', 10000);

    // Spawn observation — the `:leaf-a` `:invoke` declaration fires
    // a `:rf.machine/spawned` emit through
    // `lifecycle_fx/spawn.cljc:209`. The cascade is part of the same
    // drain so a `:rf.machine/spawned` is necessarily in the buffer
    // by the time the work-state mirror lands at `:leaf-a`.
    const spawned = events.all.filter((s) =>
      /:operation\s+:rf\.machine\/spawned/.test(s),
    );
    if (spawned.length === 0) {
      throw new Error(
        ':leaf-a entry should fire :rf.machine/spawned (`:invoke {:machine-id :helper/tick}`); not observed in trace bus',
      );
    }

    // Tick-count check — the `:leaf-a` `:entry` action `:bump-tick`
    // fires once on entry, so the parent's `:data :tick-count` lands
    // at 1. This is the substrate's proof that the action body
    // executed (a transition can emit `:rf.machine/transition`
    // without the entry action firing only in a broken implementation;
    // reading the post-action data slot confirms end-to-end).
    await expectTextEquals(page.locator('[data-testid="tick-count"]'), '1', 5000);
  },
};
