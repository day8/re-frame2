/*
 * Counter (UIx) example — smoke test.
 *
 * Renders the same dataflow as examples/reagent/counter, but through the UIx
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
