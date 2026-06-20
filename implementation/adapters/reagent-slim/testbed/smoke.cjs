/*
 * reagent-slim adapter testbed — CLIENT-RUNTIME browser smoke (rf2-xsgu8a).
 *
 * Proves the day8/reagent-slim substrate wires up end-to-end in a real
 * browser: mount through `reagent2.dom.client`, the `rf/frame-provider`
 * + injected `dispatch`/`subscribe` seam, and click-driven counter
 * updates. This is the client-runtime layer the rf2-xsgu8a review found
 * uncovered — the slim example's prior browser coverage was compile-only
 * (check-examples-compile.cjs) plus the release-bundle sentinel grep
 * (check-reagent-slim-bundle-isolation.cjs); neither exercises the live
 * mount/inject/click path.
 *
 * Deliberately a DEDICATED slim smoke, NOT an entry in the shared
 * adapter-smoke manifest (examples/scripts/adapter-smoke-filter.cjs,
 * whose Reagent/UIx/Helix set + reconcile guard are a separate surface).
 * It is named `smoke.cjs` (not `spec.cjs` / `*.spec.cjs`) so the shared
 * adapter-smoke spec-walker never discovers it, and it is driven by the
 * adapter-owned runner implementation/scripts/serve-and-run-reagent-slim-
 * smoke.cjs (npm `test:reagent-slim:smoke`). This mirrors how the slim
 * bundle-isolation contract gets its OWN script
 * (check-reagent-slim-bundle-isolation.cjs) rather than folding into the
 * shared check-bundle-isolation.cjs.
 *
 * Spec format mirrors the adapter testbed spec.cjs shape: { name, url,
 * run(page) }. The runner fails the gate on any uncaught browser
 * pageerror during the scenario, even if the visible assertions pass.
 */
'use strict';

const path = require('path');
const { expectTextEquals, expectVisible } = require(
  path.join(__dirname, '..', '..', '..', '..', 'examples', 'scripts', 'spec-helpers.cjs'),
);

module.exports = {
  name: 'reagent-slim adapter client-runtime smoke',
  // Served at the static root of the slim smoke's own staging dir.
  url:  '/',
  run:  async (page) => {
    const banner  = page.locator('[data-testid="rf-adapter-testbed-reagent-slim"]');
    const counter = page.locator('[data-testid="rf-adapter-counter"]');
    const inc     = page.locator('[data-testid="rf-adapter-inc"]');
    const dec     = page.locator('[data-testid="rf-adapter-dec"]');

    // Mount + initial render: the slim substrate boots, the frame-provider
    // resolves :rf/default, and the subscribe reads the seeded value.
    await expectVisible(banner, 10000);
    await expectTextEquals(counter, '5');

    // Click-driven dispatch through the injected frame-bound dispatch.
    await inc.click();
    await expectTextEquals(counter, '6');
    await inc.click();
    await expectTextEquals(counter, '7');
    await dec.click();
    await expectTextEquals(counter, '6');
    await dec.click();
    await dec.click();
    await expectTextEquals(counter, '4');
  },
};
