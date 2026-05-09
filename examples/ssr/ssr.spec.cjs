/*
 * SSR + hydration — smoke test (rf2-vq2s).
 *
 * Strategy: pre-rendered HTML and a serialised :rf/app-db payload are
 * baked into the static index.html (next to ssr/core.cljc) — exactly
 * the shape `handle-request` in core.cljc would emit if a real Clojure
 * server were sitting in front. The browser-side `run` reads the
 * `<script id="__rf_payload">` block, dispatches `:rf/hydrate`, and
 * renders against the now-seeded app-db.
 *
 * What this asserts:
 *   1. The pre-rendered article titles are visible immediately after
 *      navigation (server side did its job).
 *   2. After the client mounts, clicking the "Hide bodies" button
 *      toggles the article bodies — proof that the hydrated app is
 *      fully reactive (the click runs through the real dispatch /
 *      sub / re-render loop, not just the static markup).
 *   3. After hydration, the articles list still has both items —
 *      :rf/hydrate's replace-app-db policy carried the payload's
 *      :articles slice into client app-db.
 */

const {
  expectTextContains,
  expectVisible,
} = require('../scripts/spec-helpers.cjs');

module.exports = {
  name: 'ssr (hydration)',
  url: '/ssr/',
  run: async (page) => {
    const heading = page.locator('h1');
    await expectVisible(heading, 10000);
    await expectTextContains(heading, 'Recent articles', 5000);

    // The pre-rendered article titles are present from the static HTML.
    await expectTextContains(page.locator('ul'), 'Article A', 5000);
    await expectTextContains(page.locator('ul'), 'Article B', 5000);

    // Bodies are visible by default.
    await expectVisible(page.locator('p.body').first(), 5000);

    // Clicking the toggle goes through the real dispatch / re-render
    // path on the now-hydrated client. After click, the bodies are
    // gone but the titles remain — proves the client took over.
    const toggle = page.locator('button.toggle-bodies');
    await expectVisible(toggle, 5000);
    await toggle.click();
    // Poll until the body paragraphs are detached — Reagent unmounts
    // them when (when show-bodies? ...) goes false.
    const start = Date.now();
    while (Date.now() - start < 5000) {
      const count = await page.locator('p.body').count();
      if (count === 0) break;
      await new Promise((r) => setTimeout(r, 50));
    }
    if ((await page.locator('p.body').count()) !== 0) {
      throw new Error('clicking "Hide bodies" did not remove p.body elements');
    }

    // Titles remain — hydrated app-db still holds the articles.
    await expectTextContains(page.locator('ul'), 'Article A', 2000);
    await expectTextContains(page.locator('ul'), 'Article B', 2000);
  },
};
