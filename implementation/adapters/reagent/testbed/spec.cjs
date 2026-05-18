/*
 * Reagent adapter testbed — browser smoke (rf2-eceuv).
 *
 * Proves the Reagent adapter wires up end-to-end: mount, subscribe,
 * dispatch, re-render. Minimal by design — real coverage lives in
 * the framework's CLJS / browser tests and the Causa feature gate.
 */
const { expectTextEquals, expectVisible } =
  require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'reagent adapter smoke',
  url:  '/adapter-testbeds/reagent/',
  run:  async (page) => {
    const banner  = page.locator('[data-testid="rf-adapter-testbed-reagent"]');
    const counter = page.locator('[data-testid="rf-adapter-counter"]');
    const button  = page.locator('[data-testid="rf-adapter-inc"]');
    await expectVisible(banner, 10000);
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');
  },
};
