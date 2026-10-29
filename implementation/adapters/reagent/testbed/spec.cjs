/*
 * Reagent adapter testbed — browser smoke.
 *
 * Proves the Reagent adapter wires up end-to-end on the documented boot
 * path: a real-DOM `frame-root` mount, subscribe, dispatch, re-render. The
 * runner fails the smoke on any console or uncaught page error. Minimal by
 * design — real coverage lives in the framework's CLJS / browser tests and
 * the Xray feature gate.
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
    // '0' proves frame-root's ENSURE ran the :initial-events seed.
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');
  },
};
