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
 * contract; the adapter-owned bundle-isolation half lives in
 * implementation/scripts/check-reagent-slim-bundle-isolation.cjs and is
 * enforced by CI's cljs-reagent-slim-bundle-isolation job when slim changes.
 *
 * Three seams (rf2-s36l interop late-binding, rf2-08t0 wrap-render
 * → as-element conversion, rf2-u5p5 reaction `._run` on
 * subsequent render entries) all combine to make the slim adapter
 * a drop-in for the bridge: mount, render, and reactive re-render
 * all match stock Reagent's user-visible behaviour.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-slim-and-fast',
  url: '/counter-slim-and-fast/',
  run: async (page) => {
    // Anchor on data-testid (rf2-i0j1x).
    const span = page.getByTestId('counter-value');
    await expectTextEquals(span, '5', 10000);

    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    await page.getByRole('button', { name: '-' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await expectTextEquals(span, '4');
  },
};
