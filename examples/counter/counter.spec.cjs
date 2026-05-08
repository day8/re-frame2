/*
 * Counter example — smoke test (rf2-lyj0).
 *
 * - Initial render: the counter shows 5 (the value seeded by :counter/initialise).
 * - Click '+': value becomes 6.
 * - Click '-' twice from 6: value becomes 4.
 *
 * This exercises:
 *   - reg-view frame-aware injection: dispatch / subscribe inside the
 *     view body resolve to the surrounding frame (:rf/default here —
 *     a frame-provider shell is parked behind rf2-kdwc),
 *   - dispatch-sync seeding via :counter/initialise on mount,
 *   - dispatch round-trips re-rendering through the React substrate.
 */

const { expectTextEquals } = require('../../implementation/scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter',
  url: '/counter/',
  run: async (page) => {
    // The counter renders one <span> between the - and + buttons holding the count.
    const span = page.locator('span').first();
    await expectTextEquals(span, '5', 10000);

    await page.getByRole('button', { name: '+' }).click();
    await expectTextEquals(span, '6');

    await page.getByRole('button', { name: '-' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await expectTextEquals(span, '4');
  },
};
