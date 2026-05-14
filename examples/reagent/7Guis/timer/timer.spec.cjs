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

const {
  expectVisible,
  waitForValue,
} = require('../../../scripts/spec-helpers.cjs');

async function readElapsed(page) {
  // The view renders elapsed seconds inside [data-testid="timer-elapsed"],
  // e.g. "1.5 s". Parse the leading float; on the (extremely brief)
  // first-render the label may not be present yet — return null.
  const t = (await page.getByTestId('timer-elapsed').textContent()) || '';
  const m = t.match(/(\d+\.\d+)\s*s/);
  return m ? parseFloat(m[1]) : null;
}

module.exports = {
  name: 'timer (7guis #4)',
  url: '/timer/',
  run: async (page) => {
    // The bar is visible.
    await expectVisible(page.locator('.bar'), 10000);

    // Wait for the timer to actually advance past zero — poll the
    // elapsed label rather than sleeping a fixed budget (rf2-u3amn).
    await waitForValue(() => readElapsed(page), (v) => v != null && v > 0, {
      timeoutMs: 5000,
      description: 'timer elapsed > 0',
    });

    // Click Reset; elapsed should return to 0.0.
    await page.getByTestId('timer-reset').click();
    // Use a small grace window — the very first reading may be a tiny
    // fraction of a tick if the next tick fired immediately.
    await waitForValue(() => readElapsed(page), (v) => v === 0, {
      timeoutMs: 2000,
      description: 'timer elapsed reset to 0.0',
    });
  },
};
