/*
 * SSR per-frame hydration isolation — three frames each receive
 * their own :rf/hydrate dispatch from a per-frame payload slice;
 * the three resulting app-dbs are independent.
 *
 * Per spec/011-SSR.md §Frames are per-request + spec/002-Frames.md
 * §What lives in a frame: each frame owns its own app-db, router
 * queue, and signal-graph cache; the hydration protocol operates
 * frame-by-frame.
 *
 * What this spec walks:
 *
 *   - Three panels each render the seeded :n / :entries from
 *     their own payload slice. The three :n values are 10 / 99
 *     (counter A / B); the log frame has 2 entries.
 *   - Each frame's :rf/hydration metadata is independent — the
 *     summary block reports three distinct :server-hash values
 *     (aaaa1111, bbbb2222, cccc3333).
 *   - data-testid='summary-all-distinct' reads true, proving the
 *     three hashes are pairwise distinct (no cross-frame bleed).
 *   - Per-frame interactivity: clicking inc-A bumps only
 *     :counter/a's :n (10 → 11); :counter/b's :n stays at 99.
 */

const {
  expectTextEquals,
  expectVisible,
} = require('../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'testbeds/ssr-multi-frame (per-frame hydration isolation)',
  url: '/testbed-ssr-multi-frame/',
  run: async (page) => {
    // ---- (1) three panels render the seeded per-frame values --------
    await expectVisible(page.locator('[data-testid="panel-A"]'), 10000);
    await expectVisible(page.locator('[data-testid="panel-B"]'), 5000);
    await expectVisible(page.locator('[data-testid="panel-log"]'), 5000);

    await expectTextEquals(page.locator('[data-testid="n-A"]'), '10', 5000);
    await expectTextEquals(page.locator('[data-testid="n-B"]'), '99', 5000);
    await expectTextEquals(
      page.locator('[data-testid="entries-count"]'),
      '2',
      5000,
    );

    // ---- (2) per-frame :rf/hydration metadata landed independently --
    await expectTextEquals(page.locator('[data-testid="hyd-A"]'), 'true', 5000);
    await expectTextEquals(page.locator('[data-testid="hyd-B"]'), 'true', 5000);
    await expectTextEquals(page.locator('[data-testid="hyd-log"]'), 'true', 5000);

    // The payload-supplied :rf/render-hash per frame lands on the
    // matching frame's :rf/hydration :server-hash slot. Distinct
    // values prove the framework wrote to three distinct app-dbs.
    await expectTextEquals(
      page.locator('[data-testid="hash-A"]'),
      'aaaa1111',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="hash-B"]'),
      'bbbb2222',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="hash-log"]'),
      'cccc3333',
      5000,
    );

    // ---- (3) cross-frame readout via subscribe-value frame-id ------
    //
    // The hydration-summary block calls rf/subscribe-value with each
    // frame-id explicitly, reading the SAME [:hydration] query
    // against three different frames. Each call resolves the
    // matching :server-hash → proves the per-frame sub cache is
    // partitioned correctly.
    await expectTextEquals(
      page.locator('[data-testid="summary-a-hash"]'),
      'aaaa1111',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="summary-b-hash"]'),
      'bbbb2222',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="summary-log-hash"]'),
      'cccc3333',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="summary-all-distinct"]'),
      'true',
      5000,
    );

    // ---- (4) per-frame post-hydration interactivity -----------------
    //
    // Clicking inc-A dispatches `[::inc]` against `:counter/a`'s
    // router queue (the frame-provider's lexical scope picks the
    // frame). Only :counter/a's :n bumps; :counter/b stays at 99.
    await page.locator('[data-testid="inc-A"]').click();
    await expectTextEquals(page.locator('[data-testid="n-A"]'), '11', 5000);
    // Cross-frame isolation: B unchanged.
    await expectTextEquals(page.locator('[data-testid="n-B"]'), '99', 5000);

    await page.locator('[data-testid="inc-B"]').click();
    await page.locator('[data-testid="inc-B"]').click();
    await expectTextEquals(page.locator('[data-testid="n-B"]'), '101', 5000);
    // A unaffected.
    await expectTextEquals(page.locator('[data-testid="n-A"]'), '11', 5000);
  },
};
