/*
 * SSR hydration-mismatch — verify-hydration! emits a structured
 * :rf.ssr/hydration-mismatch trace event with the payload visible
 * inline in dev mode.
 *
 * The static index.html bakes a known-wrong :rf/render-hash
 * ("deadbeef"). On first render, verify-hydration! reads the
 * server hash, hashes the resolved client tree, disagrees, emits
 * :rf.ssr/hydration-mismatch (op-type :error, recovery
 * :warned-and-replaced) per spec/011-SSR.md §Hydration-mismatch
 * detection.
 *
 * This spec walks the mismatch path:
 *
 *   - Hydration completes (data-testid='hydrated' marker visible).
 *   - The mismatch banner renders the captured trace's tags
 *     verbatim:
 *       - server-hash = 'deadbeef' (the baked-wrong value)
 *       - client-hash = a non-nil 8-char lowercase-hex string
 *       - failing-id = ':rf/hydrate' (v1 body-mismatch discriminator)
 *       - recovery = ':warned-and-replaced' (runtime default)
 *   - The window mirror exposes the same trace event.
 *   - The page remains interactive post-mismatch: clicking inc
 *     mutates the counter — proves warn-and-replace is degraded-
 *     but-running, not crash.
 */

const {
  expectTextEquals,
  expectTextContains,
  expectVisible,
} = require('../../examples/scripts/spec-helpers.cjs');

const HEX_8 = /^[0-9a-f]{8}$/;

module.exports = {
  name: 'testbeds/ssr-hydration-mismatch (mismatch trace fires)',
  url: '/testbed-ssr-hydration-mismatch/',
  run: async (page) => {
    // ---- (1) hydration handshake completes --------------------------
    const hydrated = page.locator('[data-testid="hydrated"]');
    await expectVisible(hydrated, 10000);
    await expectTextEquals(hydrated, 'hydrated', 5000);

    // ---- (2) the mismatch banner renders the structured trace ------
    //
    // The trace listener captured :rf.ssr/hydration-mismatch and
    // routed its tags through ::record-mismatch; the banner view
    // subscribes to [:mismatch] and renders the structured payload
    // inline. Without this, dev-mode visibility would require dev-
    // tools — Spec 011 §Mismatch recovery and configuration covers
    // the contract.
    const banner = page.locator('[data-testid="mismatch-banner"]');
    await expectVisible(banner, 10000);

    // server-hash literally matches the baked-wrong value.
    await expectTextEquals(
      page.locator('[data-testid="mismatch-server-hash"]'),
      'deadbeef',
      5000,
    );

    // client-hash is whatever the runtime computed — assert on
    // shape only (8 lowercase-hex chars per spec/011 §Hydration-
    // mismatch detection).
    const clientHash = await page
      .locator('[data-testid="mismatch-client-hash"]')
      .textContent();
    if (!HEX_8.test((clientHash || '').trim())) {
      throw new Error(
        `expected mismatch-client-hash to be an 8-char lowercase-hex string; got "${clientHash}"`,
      );
    }
    if ((clientHash || '').trim() === 'deadbeef') {
      throw new Error(
        'client-hash equalled the (deliberately wrong) server-hash — that would be a spec violation',
      );
    }

    // failing-id is :rf/hydrate per spec/011 v1 body-mismatch
    // discriminator (head-mismatch is reserved post-v1).
    await expectTextEquals(
      page.locator('[data-testid="mismatch-failing-id"]'),
      ':rf/hydrate',
      5000,
    );

    // recovery is :warned-and-replaced — the runtime default.
    await expectTextEquals(
      page.locator('[data-testid="mismatch-recovery"]'),
      ':warned-and-replaced',
      5000,
    );

    // ---- (3) the window mirror exposes the same trace ---------------
    const events = await page.evaluate(() => {
      const fn = window.__rf_trace_events;
      return fn ? fn() : [];
    });
    const mismatch = (events || []).find(
      (e) => e.operation === ':rf.ssr/hydration-mismatch',
    );
    if (!mismatch) {
      throw new Error(
        `expected a :rf.ssr/hydration-mismatch trace on window.__rf_trace_events(); got operations: ${JSON.stringify((events || []).map((e) => e.operation))}`,
      );
    }
    if (mismatch.server_hash !== undefined && mismatch.server_hash !== 'deadbeef') {
      // window.__rf_trace_events() projects keys with hyphens converted to
      // underscores via cljs->js. Either spelling can land — accept both.
      throw new Error(
        `expected mismatch.server_hash to equal 'deadbeef'; got "${mismatch.server_hash}"`,
      );
    }
    if (mismatch['server-hash'] !== undefined && mismatch['server-hash'] !== 'deadbeef') {
      throw new Error(
        `expected mismatch['server-hash'] to equal 'deadbeef'; got "${mismatch['server-hash']}"`,
      );
    }
    const op = mismatch.op_type || mismatch['op-type'];
    if (op !== ':error') {
      throw new Error(
        `expected :rf.ssr/hydration-mismatch op-type to be ':error'; got "${op}"`,
      );
    }

    // ---- (4) page is still interactive post-mismatch ---------------
    //
    // Default recovery is warn-and-replace — the client renders
    // against the seeded state (count=0) and the dispatch pipeline
    // is live. Click `inc`, observe the count bump.
    await expectTextEquals(page.locator('[data-testid="count"]'), '0', 5000);
    await page.locator('[data-testid="inc"]').click();
    await expectTextEquals(page.locator('[data-testid="count"]'), '1', 5000);
  },
};
