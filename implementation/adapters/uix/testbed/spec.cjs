/*
 * UIx adapter testbed — browser smoke.
 *
 * Proves the UIx adapter wires up end-to-end on the documented boot path: a
 * real-DOM `frame-root` ENSURE mount, subscribe (via use-sub), dispatch,
 * re-render. The runner fails the smoke on any console or uncaught page
 * error, which is how a mount-time React-child failure (fn-as-child,
 * MapEntry-as-child) surfaces: only a real-DOM boot shows it.
 * Minimal by design — real coverage lives in the framework's CLJS /
 * browser tests and the Xray feature gate.
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
    // '0' proves frame-root's ENSURE ran the :initial-events seed.
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');
  },
};
