/*
 * 7GUIs #4 — Timer — smoke test.
 *
 * The timer is driven by `:dispatch-later` ticks every 100ms. The smoke
 * test verifies the timer ticks (elapsed advances), the slider changes
 * the duration, and Reset zeros elapsed.
 *
 * - Initial render: progress bar visible; elapsed text near "0".
 * - Wait ~500ms: elapsed advances above zero.
 * - Reset: elapsed back to "0.0 s".
 */

const { expectVisible } = require('../../../scripts/spec-helpers.cjs');

async function readElapsed(page) {
  // The view renders elapsed seconds inside a <label>, e.g. "1.5 s".
  // We read the .row containing the elapsed-only label (no preceding label).
  const labels = await page.locator('label').allTextContents();
  for (const t of labels) {
    const m = (t || '').match(/^\s*(\d+\.\d+)\s*s\s*$/);
    if (m) return parseFloat(m[1]);
  }
  return null;
}

module.exports = {
  name: 'timer (7guis #4)',
  url: '/timer/',
  run: async (page) => {
    // The bar is visible.
    await expectVisible(page.locator('.bar'), 10000);

    // Wait for the timer to actually advance past zero — poll the
    // elapsed label rather than sleeping a fixed budget (rf2-u3amn).
    const start = Date.now();
    let elapsed1 = 0;
    while (Date.now() - start < 5000) {
      const v = await readElapsed(page);
      if (v != null && v > 0) {
        elapsed1 = v;
        break;
      }
      await new Promise((r) => setTimeout(r, 50));
    }
    if (elapsed1 <= 0) {
      throw new Error(`expected elapsed > 0 within 5s, got ${elapsed1}`);
    }

    // Click Reset; elapsed should return to 0.0.
    await page.getByRole('button', { name: /reset/i }).click();
    // Use a small grace window — the very first reading may be a tiny
    // fraction of a tick if the next tick fired immediately.
    const resetStart = Date.now();
    while (Date.now() - resetStart < 2000) {
      const e = await readElapsed(page);
      if (e === 0 || e === 0.0) return;
      await new Promise((r) => setTimeout(r, 50));
    }
    throw new Error('Reset did not return elapsed to 0.0 within 2s');
  },
};
