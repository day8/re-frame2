# docs/scripts/

Build-time and content-generation helpers for the docs site.

## `generate-tutorial-screenshots.cjs`

Drives Playwright through the Causa and Story testbeds and captures the
annotated screenshots embedded in the [Causa](../causa/index.md) and
[Story](../story/index.md) tutorials.

### When to re-run

Re-run after any UI change that would alter how the panels look. The output
PNGs are tracked in git, so re-running is opt-in — the docs site doesn't
depend on Playwright being available at build time.

### How to run

The pipeline needs the example bundles compiled and served. The
canonical examples test orchestrator (`npm run test:examples` from
`implementation/`) does both, then runs its own assertion suite and tears
down the server.

For screenshot regeneration we just need the server. The simplest
sequence:

```bash
# Terminal A — build + serve the example bundles
cd implementation
npm install                                  # one-time
npx shadow-cljs compile examples/counter examples/counter-with-stories
# stage the index.html files
node ../examples/scripts/serve-and-run-examples-tests.cjs
# (or run an http-server over implementation/out/examples directly)
```

```bash
# Terminal B — capture
cd /path/to/re-frame2
node docs/scripts/generate-tutorial-screenshots.cjs
```

The script writes:

```
docs/images/causa/*.png
docs/images/story/*.png
```

### Determinism notes

- Viewport pinned to **1280×800**.
- The Story shell's first-visit help dialog is dismissed via
  `localStorage` seeding (`re-frame.story/seen-help-v1`).
- Causa's first paint is gated by waiting for
  `[data-testid="rf-causa-shell"]`.
- The counter testbed seeds `:count` to a fixed value at boot.

### Annotations

Each scene declares a list of rectangles via the `regions(page)` callback;
the pipeline injects a thin DOM overlay (boxes + coloured labels) just
before `page.screenshot` fires. The overlay is torn down between scenes.
No external image-processing dependency — Playwright + a tiny inline
helper is enough.

### Adding a new scene

```js
SCENES.push({
  id: 'causa-my-new-panel',
  out: path.join(OUT_CAUSA, '12-my-new-panel.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await openCausa(page);
    await navCausa(page, 'my-new-panel');
  },
  regions: async (page) => [
    { x: 100, y: 50, w: 300, h: 60, colour: '#e53935', label: 'thing to call out' },
  ],
});
```

Then re-run the script and commit the new PNG alongside its doc page.
