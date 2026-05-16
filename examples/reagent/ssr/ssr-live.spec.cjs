/*
 * SSR + hydration — live server smoke test (rf2-j3dlc).
 *
 * Companion to ssr.spec.cjs (which exercises hydration against a
 * PRE-BAKED server-shaped index.html). This spec hits a REAL live JVM
 * Ring + Jetty server that runs the example's :clj `handle-request`
 * shape end-to-end:
 *
 *   browser GET http://127.0.0.1:<LIVE_SSR_PORT>/
 *     → re-frame.ssr.ring/ssr-handler
 *       → per-request frame, :on-create dispatches :rf/server-init
 *         → :fx-overrides redirects :rf.http/managed →
 *           :ssr.http/canned-articles (synchronous canned reply)
 *       → drain settles, app-db carries the articles slice
 *       → render-to-string emits HTML + :rf/render-hash on the root
 *       → builds the :rf/hydration-payload (whole-app-db policy)
 *       → wraps in default-html-shell, returns Ring response
 *
 * What this asserts (in order):
 *
 *   1. **Server-rendered content present BEFORE hydration** — a raw
 *      fetch() of the page from inside the browser observes the
 *      article titles, the data-rf-render-hash marker, and the inline
 *      __rf_payload script BEFORE any scripts execute. The
 *      `<script src='/main.js'>` is the last child in <body> so the
 *      raw HTML body is the server's authoritative pre-hydration
 *      shape.
 *
 *   2. **Hydration completes and the client renders** — the
 *      `Hide bodies` button + articles list are visible after navigate.
 *
 *   3. **Post-hydration interactivity** — the canonical "click the
 *      button after hydration" check: clicking `toggle-bodies` removes
 *      the body paragraphs (Reagent re-render fires the click through
 *      the full re-frame dispatch loop).
 *
 *   4. **Articles survive hydration** — the titles remain after the
 *      toggle (proves :rf/hydrate's replace-app-db policy seeded the
 *      :articles slice into the client's app-db).
 *
 * Lower-level JVM SSR/Ring contract details (status codes, header
 * round-trip, CRLF rejection, cookie wire shape, error projection) are
 * covered exhaustively by the JVM-side tests at
 * `implementation/ssr-ring/test/.../ring_e2e_validator_test.clj` and
 * `ring_test.clj`. This spec does NOT duplicate that coverage — it
 * narrowly proves the browser-side end-to-end path (live response →
 * hydration → interactive) works through real bytes on the wire.
 *
 * Where the URL comes from: the orchestrator
 * (examples/scripts/serve-and-run-examples-tests.cjs) launches the
 * JVM Jetty harness in parallel with the static http-server and
 * exposes its base URL via the EXAMPLES_LIVE_SSR_URL env var. We pin
 * the full URL on `spec.url` so run-examples-tests.cjs uses it
 * verbatim rather than prepending its default EXAMPLES_BASE_URL.
 */

const {
  expectCount,
  expectTextContains,
  expectVisible,
} = require('../../scripts/spec-helpers.cjs');

const LIVE_SSR_URL = process.env.EXAMPLES_LIVE_SSR_URL;

// When EXAMPLES_LIVE_SSR_URL is unset the spec emits SKIP rather than
// failing module-load (the runner's `require(file)` happens for ALL
// specs at startup; a throw here would crash the whole suite). The
// orchestrator (examples/scripts/serve-and-run-examples-tests.cjs)
// always sets it; the SKIP path covers manual `node run-examples-
// tests.cjs` invocations.
const SKIP_REASON = LIVE_SSR_URL
  ? false
  : 'EXAMPLES_LIVE_SSR_URL not set — run via examples/scripts/serve-and-run-examples-tests.cjs (which spins up the live JVM Jetty SSR harness on a side port).';

module.exports = {
  name: 'ssr-live (live JVM Ring handler)',
  skip: SKIP_REASON,
  // `url` is read for non-skipped runs only; when SKIP_REASON is truthy
  // the runner short-circuits BEFORE `url` is consumed (see
  // run-examples-tests.cjs `if (spec.skip)` block).
  url: (LIVE_SSR_URL || 'http://invalid.local') + '/',
  run: async (page) => {
    // ---- (1) Server-rendered content present BEFORE hydration -----------
    //
    // Issue a SECOND HTTP GET from inside the browser context against
    // the same origin and inspect the raw response body. This is the
    // bytes-on-wire view — no client JS has touched it. Anchors:
    //   - the article titles (server's authoritative slice)
    //   - the data-rf-render-hash root-element marker
    //   - the inline __rf_payload <script> with :rf/version 1
    //
    // Why this matters: if the SSR handler quietly fell back to an
    // empty shell and let the client render-from-scratch, the page
    // would still pass the visible-content assertions below (the
    // client would just render twice). Asserting the raw HTML
    // structurally pins SSR as the source of the first paint.
    const rawHtml = await page.evaluate(async (url) => {
      const resp = await fetch(url, { cache: 'no-store' });
      return { status: resp.status, body: await resp.text() };
    }, LIVE_SSR_URL + '/');
    if (rawHtml.status !== 200) {
      throw new Error(`live-SSR GET / returned ${rawHtml.status}; expected 200`);
    }
    if (!rawHtml.body.includes('Article A') || !rawHtml.body.includes('Article B')) {
      throw new Error(`raw SSR HTML does not contain article titles; body head: ${rawHtml.body.slice(0, 400)}`);
    }
    if (!/data-rf-render-hash="[0-9a-f]{8}"/.test(rawHtml.body)) {
      throw new Error(`raw SSR HTML does not carry a data-rf-render-hash on the root element; body head: ${rawHtml.body.slice(0, 400)}`);
    }
    if (!rawHtml.body.includes('id="__rf_payload"')) {
      throw new Error(`raw SSR HTML does not embed the __rf_payload <script>; body head: ${rawHtml.body.slice(0, 400)}`);
    }
    if (!rawHtml.body.includes(':rf/version 1')) {
      throw new Error(`raw __rf_payload does not carry :rf/version 1; body head: ${rawHtml.body.slice(0, 400)}`);
    }

    // ---- (2) Page mounts; visible content survives hydration -----------
    //
    // The page.goto(...) in run-examples-tests.cjs has already loaded
    // the URL with waitUntil:'load' — `main.js` has executed and
    // dispatched :rf/hydrate. The articles should now be visible in
    // the live DOM.
    const articlesList = page.getByTestId('articles-list');
    const bodies       = page.getByTestId('article-body');
    const toggle       = page.getByTestId('toggle-bodies');

    await expectVisible(articlesList, 10000);
    await expectTextContains(articlesList, 'Article A', 5000);
    await expectTextContains(articlesList, 'Article B', 5000);
    await expectVisible(bodies.first(), 5000);

    // ---- (3) Post-hydration interactivity ------------------------------
    //
    // The canonical "click the button after hydration" check. The
    // server pre-rendered the button (raw HTML above carries the
    // toggle markup); the click goes through the FULL re-frame
    // dispatch / sub / re-render loop on the hydrated client. After
    // the click, the body paragraphs are detached.
    await expectVisible(toggle, 5000);
    await toggle.click();
    await expectCount(bodies, 0, 5000);

    // ---- (4) Articles survive the hydration handoff --------------------
    //
    // Titles remain — the hydrated app-db still holds the :articles
    // slice (carried over from the server's payload via :rf/hydrate's
    // replace-app-db policy).
    await expectTextContains(articlesList, 'Article A', 2000);
    await expectTextContains(articlesList, 'Article B', 2000);
  },
};
