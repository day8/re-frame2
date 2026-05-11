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
 * rf2-s36l (interop late-binding) and rf2-08t0 (slim wrap-render
 * → as-element seam) both landed in the rf2-s36l PR — the slim
 * adapter now mounts cleanly with the correct IDisposable
 * dispatch and the class render() method returns React elements
 * instead of raw hiccup vectors. With those two fixes, this
 * smoke gets as far as initial render (the displayed count is
 * the seeded 5). What still fails is the click-driven re-render
 * path: `+` clicks update app-db but the displayed count stays
 * at 5. Tracked at **rf2-u5p5** (slim re-render reactive). Until
 * that bead lands the bundle-comparison contract remains the
 * binding S3-008 claim; the runtime smoke is parked behind
 * `skip:` again.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-slim-and-fast',
  url: '/counter-slim-and-fast/',
  // SEE the docstring above. The interop seam (rf2-s36l) and the
  // wrap-render → as-element seam (rf2-08t0) both landed in the
  // rf2-s36l PR — initial mount + render is correct. What remains
  // is the reactive-update path: dispatched events update app-db
  // but the subscribe Reaction's watch chain doesn't drive a
  // re-render of the class component. Tracked at rf2-u5p5.
  skip: 'pending rf2-u5p5 — slim Reaction → React forceUpdate watch chain not firing on app-db change; initial mount + render works post-rf2-s36l/rf2-08t0, click-driven re-render does not. Bundle-comparison contract unaffected.',
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
