/*
 * 7GUIs #6 — Circle Drawer — smoke test.
 *
 * Exercises the undo/redo + dialog interaction model:
 *
 * - Initial render: empty SVG canvas; Undo and Redo disabled.
 * - Click on canvas: a circle is added at the click position.
 * - Click again: a second circle is added, total 2.
 * - Right-click a circle: dialog opens with a slider.
 * - Change slider value, click Close: the circle's radius updates.
 * - Click Undo: the radius change is undone (or earlier state restored).
 * - Click Redo: state restored.
 *
 * The on-context-menu handler calls .preventDefault(), so Playwright's
 * `click({ button: 'right' })` will fire it without the browser context
 * menu intervening.
 */

const {
  expectAttribute,
  expectCount,
  expectVisible,
  waitForValue,
} = require('../../../scripts/spec-helpers.cjs');

module.exports = {
  name: 'circle-drawer (7guis #6)',
  url: '/circle-drawer/',
  run: async (page) => {
    // Anchor controls on data-testid (rf2-i0j1x). The drawn circles
    // themselves are the data model — they have no per-id testid by
    // design — so position lookups remain via the `svg circle` family.
    const svg = page.getByTestId('drawer-canvas');
    const undoBtn = page.getByTestId('drawer-undo');
    const redoBtn = page.getByTestId('drawer-redo');
    const circles = page.locator('[data-testid="drawer-canvas"] circle');

    await expectVisible(svg, 10000);

    // Initial: undo + redo disabled.
    await expectAttribute(undoBtn, 'disabled', '');
    await expectAttribute(redoBtn, 'disabled', '');

    // Click canvas: 1 circle.
    await svg.click({ position: { x: 120, y: 120 } });
    await expectCount(circles, 1, 5000);
    await expectAttribute(undoBtn, 'disabled', null);

    // Click again: 2 circles.
    await svg.click({ position: { x: 300, y: 200 } });
    await expectCount(circles, 2, 5000);

    // Right-click the first circle to open the dialog.
    const firstCircle = circles.first();
    const initialRadius = await firstCircle.getAttribute('r');
    await firstCircle.click({ button: 'right' });
    const slider = page.getByTestId('drawer-slider');
    await expectVisible(slider, 5000);

    // Drag slider to a different value, close dialog.
    await slider.fill('70');
    await page.getByTestId('drawer-close').click();
    await waitForValue(
      () => firstCircle.getAttribute('r'),
      (r) => r === '70',
      { timeoutMs: 5000, description: 'first circle r="70"' },
    );

    // Undo: radius change reverts.
    await undoBtn.click();
    await waitForValue(
      () => firstCircle.getAttribute('r'),
      (r) => r === initialRadius,
      { timeoutMs: 5000, description: `first circle r="${initialRadius}"` },
    );

    // Redo: radius change reapplies.
    await redoBtn.click();
    await waitForValue(
      () => firstCircle.getAttribute('r'),
      (r) => r === '70',
      { timeoutMs: 5000, description: 'first circle r="70" (redo)' },
    );
  },
};
