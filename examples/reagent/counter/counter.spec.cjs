/*
 * Counter example — smoke test.
 *
 * - Initial render: the counter shows 5 (the value seeded by :counter/initialise).
 * - Click '+': value becomes 6.
 * - Click '-' twice from 6: value becomes 4.
 *
 * This exercises:
 *   - reg-view frame-aware injection: dispatch / subscribe inside the
 *     view body resolve to the surrounding frame (:rf/default here),
 *   - dispatch-sync seeding via :counter/initialise on mount,
 *   - dispatch round-trips re-rendering through the React substrate.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter',
  url: '/counter/',
  run: async (page) => {
    // Anchor on data-testid (rf2-i0j1x) — survives sibling DOM
    // changes that would otherwise break `span.first()`.
    const span = page.getByTestId('counter-value');
    await expectTextEquals(span, '5', 10000);

    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    await page.getByRole('button', { name: '-' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await expectTextEquals(span, '4');
  },
};
