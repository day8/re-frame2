/*
 * Long-running-work example — Pattern-LongRunningWork smoke.
 *
 * Demonstrates :invoke-all spawn-and-join with cooperative
 * cancellation:
 *
 *   - Initial render: state = :idle, progress = 0 / 300 items.
 *   - Click Start: state → :working; the parent's :invoke-all
 *     entry-cascade spawns 3 child :work/processor machines; each
 *     yields between chunks via :after, dispatching :progress
 *     events back to the parent. Progress bar advances visibly.
 *   - Click Cancel mid-flight: state → :cancelled; the exit
 *     cascade fires :rf.machine/destroy with :rf/invoke-all true;
 *     every surviving child is torn down. Partial progress is
 *     preserved (the parent's :stamp-outcome action records
 *     :outcome :cancelled).
 *   - Reset: returns to :idle, clearing the per-shard progress.
 *   - Hide / Show: unmounting the work-bench fires :cancel via
 *     the view's r/with-let cleanup; the cascade tears every
 *     in-flight child down. Re-Show resumes from :idle.
 *
 * The browser is the place we *prove* the cascade is live: a
 * mid-flight cancel actually stops the workers (the progress
 * snapshot just before cancel does not advance after). The
 * headless tests (re-frame.long-running-work-cljs-test) cover
 * the machine-side invariants; this smoke covers the
 * machine ↔ React ↔ browser-clock wiring.
 */

const { expectTextEquals, expectTextContains, expectVisible } =
  require('../../scripts/spec-helpers.cjs');

async function readProgressDone(page) {
  const label = page.locator('[data-testid="progress-label"]');
  const text  = (await label.textContent()) || '';
  // Shape: "Progress: 42 / 300 items"
  const m = text.match(/Progress:\s+(\d+)\s+\/\s+(\d+)/);
  if (!m) throw new Error(`unexpected progress label: ${text}`);
  return { done: parseInt(m[1], 10), total: parseInt(m[2], 10) };
}

module.exports = {
  name: 'long-running-work',
  url: '/long-running-work/',
  run: async (page) => {
    // --- Initial render: :idle, 0/300 -----------------------------------------
    const stateValue = page.locator('[data-testid="state-value"]');
    await expectTextEquals(stateValue, 'idle', 10000);

    const initial = await readProgressDone(page);
    if (initial.done !== 0 || initial.total !== 300) {
      throw new Error(
        `initial progress should be 0/300, got ${initial.done}/${initial.total}`,
      );
    }

    // --- Start: the parent transitions :working, workers start dispatching ---
    await page.getByTestId('start').click();
    await expectTextEquals(stateValue, 'working');

    // Wait for visible progress — at 50ms / item, the bar should
    // tick past 1 within a couple of hundred ms. We poll until the
    // counter goes above zero or time out.
    const start = Date.now();
    let progress;
    while (Date.now() - start < 5000) {
      progress = await readProgressDone(page);
      if (progress.done > 0) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    if (!progress || progress.done <= 0) {
      throw new Error(
        `expected progress to advance past 0 within 5s; got ${progress && progress.done}/${progress && progress.total}`,
      );
    }

    // --- Cancel mid-flight: workers stop, state → :cancelled ------------------
    await page.getByTestId('cancel').click();
    await expectTextEquals(stateValue, 'cancelled');

    // The recorded :outcome is :cancelled, visible in the status line.
    await expectTextContains(
      page.locator('[data-testid="outcome-value"]'),
      'cancelled',
    );

    // The progress snapshot at cancel-time is preserved (NOT zero,
    // NOT 300). Read the current done-count and assert it hasn't
    // advanced beyond what we just saw — if the cascade had failed
    // to destroy the workers, the bar would continue to advance.
    const cancelledAt = await readProgressDone(page);
    if (cancelledAt.done <= 0) {
      throw new Error('progress should be > 0 immediately after cancel');
    }
    if (cancelledAt.done >= cancelledAt.total) {
      throw new Error('cancel should fire before the work completes');
    }

    // Wait a beat and re-read — if the cascade is broken, in-flight
    // :after timers would keep firing and the bar would continue
    // to advance. With the cascade live, it stays put.
    await new Promise((r) => setTimeout(r, 500));
    const afterPause = await readProgressDone(page);
    if (afterPause.done !== cancelledAt.done) {
      throw new Error(
        `progress should stay frozen after cancel; was ${cancelledAt.done}, now ${afterPause.done}`,
      );
    }

    // --- Reset: back to :idle, progress cleared -------------------------------
    await page.getByTestId('reset').click();
    await expectTextEquals(stateValue, 'idle');
    const reset = await readProgressDone(page);
    if (reset.done !== 0) {
      throw new Error(`reset should clear progress to 0; got ${reset.done}`);
    }

    // --- Unmount cascade: hide the work-bench mid-flight ---------------------
    // Start work again, let it advance a little, then click
    // "Hide work-bench". The wrapper's r/with-let cleanup
    // dispatches [:work/flow [:cancel]]; the cascade fires.
    await page.getByTestId('start').click();
    await expectTextEquals(stateValue, 'working');
    // Wait for visible progress.
    {
      const t0 = Date.now();
      while (Date.now() - t0 < 5000) {
        const p = await readProgressDone(page);
        if (p.done > 0) break;
        await new Promise((r) => setTimeout(r, 50));
      }
    }
    await page.getByTestId('toggle-bench').click();
    // The bench is unmounted; its DOM is gone. Click Show to
    // re-mount, and assert the machine is in :cancelled — i.e. the
    // unmount cleanup did dispatch :cancel, the cascade ran, and
    // the parent state reflects it.
    await page.getByTestId('toggle-bench').click();
    await expectTextEquals(stateValue, 'cancelled');
    await expectTextContains(
      page.locator('[data-testid="outcome-value"]'),
      'cancelled',
    );

    // After re-show, the progress bar shows the snapshot at cancel
    // time — preserved across the unmount/remount.
    const afterRemount = await readProgressDone(page);
    if (afterRemount.done <= 0) {
      throw new Error('progress should be preserved across unmount/remount');
    }
  },
};
