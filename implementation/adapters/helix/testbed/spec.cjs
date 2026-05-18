/*
 * Helix adapter testbed — browser smoke (rf2-eceuv).
 *
 * Proves the Helix adapter wires up end-to-end: mount, subscribe (via
 * use-subscribe), dispatch, re-render. Minimal by design — real
 * coverage lives in the framework's CLJS / browser tests and the
 * Causa feature gate.
 */
const { expectTextEquals, expectVisible } =
  require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'helix adapter smoke',
  url:  '/adapter-testbeds/helix/',
  run:  async (page) => {
    const banner  = page.locator('[data-testid="rf-adapter-testbed-helix"]');
    const counter = page.locator('[data-testid="rf-adapter-counter"]');
    const button  = page.locator('[data-testid="rf-adapter-inc"]');
    await expectVisible(banner, 10000);
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');
  },
};
