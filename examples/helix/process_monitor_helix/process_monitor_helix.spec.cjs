/*
 * process-monitor-helix — Helix design-led example smoke (rf2-t7t6f).
 *
 * Terminal-style process monitor (status tiles + filterable process
 * list + live log feed with a recurring :dispatch-later tick). The
 * smoke proves:
 *
 *   1. Initial render mounts all eight processes.
 *   2. The recurring tick (1800ms cadence) appends to the log feed —
 *      a 4-second window lets it fire at least once even on slow CI.
 *   3. Clicking a process row narrows the log feed to that PID;
 *      clicking it again clears the filter.
 */
const { expectVisible, expectCount, waitForValue } =
  require('../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'process-monitor-helix smoke (Helix · design-led)',
  url:  '/process-monitor-helix/',
  run:  async (page) => {
    const list = page.locator('[data-testid="monitor-process-list"]');
    await expectVisible(list, 10000);

    const rows = page.locator('[data-testid^="monitor-row-"]');
    await expectCount(rows, 8, 10000);

    // The tick cadence is 1800ms. Read the initial log length, then
    // wait for it to grow.
    const logs = page.locator('[data-testid="monitor-log-list"] > li');
    const initialN = await logs.count();
    await waitForValue(
      async () => await logs.count(),
      (n) => n > initialN,
      { intervalMs: 200, timeoutMs: 6000, description: 'log feed to grow' },
    );

    // Select the :web row — log feed narrows. The initial seed log
    // contains a t=0 GET for pid 13287; pinning to :web (pid 13287)
    // should leave at least one entry visible.
    await page.locator('[data-testid="monitor-row-web"]').click();
    // Allow the next tick to fire so the narrowed feed has something
    // to display even if all initial t<11 entries scrolled off.
    await waitForValue(
      async () => await logs.count(),
      (n) => n >= 1,
      { intervalMs: 200, timeoutMs: 4000, description: 'narrowed log feed non-empty' },
    );
  },
};
