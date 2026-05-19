/*
 * dashboard-uix — UIx design-led example smoke (rf2-t7t6f).
 *
 * Atlas analytics dashboard (status tiles + filter chips + sparkline
 * cards). The smoke proves:
 *
 *   1. Initial render mounts all six metric cards (revenue, signups,
 *      latency, errors, dau, sessions).
 *   2. Toggling the :perf filter chip hides the latency + errors cards.
 *   3. Toggling :perf back on restores them.
 */
const { expectVisible, expectCount } =
  require('../../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'dashboard-uix smoke (UIx · design-led)',
  url:  '/dashboard-uix/',
  run:  async (page) => {
    const grid = page.locator('[data-testid="dashboard-grid"]');
    await expectVisible(grid, 10000);

    const cards = page.locator('[data-testid^="dashboard-card-"]');

    // 1. All six metric cards visible by default.
    await expectCount(cards, 6, 10000);

    // 2. Toggle the perf filter — :latency + :errors disappear.
    await page.locator('[data-testid="dashboard-chip-perf"]').click();
    await expectCount(cards, 4);

    // 3. Toggle perf back on — all six return.
    await page.locator('[data-testid="dashboard-chip-perf"]').click();
    await expectCount(cards, 6);
  },
};
