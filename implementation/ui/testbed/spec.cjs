/*
 * re-frame.ui testbed — browser smoke (rf2-nojiwy).
 *
 * Proves the re-frame.ui compiled-view substrate wires up end-to-end:
 * mount, subscribe, dispatch, re-render. The four-suites rule's new-UI
 * smoke, riding the shared adapter-smoke runner alongside the three
 * adapter smokes. Minimal by design — real coverage lives in the
 * re-frame.ui conformance suites (CLJS / browser tests) and the
 * G-gates (ui-g1 / ui-g13 / ui-g8 / facade isolation).
 */
const { expectTextEquals, expectVisible } =
  require('../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 're-frame.ui testbed smoke',
  url:  '/ui-testbed/',
  run:  async (page) => {
    const banner  = page.locator('[data-testid="rf-ui-testbed"]');
    const counter = page.locator('[data-testid="rf-ui-counter"]');
    const button  = page.locator('[data-testid="rf-ui-inc"]');
    await expectVisible(banner, 10000);
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');
  },
};
