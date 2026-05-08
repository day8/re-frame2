/*
 * Counter example — smoke test (rf2-lyj0).
 *
 * - Initial render: the counter shows 5 (the value seeded by :counter/initialise).
 * - Click '+': value becomes 6.
 * - Click '-' twice from 6: value becomes 4.
 *
 * This exercises:
 *   - frame-provider scoping (the :counter frame is created on mount and
 *     drives the subtree's dispatch / subscribe),
 *   - `:on-create` running on first render,
 *   - dispatch round-trips re-rendering through the React substrate.
 */

const { expectTextEquals } = require('./_helpers.cjs');

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
