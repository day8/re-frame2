/*
 * SSR baseline — hydration round-trip + per-request lifecycle.
 *
 * The static index.html bakes pre-rendered HTML plus an EDN
 * :rf/hydration-payload script (mirroring what
 * `re-frame.ssr/render-to-string` + the JVM payload builder emit per
 * spec/011-SSR.md).  The browser-side `run`:
 *
 *   1. Reads the payload from `<script id="__rf_payload">`.
 *   2. Dispatches `:rf/hydrate` — replace-app-db policy seeds the
 *      client app-db with the server's authoritative slice.
 *   3. Renders against the now-seeded app-db.
 *   4. Calls `verify-hydration!` post-first-render — the hash-mismatch
 *      detection path (no mismatch on this surface; the baseline
 *      payload's :rf/render-hash is nil, see ssr_hydration_mismatch/
 *      for the deliberate-mismatch surface).
 *
 * This spec walks the round-trip:
 *
 *   - The pre-rendered HTML is visible BEFORE the bundle loads (the
 *     server-rendered counter, title, and per-request response panel
 *     read 7/seeded/200 from the static markup).
 *   - The bundle loads cleanly — no console errors, no pageerrors.
 *   - After hydration, the `data-testid="hydrated"` marker replaces
 *     `not-hydrated` — proves `:rf/hydration` metadata landed in
 *     app-db.
 *   - The reactive substrate is live: clicking `inc` mutates count
 *     7 → 8; clicking `set-title` writes "hydrated" into title.
 *   - The payload's :rf/response slice — status, content-type, the
 *     `session` cookie — round-trips into the view layer.  Proves
 *     per-request :rf.server/* response state survives the wire.
 *   - The trace listener captured at least the hydrate's
 *     compatibility-check trace events on the window mirror.
 */

const {
  expectTextEquals,
  expectTextContains,
  expectVisible,
} = require('../../examples/scripts/spec-helpers.cjs');

module.exports = {
  name: 'testbeds/ssr-basic (hydration baseline)',
  url: '/testbed-ssr-basic/',
  run: async (page) => {
    // ---- (1) static HTML present before bundle interaction ----------
    //
    // The page is loaded at this point; the static HTML's pre-rendered
    // counter / title are visible. Pre-hydration the marker reads
    // `not-hydrated`; the bundle's mount switches it to `hydrated`.
    const root = page.locator('[data-testid="ssr-basic"]');
    await expectVisible(root, 10000);

    // ---- (2) hydration handshake completes --------------------------
    const hydrated = page.locator('[data-testid="hydrated"]');
    await expectVisible(hydrated, 10000);
    await expectTextEquals(hydrated, 'hydrated', 5000);

    // ---- (3) seeded state from payload's :rf/app-db -----------------
    //
    // The payload baked {:count 7 :title "seeded"} — the client view
    // renders these post-hydrate, replacing whatever the pre-rendered
    // HTML displayed (which happens to match by construction, but the
    // value source after hydration is the reactive substrate).
    await expectTextEquals(page.locator('[data-testid="count"]'), '7', 5000);
    await expectTextEquals(page.locator('[data-testid="title"]'), 'seeded', 5000);

    // ---- (4) reactive substrate is live -----------------------------
    //
    // Click `inc` — handler dispatches, app-db's :count slot bumps,
    // sub recomputes, view re-renders. Proves the hydration handoff
    // didn't break the six-domino loop.
    await page.locator('[data-testid="inc"]').click();
    await expectTextEquals(page.locator('[data-testid="count"]'), '8', 5000);

    // Click `set-title` — same domino path, different slot.
    await page.locator('[data-testid="set-title"]').click();
    await expectTextEquals(page.locator('[data-testid="title"]'), 'hydrated', 5000);

    // ---- (5) per-request :rf/response round-trip --------------------
    //
    // The payload's :rf/response slice carried {:status 200 :headers
    // {"content-type" "text/html; charset=utf-8" ...} :cookies [{:name
    // "session" ...}]}. After hydration the view reads it back from
    // [:server-response] in app-db.
    await expectTextEquals(
      page.locator('[data-testid="resp-status"]'),
      '200',
      5000,
    );
    await expectTextContains(
      page.locator('[data-testid="resp-ct"]'),
      'text/html',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="resp-cookies-count"]'),
      '1',
      5000,
    );
    await expectTextEquals(
      page.locator('[data-testid="resp-cookie-name"]'),
      'session',
      5000,
    );

    // ---- (6) trace bus mirrored the hydration handshake -------------
    //
    // The cljs side registered a trace listener that mirrors every
    // :rf.ssr/* and :rf/hydrate trace event onto
    // window.__rf_trace_events(). The hydration handshake fired the
    // two compatibility-check fxs (:rf.ssr/check-version and, when
    // the payload carries one, :rf.ssr/check-schema-digest). With no
    // late-bind hooks registered on this surface we expect at least
    // one :rf.ssr/compatibility-check-skipped trace.
    const events = await page.evaluate(() => {
      const fn = window.__rf_trace_events;
      return fn ? fn() : [];
    });
    if (!Array.isArray(events) || events.length === 0) {
      throw new Error(
        `expected at least one :rf.ssr/* trace event on window.__rf_trace_events(); got ${JSON.stringify(events)}`,
      );
    }
    const ops = events.map((e) => e.operation);
    if (!ops.some((op) => op === ':rf.ssr/compatibility-check-skipped')) {
      throw new Error(
        `expected at least one :rf.ssr/compatibility-check-skipped trace (no :rf2/runtime-version hook registered on this surface); got operations: ${JSON.stringify(ops)}`,
      );
    }

    // ---- (7) no :rf.ssr/hydration-mismatch on the baseline ----------
    //
    // The payload's :rf/render-hash is nil, so verify-hydration! has
    // nothing to compare against and silently no-ops. A mismatch trace
    // here would be a real bug.
    if (ops.includes(':rf.ssr/hydration-mismatch')) {
      throw new Error(
        `unexpected :rf.ssr/hydration-mismatch trace on the baseline surface (server-hash was nil); got: ${JSON.stringify(events)}`,
      );
    }
  },
};
