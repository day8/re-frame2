# Story — Tutorial: Playwright e2e starter recipe (rf2-6qqry)

> A minimum-viable Playwright probe for a Story-using app. Navigates
> to `#/stories/<variant-id>`, waits for the canvas to mount, asserts
> a `[data-test=...]` selector, captures a screenshot keyed by
> `snapshot-identity`. Pair this with the production-quality gates in
> [`tools/story/test/story_browser_scenarios.cjs`](../test/story_browser_scenarios.cjs)
> and [`tools/story/test/story_feature_load.cjs`](../test/story_feature_load.cjs)
> once you outgrow the starter shape.

## Audience and scope

This recipe is for a re-frame2 app that already mounts the Story
shell via `re-frame.story/mount-shell!` and now wants to add a
Playwright e2e probe of their own variants. The recipe covers:

- Wiring Playwright to a shadow-cljs `npm run watch` (or a static
  `story:build` output) URL.
- Navigating to a variant via its URL hash route.
- Waiting for the canvas to mount and Story's chrome to settle.
- Asserting against a `[data-test=...]` selector in the variant's
  rendered hiccup.
- Capturing a screenshot keyed by `snapshot-identity` so a visual-
  regression service (Chromatic, Percy, Argos, raw `pixelmatch`) can
  diff stable cells across runs.

What this recipe is **not**: a production e2e harness for Story's
own QA. The shipped `story_browser_scenarios.cjs` and
`story_feature_load.cjs` are gate-grade; this is the tutorial-shaped
on-ramp.

## Prerequisites

- Story 0.x shell mounted at the page root via `mount-shell!`. The
  variant URL hash route (`#/stories/<variant-id>`) MUST be wired
  through `re-frame.story.ui.url-state/hydrate!` at boot (the
  shell's standard install does this — see
  [`014-Chrome-Features.md`](014-Chrome-Features.md) §URL state).
- Variants of interest carry stable `[data-test=...]` selectors in
  their rendered hiccup. The view, not the variant body, declares
  these — they ride with the component into every variant of every
  story that uses it.
- Playwright installed in the consumer's repo (`npm i -D
  @playwright/test`). The recipe assumes Playwright 1.40+; older
  versions have a different `page.evaluate` signature but the
  shape is unchanged.
- A served Story shell. Three common shapes:
  - `npm run watch` (shadow-cljs dev server) — fast iteration.
  - `npm run story:build` + a static file server — closer to
    production. See [`013-Static-Build.md`](013-Static-Build.md).
  - A deployed `story:build` artefact on GitHub Pages / Netlify /
    Vercel — for CI runs against a published shell.

## The starter probe

Create `e2e/story-probe.spec.js` in your repo (the path is
arbitrary — Playwright globs whatever `playwright.config.js` points
at). Adjust `BASE_URL` to wherever your Story shell is served.

```js
// e2e/story-probe.spec.js
const { test, expect } = require('@playwright/test');

const BASE_URL = process.env.STORY_BASE_URL || 'http://localhost:8080';

/** Navigate to a Story variant via its URL hash route. */
async function gotoVariant(page, variantId) {
  await page.goto(`${BASE_URL}/#/stories/${encodeURIComponent(variantId)}`,
    { waitUntil: 'load' });

  // The shell mounts asynchronously; wait for the three landmark
  // regions Story always renders.
  await page.getByRole('navigation').waitFor({ state: 'visible', timeout: 10000 });
  await page.getByRole('main').waitFor({ state: 'visible', timeout: 10000 });

  // Dismiss the first-visit help overlay if present — it intercepts
  // pointer events on a fresh localStorage.
  const help = page.getByRole('dialog', { name: /Story playground help/i });
  if (await help.isVisible().catch(() => false)) {
    await help.getByRole('button', { name: /Got it/i }).click();
  }

  // The variant canvas carries a `data-test-variant` attribute set
  // to the variant's id; wait for it.
  await page.locator(`[data-test-variant="${variantId}"]`)
    .waitFor({ state: 'visible', timeout: 10000 });
}

/** Read Story's snapshot-identity for the active variant from the canvas. */
async function snapshotIdentity(page, variantId) {
  return page.locator(`[data-test-variant="${variantId}"]`)
    .getAttribute('data-snapshot-hash');
}

test('counter at-five renders the value 5', async ({ page }) => {
  await gotoVariant(page, ':story.counter/at-five');

  // The view declares `[data-test="counter-value"]`; assert on it.
  await expect(page.locator('[data-test="counter-value"]')).toHaveText('5');

  // Capture a screenshot keyed by snapshot-identity so a visual-
  // regression diff is stable across runs.
  const hash = await snapshotIdentity(page, ':story.counter/at-five');
  await page.screenshot({ path: `screenshots/counter-at-five-${hash}.png` });
});
```

That's the full minimum probe — three test-relevant steps (navigate,
assert, screenshot) plus the canonical wait pattern.

## How it works

### URL hash routing

Story's shell consumes `window.location.hash` to resolve which
variant to mount. The hash format is `#/stories/<url-encoded
variant-id>`. The fragment encoder is symmetric with
`variant-share-url`'s output (per
[`API.md`](API.md) §URL surfaces), so a URL pasted from the share
popover navigates correctly.

### Landmark waits

Three semantic landmarks always render once the shell is up:

| Landmark | Role | Purpose |
|---|---|---|
| `navigation` | `<nav>` | Sidebar with the variant tree |
| `main` | `<main>` | Variant canvas |
| `complementary` | `<aside>` | The RHS Causa embed |

Waiting for `main` then for the canvas's `data-test-variant`
selector is the correct gate — the canvas mounts only after the
shell resolves the variant id from the URL hash.

### The first-visit help overlay

Story ships a one-shot first-visit help overlay (rf2-381i). On a
fresh `localStorage` it covers the canvas with a modal dialog and
intercepts pointer events. The recipe dismisses it if open; for CI
runs that hit a persistent profile you can instead prime the
"already-seen" key once at the start of the suite:

```js
test.beforeAll(async ({ browser }) => {
  const ctx = await browser.newContext();
  const page = await ctx.newPage();
  await page.goto(BASE_URL);
  await page.evaluate(() => {
    localStorage.setItem('re-frame.story/seen-help-v1', '1');
  });
  await ctx.close();
});
```

### `data-test-variant` and `data-snapshot-hash`

Story stamps two `data-*` attributes onto the canvas root:

| Attribute | Value | Use |
|---|---|---|
| `data-test-variant` | The variant id (`:story.counter/at-five`) | Probe scope — assert "I'm looking at the right cell." |
| `data-snapshot-hash` | The content-hash from `snapshot-identity` | Visual-regression key — name screenshots by hash so a stable variant produces a stable filename. |

The hash includes `:variant-id`, `:active-modes`, `:substrate`, and
`:content-hash` (the canvas's rendered hiccup). Changing any of
those changes the hash; pixel-diffing then compares like-for-like.
See [`002-Runtime.md`](002-Runtime.md) §Snapshot identity for the
full hash recipe.

### Author-side: declaring `[data-test=...]` selectors

Selectors are the view's responsibility, not the variant body. The
canonical pattern:

```clojure
(rf/reg-view :app.ui/counter
  (fn [_]
    (let [n @(rf/subscribe [:counter/value])]
      [:div
       [:button {:on-click  #(rf/dispatch [:counter/decrement])
                 :data-test "counter-decrement"} "−"]
       [:span {:data-test "counter-value"} n]
       [:button {:on-click  #(rf/dispatch [:counter/increment])
                 :data-test "counter-increment"} "+"]])))
```

Every variant of every story that mounts this view gets these
selectors. The variant body stays pure data.

## Driving state — Story's preferred path

Playwright `userEvent.click(...)` works against Story canvases, but
it is rarely the right tool for Story-driven probes. The variant
body's `:play` slot is already a vector of events that drives the
variant's frame; let Story dispatch them and use Playwright only to
assert the final rendered state.

```clojure
;; Variant body declares the driving events:
(story/reg-variant :story.counter/driven
  {:events [[:counter/initialise]]
   :play   [[:counter/increment]
            [:counter/increment]
            [:counter/increment]]})
```

```js
// Playwright asserts the final state:
test('counter driven by play body lands at 3', async ({ page }) => {
  await gotoVariant(page, ':story.counter/driven');
  await expect(page.locator('[data-test="counter-value"]')).toHaveText('3');
});
```

Why prefer `:play` to Playwright clicks?

- **Events round-trip with the share URL.** A URL copied from the
  share popover reproduces the exact `:play` sequence on the
  reader's machine; clicks in a Playwright file don't.
- **EDN, not DOM.** The `:play` body is pure data — testable from
  CLJS / JVM via `run-variant` without a browser at all.
- **Record-don't-throw.** Story's `:rf.assert/*` events record into
  `:assertions` and the sequence continues. Playwright's
  `expect(...)` throws on first failure and stops.

Use Playwright when you genuinely need a browser-only surface —
real-pointer events, viewport sizing, file-upload dialogs,
permissions prompts, multi-tab flows. Use `:play` for everything
else.

## Common pitfalls

- **Forgetting to URL-encode the variant id.** A variant id like
  `:story.auth.login-form/happy-path` carries a `/`; without
  `encodeURIComponent` it breaks the hash route.
- **Asserting before the canvas mounts.** The landmark waits are
  not optional. The shell's internal nav-to-mount latency is a
  few hundred ms on a warm dev server; without the wait the
  selector resolves to an empty page.
- **Screenshots keyed by variant id only, not snapshot-identity.**
  Two cells of the same variant against different modes have
  different pixels; the hash distinguishes them. A filename of
  `counter-at-five.png` accumulates collisions across mode toggles.
- **Hitting `:advanced`-compiled bundles.** Production-compiled
  Story bundles elide all `reg-*` calls to `nil` (see
  [`005-SOTA-Features.md`](005-SOTA-Features.md) §Production
  elision). Run probes against the dev / `story:build` shell, not
  against your application's production bundle.

## Visual regression — the hash-keyed pattern

If you ship a visual-regression service, the canonical pattern is:

1. Per variant of interest, capture one screenshot per
   `(variant × mode × substrate)` cell, naming it by the
   `data-snapshot-hash` value.
2. On the next run, capture again. If the hash matches a baseline,
   pixel-diff against it. If the hash changed, treat it as a new
   baseline candidate and surface for review.
3. Cell churn (variant body / args / mode declaration changes)
   produces a new hash automatically — you never diff apples to
   oranges.

The hash is content-derived, not timestamp-derived; identical
declarations across machines produce identical hashes. Chromatic /
Percy / Argos consume the screenshots; Story owns the keying.

## Beyond the starter — when to graduate

Once your suite outgrows the starter shape, reach for the
production helpers shipped in `tools/story/test/`:

- **`story_browser_scenarios.cjs`** — focused per-feature scenarios
  with rich error context (`feature` / `phase` / `storyContext`
  attached to thrown errors). The right shape for multi-step
  workflows.
- **`story_feature_load.cjs`** — wide gate-grade coverage of every
  Story feature surface. The right shape for "does Story still
  work in my app?" smoke runs.
- **`examples/scripts/serve-and-run-story-play-scripts.cjs`** —
  serves the shell and auto-drives every variant carrying a
  `:play-script` body. The right shape for headless CI-as-test
  runs over a corpus of variants.
- **Multi-frame CLJS e2e** — for tests where you can avoid a real
  browser entirely, see the `re-frame.story.test-helpers.e2e-multi-frame`
  helpers and any of the `tools/story/test/re_frame/story/panels_e2e/`
  test files. Sub-millisecond per case, no Playwright dependency.

## Cross-references

- [`002-Runtime.md`](002-Runtime.md) §Snapshot identity — the
  content-hash recipe.
- [`004-Assertions.md`](004-Assertions.md) — `:rf.assert/*`
  events and the record-don't-throw contract.
- [`013-Static-Build.md`](013-Static-Build.md) — `story:build`
  output for CI against a static shell.
- [`014-Chrome-Features.md`](014-Chrome-Features.md) §URL state —
  hash-route hydration.
- [`Tutorial-Embed.md`](Tutorial-Embed.md) — the companion recipe
  for embedding variants as iframes in docs sites.
- [`API.md`](API.md) §URL surfaces — the three URL axes Story carries.
