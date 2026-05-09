/*
 * Counter (Helix) example — smoke test (rf2-2qit Decision 7).
 *
 * Renders the same dataflow as examples/reagent/counter and
 * examples/uix/counter_uix, but through the Helix substrate adapter.
 * The smoke trio (per Conventions §Substrate test matrix policy) is
 * counter + login (Decision 7 skips realworld for Helix, matching
 * the UIx adapter).
 *
 * Same user-visible behaviour as the Reagent + UIx counters:
 *   - Initial render: 5 (seeded by :counter/initialise).
 *   - +/- increment / decrement.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-helix',
  url: '/counter-helix/',
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
