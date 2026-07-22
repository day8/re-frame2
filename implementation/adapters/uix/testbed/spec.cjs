/*
 * UIx adapter testbed — browser smoke (rf2-eceuv, rf2-qgfo4).
 *
 * Proves the UIx adapter wires up end-to-end on the DOCUMENTED BOOT
 * PATH: a real-DOM `frame-root` ENSURE mount (the template scaffold's
 * exact shape), subscribe (via use-subscribe), dispatch, re-render —
 * and that the boot is CLEAN: zero console errors and zero uncaught
 * page errors. rf2-qgfo4 recorded a mount-time React-child regression
 * class (fn-as-child / MapEntry-as-child) that only a real-DOM boot
 * surfaces; the previous smoke mounted via frame-provider after a
 * manual make-frame, so frame-root's browser path had no coverage.
 * Minimal by design — real coverage lives in the framework's CLJS /
 * browser tests and the Xray feature gate.
 */
const { expectTextEquals, expectVisible } =
  require('../../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'uix adapter smoke',
  url:  '/adapter-testbeds/uix/',
  run:  async (page) => {
    // The runner navigates before run() is called, so arm the capture
    // listeners and re-navigate: the mount under assertion must happen
    // with listeners attached or boot-time errors go unobserved.
    const consoleErrors = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
    const pageErrors = [];
    page.on('pageerror', (err) => pageErrors.push(err.message));
    await page.goto(page.url(), { waitUntil: 'load' });

    const banner  = page.locator('[data-testid="rf-adapter-testbed-uix"]');
    const counter = page.locator('[data-testid="rf-adapter-counter"]');
    const button  = page.locator('[data-testid="rf-adapter-inc"]');
    await expectVisible(banner, 10000);
    // '0' proves frame-root's commit-time ENSURE ran the
    // :initial-events seed exactly once before the children rendered.
    await expectTextEquals(counter, '0');
    await button.click();
    await expectTextEquals(counter, '1');

    if (consoleErrors.length > 0 || pageErrors.length > 0) {
      throw new Error(
        'uix frame-root real-DOM boot must be clean (rf2-qgfo4): ' +
          `${consoleErrors.length} console error(s), ` +
          `${pageErrors.length} pageerror(s)\n` +
          [...consoleErrors, ...pageErrors].join('\n'),
      );
    }
  },
};
