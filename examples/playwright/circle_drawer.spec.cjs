/*
 * 7GUIs #6 — Circle Drawer — smoke test (rf2-w3vn).
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

const { expectVisible, expectAttribute } = require('./_helpers.cjs');

module.exports = {
  name: 'circle-drawer (7guis #6)',
  url: '/circle-drawer/',
  run: async (page) => {
    const svg = page.locator('svg');
    const undoBtn = page.getByRole('button', { name: /undo/i });
    const redoBtn = page.getByRole('button', { name: /redo/i });

    await expectVisible(svg, 10000);

    // Initial: undo + redo disabled.
    await expectAttribute(undoBtn, 'disabled', '');
    await expectAttribute(redoBtn, 'disabled', '');

    // Click canvas: 1 circle.
    await svg.click({ position: { x: 120, y: 120 } });
    await page.waitForFunction(
      () => document.querySelectorAll('svg circle').length === 1,
      null,
      { timeout: 5000 },
    );
    await expectAttribute(undoBtn, 'disabled', null);

    // Click again: 2 circles.
    await svg.click({ position: { x: 300, y: 200 } });
    await page.waitForFunction(
      () => document.querySelectorAll('svg circle').length === 2,
      null,
      { timeout: 5000 },
    );

    // Right-click the first circle to open the dialog.
    const firstCircle = page.locator('svg circle').nth(0);
    const initialRadius = await firstCircle.getAttribute('r');
    await firstCircle.click({ button: 'right' });
    const slider = page.locator('input[type="range"]');
    await expectVisible(slider, 5000);

    // Drag slider to a different value, close dialog.
    await slider.fill('70');
    await page.getByRole('button', { name: /close/i }).click();
    await page.waitForFunction(
      () => {
        const c = document.querySelectorAll('svg circle')[0];
        return c && c.getAttribute('r') === '70';
      },
      null,
      { timeout: 5000 },
    );

    // Undo: radius change reverts.
    await undoBtn.click();
    await page.waitForFunction(
      (initial) => {
        const c = document.querySelectorAll('svg circle')[0];
        return c && c.getAttribute('r') === initial;
      },
      initialRadius,
      { timeout: 5000 },
    );

    // Redo: radius change reapplies.
    await redoBtn.click();
    await page.waitForFunction(
      () => {
        const c = document.querySelectorAll('svg circle')[0];
        return c && c.getAttribute('r') === '70';
      },
      null,
      { timeout: 5000 },
    );
  },
};
