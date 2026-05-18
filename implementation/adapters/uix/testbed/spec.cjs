/*
 * UIx adapter testbed — browser smoke (rf2-eceuv).
 *
 * Proves the UIx adapter wires up end-to-end: mount, subscribe (via
 * use-subscribe), dispatch, re-render. Minimal by design — real
 * coverage lives in the framework's CLJS / browser tests and the
 * Causa feature gate.
 */
const { expectTextEquals, expectVisible } =
  require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'uix adapter smoke',
  url:  '/adapter-testbeds/uix/',
  run:  async (page) => {
    const banner  = page.locator('[data-testid="rf-adapter-testbed-uix"]');
    const counter = page.locator('[data-testid="rf-adapter-counter"]');
    const button  = page.locator('[data-testid="rf-adapter-inc"]');
    await expectVisible(banner, 10000);
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');
  },
};
