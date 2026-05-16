/*
 * Causa inline-host resize contract — browser smoke (rf2-um813).
 *
 * Pins the developer-resize contract documented in
 * tools/causa/spec/011-Launch-Modes.md §Resizing the inline host:
 *
 *   - The host's `[data-rf-causa-host]` rule reads
 *     `flex-basis: var(--rf-causa-inline-width, 420px)`.
 *   - Overriding the CSS custom property anywhere up the cascade
 *     resizes the inline panel, with no JS, no overlay, no body
 *     padding, and no fork of the host rule.
 *   - App content to the left of the host remains clickable
 *     (the flex flow reshapes `#app` to fill the remainder; the
 *     panel never overlays).
 *
 * The spec drives the existing /counter/ example (whose index.html
 * was updated under rf2-um813 to use `var(--rf-causa-inline-width,
 * 420px)` in its host rule). Three contracts are exercised:
 *
 *   1. **Default geometry pinned.** Without an override, the host
 *      renders at the documented default width (420px) — exact
 *      bounding-rect width, not a tolerance — so a regression that
 *      silently drops the fallback in the `var(...)` is loud.
 *
 *   2. **Cascade override takes effect.** Injecting
 *      `:root { --rf-causa-inline-width: 600px; }` resizes the host
 *      to 600px on the very next layout pass. We assert the
 *      bounding-rect width and that the left edge of the host moved
 *      leftward exactly as much as the width grew (the host is pinned
 *      to the shell's right edge, so growing the host extends its left
 *      edge into the app column).
 *
 *   3. **No hit-test occlusion across the resize.** A host-side
 *      `+` button sits to the left of Causa; we click it before
 *      and after the resize, asserting the counter increments each
 *      time. The click would silently miss if the panel grew an
 *      overlay layer or trapped pointer events outside its rect.
 *
 * Why a separate spec rather than amending rigorous/spec.cjs:
 *   - The rigorous spec is already thick (~500 lines covering shell
 *     visibility, time-travel, co-pilot, trace bus). The resize
 *     contract is a small, focused regression-target — co-locating
 *     it with rigorous would bury it. A dedicated 80-line spec lets
 *     a failure surface the contract by name (`causa-inline-resize`)
 *     in the runner's summary line.
 *   - The runner (examples/scripts/run-examples-tests.cjs) walks
 *     `tools/<tool>/testbeds/<scenario>/spec.cjs` by convention
 *     (rf2-p8f2s), so a new directory under tools/causa/testbeds/
 *     is picked up with no orchestrator change.
 */

const { expectTextEquals, expectVisible } =
  require('../../../../examples/scripts/spec-helpers.cjs');

// The historical default the recommended host rule uses as the
// `var(...)`'s fallback. Matches
// `day8.re-frame2-causa.config/default-layout-host-width`.
const DEFAULT_HOST_WIDTH_PX = 420;

// The deliberate override the spec injects. Chosen to be (a) larger
// than the default, so the right-edge delta is positive and visible;
// (b) different enough that a stale snapshot reading the default
// would fail loudly; (c) compatible with the standard headless
// viewport (Chromium defaults to 1280×720, so 600 still leaves room
// for `#app` to render its controls).
const OVERRIDE_HOST_WIDTH_PX = 600;

async function readHostGeometry(page) {
  return page.evaluate(() => {
    const host = document.querySelector('[data-rf-causa-host]');
    const app = document.getElementById('app');
    function rect(el) {
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { left: r.left, right: r.right, width: r.width };
    }
    return {
      host: rect(host),
      app: rect(app),
      flexBasisVar: host
        ? getComputedStyle(host).getPropertyValue('--rf-causa-inline-width')
        : null,
    };
  });
}

async function injectCustomPropertyOverride(page, pixels) {
  await page.evaluate((px) => {
    const style = document.createElement('style');
    style.setAttribute('data-test', 'rf-causa-inline-width-override');
    style.textContent = `:root { --rf-causa-inline-width: ${px}px; }`;
    document.head.appendChild(style);
  }, pixels);
}

module.exports = {
  name: 'causa-inline-resize',
  url: '/counter/',
  run: async (page) => {
    // ----------------------------------------------------------------
    // 0. Wait for first paint of Causa + the host app.
    // ----------------------------------------------------------------
    await expectVisible(
      page.locator('[data-testid="rf-causa-shell"]'),
      10000,
    );
    const counterValue = page.locator('#app [data-testid="counter-value"]');
    await expectVisible(counterValue, 10000);
    const initialCounter = parseInt(
      ((await counterValue.textContent()) || '0').trim(),
      10,
    );

    // ----------------------------------------------------------------
    // 1. Default geometry — the `var(...)` fallback is in force.
    // ----------------------------------------------------------------
    const before = await readHostGeometry(page);
    if (!before.host || !before.app) {
      throw new Error(
        `Expected [data-rf-causa-host] + #app to render; got ${JSON.stringify(before)}`,
      );
    }
    if (Math.round(before.host.width) !== DEFAULT_HOST_WIDTH_PX) {
      throw new Error(
        `Default inline-host width should be ${DEFAULT_HOST_WIDTH_PX}px ` +
          `(the var() fallback); got ${before.host.width}px. ` +
          'Did the recommended host CSS rule drop its fallback?',
      );
    }
    if (before.app.right > before.host.left) {
      throw new Error(
        `App content should sit to the left of the host; got ${JSON.stringify(before)}`,
      );
    }

    // ----------------------------------------------------------------
    // 2. Host `+` button is clickable through the default-width layout.
    // ----------------------------------------------------------------
    const plusButton = page
      .locator('#app button', { hasText: '+' })
      .first();
    await expectVisible(plusButton, 5000);
    await plusButton.click();
    await expectTextEquals(counterValue, String(initialCounter + 1), 5000);

    // ----------------------------------------------------------------
    // 3. Inject the override — `--rf-causa-inline-width: 600px`.
    //
    //    The override goes into `:root` so the cascade lookup at the
    //    host element resolves to the new value. We assert the host's
    //    bounding-rect width updates on the very next layout pass.
    // ----------------------------------------------------------------
    await injectCustomPropertyOverride(page, OVERRIDE_HOST_WIDTH_PX);

    // Force a layout read; getBoundingClientRect already flushes, but
    // we re-read until the width settles to guard against any async
    // style-recalc the browser schedules.
    const start = Date.now();
    let after = null;
    while (Date.now() - start < 5000) {
      after = await readHostGeometry(page);
      if (
        after.host &&
        Math.round(after.host.width) === OVERRIDE_HOST_WIDTH_PX
      ) {
        break;
      }
      await new Promise((r) => setTimeout(r, 50));
    }
    if (!after || Math.round(after.host.width) !== OVERRIDE_HOST_WIDTH_PX) {
      throw new Error(
        `Override --rf-causa-inline-width: ${OVERRIDE_HOST_WIDTH_PX}px ` +
          'did not propagate to the host element. ' +
          `Last reading: ${JSON.stringify(after)}. ` +
          'Did the host CSS stop reading the variable?',
      );
    }

    // The app must reflow to keep its right edge <= the host's new
    // left edge. Equality is the flex-layout outcome (no gap rule);
    // a strict `>` would indicate the panel started overlaying.
    if (after.app.right > after.host.left) {
      throw new Error(
        `App content was overlapped by the resized host; got ${JSON.stringify(after)}`,
      );
    }
    const widthDelta = after.host.width - before.host.width;
    const leftDelta = before.host.left - after.host.left;
    if (Math.abs(widthDelta - leftDelta) > 1) {
      // The host's right edge is pinned by `.rf2-testbed-shell`'s
      // flex container, so `width` and `left` MUST move in lockstep
      // (growing the host extends its left edge leftward into the app
      // column). A mismatch means the override grew the box via
      // padding/margin (which would NOT survive a real developer
      // override) rather than `flex-basis`.
      throw new Error(
        `Host width grew by ${widthDelta}px but left edge moved by ` +
          `${leftDelta}px — these should match. Got ${JSON.stringify({ before, after })}.`,
      );
    }

    // ----------------------------------------------------------------
    // 4. Host `+` button is STILL clickable after the resize.
    //
    //    The button's position stays anchored in the app column on the
    //    left (the host grew leftward, the app column compressed but
    //    the button still sits in its #app subtree); Playwright's
    //    locator re-resolves on click, so a successful increment is
    //    the simplest end-to-end proof that no overlay or hit-test
    //    trap was introduced by the resize.
    // ----------------------------------------------------------------
    await plusButton.click();
    await expectTextEquals(counterValue, String(initialCounter + 2), 5000);

    // ----------------------------------------------------------------
    // 5. Removing the override snaps the host back to the default —
    //    proves the contract is reversible and that the host rule is
    //    a single, well-behaved source of truth.
    // ----------------------------------------------------------------
    await page.evaluate(() => {
      const el = document.querySelector(
        'style[data-test="rf-causa-inline-width-override"]',
      );
      if (el && el.parentNode) el.parentNode.removeChild(el);
    });
    const reset = await readHostGeometry(page);
    if (
      !reset.host ||
      Math.round(reset.host.width) !== DEFAULT_HOST_WIDTH_PX
    ) {
      throw new Error(
        `Removing the override should revert to ${DEFAULT_HOST_WIDTH_PX}px; ` +
          `got ${JSON.stringify(reset)}.`,
      );
    }
  },
};
