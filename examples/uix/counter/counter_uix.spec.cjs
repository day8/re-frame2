/*
 * Counter (UIx) example — smoke test (rf2-3yij Decision 7).
 *
 * Renders the same dataflow as examples/counter, but through the UIx
 * substrate adapter. The smoke trio (per Conventions §Substrate test
 * matrix policy) — counter + login + realworld — is reduced to
 * counter + login for UIx (Decision 7 skips realworld).
 *
 * Same user-visible behaviour as the Reagent counter:
 *   - Initial render: 5 (seeded by :counter/initialise).
 *   - +/- increment / decrement.
 */

const { expectTextEquals } = require('../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'counter-uix',
  url: '/counter-uix/',
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
