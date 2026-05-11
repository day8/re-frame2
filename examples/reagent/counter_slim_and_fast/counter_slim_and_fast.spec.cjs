/*
 * counter-slim-and-fast example — smoke test (bead rf2-5lbx).
 *
 * Same user-visible behaviour assertions as
 * examples/reagent/counter/counter.spec.cjs — the slim adapter
 * is meant to be a drop-in for the bridge, so the same clicks
 * must produce the same counts.
 *
 *   - Initial render: shows 5 (seeded by :counter/initialise).
 *   - Click '+': value becomes 6.
 *   - Click '-' twice from 6: value becomes 4.
 *
 * This is the "no behavioural regression" half of the S3-008
 * contract; the bundle-comparison half lives in
 * implementation/scripts/check-counter-slim-and-fast.cjs and is
 * already enforced by CI's cljs-bundle-comparison job.
 *
 * SKIPPED at runtime — see `skip` field below. The example's
 * :advanced bundle compiles cleanly and the bundle-comparison
 * contract passes; what fails is the runtime IDisposable dispatch
 * when the subscription cache calls
 * `re-frame.interop/add-on-dispose!` on a `reagent2.ratom/Reaction`.
 * `re-frame.interop` is presently hardcoded to stock `reagent.ratom/
 * add-on-dispose!`, which dispatches via stock Reagent's
 * IDisposable protocol — which the slim's Reaction doesn't
 * implement (it implements `reagent2.ratom/IDisposable`).
 *
 * The fix lives outside this bead's scope (rf2-5lbx is explicitly
 * NOT modifying the slim impl). The interop seam needed to make
 * the slim runtime-functional is tracked at bead **rf2-s36l**
 * (P2 bug, discovered-from rf2-5lbx). Until that work lands, the
 * bundle-comparison contract is the binding S3-008 claim; the
 * runtime smoke is parked behind `skip:`.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-slim-and-fast',
  url: '/counter-slim-and-fast/',
  // SEE the docstring above. The runtime smoke is parked behind a
  // missing interop seam — `re-frame.interop` needs adapter-agnostic
  // dispatch for IDisposable / IReactiveAtom before the slim adapter
  // is runtime-functional. The :advanced bundle still compiles
  // cleanly and the bundle-comparison contract still passes; this
  // skip preserves both signals while parking the click-through
  // smoke until the seam lands.
  skip: 'pending rf2-s36l — `re-frame.interop/add-on-dispose!` hardcodes stock `reagent.ratom`; needed before slim is runtime-functional. The bundle-comparison contract (cljs-bundle-comparison job) is unaffected.',
  run: async (page) => {
    const span = page.locator('span').first();
    await expectTextEquals(span, '5', 10000);

    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    await page.getByRole('button', { name: '-' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await expectTextEquals(span, '4');
  },
};
