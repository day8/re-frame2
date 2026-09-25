# docs/scripts/

Build-time and content-generation helpers for the docs site.

There are 2 independent tutorial screenshot generators — one for
[Story](../story/index.md), one for [Xray](../xray/index.md). They have
different testbeds, serving models and output directories. Run them
separately. The sections below document each in full.

| Generator | Tutorial | Output |
| --------- | -------- | ------ |
| `generate-story-tutorial-screenshots.cjs` | [Story](../story/index.md) | `docs/images/story/story-tutorial-*.png` |
| `generate-tutorial-screenshots.cjs` | [Xray](../xray/index.md) | `docs/images/xray/*.png` |

The output PNGs for both generators are tracked in git, so the MkDocs
site renders without needing shadow-cljs or Playwright at build time.
Re-running either generator is opt-in.

## Story — `generate-story-tutorial-screenshots.cjs`

This generator drives Playwright through the live Story testbeds. It
captures the screenshots embedded in the [Story](../story/index.md)
tutorial.

The generator serves the compiled Story testbed bundles from
`implementation/out` over its own internal HTTP server, then captures
each Story shell scene. You do not need an external orchestrator — just
the compiled bundles.

### How to run

Compile the Story example bundles first, then run the generator from the
repo root:

```bash
cd implementation
npm install                                   # one-time
npx shadow-cljs compile :examples/login-form \
                        :examples/counter-with-stories \
                        :examples/nine-states-with-stories
cd ..
node docs/scripts/generate-story-tutorial-screenshots.cjs
```

It writes:

```
docs/images/story/story-tutorial-*.png
```

### Determinism notes

- viewport pinned to 1440×900
- each scene waits for a distinctive `[data-test=...]` anchor before
  shooting
- the "seen help" flag is pre-seeded in `localStorage` so the
  first-run help overlay never appears

### When to re-run

Re-run after any Story UI change that would alter how the panels look,
then commit the regenerated PNGs alongside the doc page.

## Xray — `generate-tutorial-screenshots.cjs`

This generator drives a headless Chromium through the Xray testbed. It
captures the annotated screenshots embedded in the
[Xray](../xray/index.md) tutorial.

Unlike the Story generator, this one does not serve the bundles itself.
It expects the counter example (`:examples/counter`, which carries the
Xray preload) to be served already, and drives Xray on that page.

### How to run

The standalone-example runner `examples/scripts/serve-example.cjs`
(`npm run dev:example` from `implementation/`) compiles the counter
example, stages its page, and serves it at the root of
`http://127.0.0.1:8050` until you stop it. That is the default port of
the examples port resolver, and the generator probes the same port.
When 8050 is busy, or `EXAMPLES_PORT` picks another port, the runner
prints the URL it serves on; point the generator at it via
`SCREENSHOT_BASE_URL`.

```bash
# Terminal A — compile the counter example once, then serve it on :8050
cd implementation
npm install                                  # one-time
npm run dev:example -- examples/counter --no-watch
```

```bash
# Terminal B — capture, once terminal A prints "is live at"
cd /path/to/re-frame2
node docs/scripts/generate-tutorial-screenshots.cjs
```

The script writes:

```
docs/images/xray/*.png
```

### Determinism notes

- viewport pinned to 1280×800
- Xray's first paint is gated by waiting for
  `[data-testid="rf-xray-shell"]`

### When to re-run

Re-run after any Xray UI change that would alter how the panels look,
then commit the regenerated PNGs alongside the doc page.

### Annotations (data-driven)

Annotations live in a sibling JSON file —
[`tutorial-annotation-spec.json`](tutorial-annotation-spec.json) — keyed
by scene id. The pipeline resolves each region's DOM anchor (selector or
absolute xy box) via Playwright `boundingBox`, then injects an SVG
overlay (anti-aliased boxes, drop-shadowed labels, optional arrows)
just before `page.screenshot` fires. It tears the overlay down between
scenes. There is no external image-processing dependency — Playwright
plus inline SVG is enough.

Region shape:

```jsonc
{
  // Either a CSS / [data-testid] selector resolved at runtime ...
  "selector": "[data-testid=\"rf-xray-trace-counts\"]",
  // ... or absolute xy box (no DOM anchor needed):
  "xy":       { "x": 1150, "y": 730, "w": 110, "h": 50 },
  // Optional adjustments:
  "inset":    { "x": 8, "y": 8, "w": -16, "h": 48 },  // negative w/h trims
  "padding":  6,                                       // halo around the box
  // Visual:
  "colour":   "#e53935",
  "label":    "thing to call out",
  "labelPos": "above" | "below" | "left" | "right" | "auto"
}
```

Resolved regions paint a 3-px stroke with a white halo for contrast,
a rounded-corner label background, and (optionally) an SVG arrow with
arrowhead marker.

### Adding a new Xray scene

1. Add the scene to `generate-tutorial-screenshots.cjs` `SCENES.push(...)`:
   ```js
   SCENES.push({
     id: 'xray-my-new-panel',
     out: path.join(OUT_XRAY, '12-my-new-panel.png'),
     url: '/',
     before: async (page) => {
       await page.locator('span').first().waitFor({ state: 'visible' });
       await openXray(page);
       await navXray(page, 'my-new-panel');
     },
   });
   ```
2. Add the matching annotation entry to `tutorial-annotation-spec.json`:
   ```json
   "xray-my-new-panel": [
     { "selector": "[data-testid=\"rf-xray-my-new-panel\"]",
       "inset": { "x": 8, "y": 8, "w": -16, "h": 48 },
       "colour": "#1976d2",
       "label": "thing to call out",
       "labelPos": "below" }
   ]
   ```
3. (Optional) Add a placeholder entry to
   `generate-placeholder-images.py` so the PNG renders before the live
   pipeline runs in CI.
4. Re-run the live pipeline and commit the new PNG alongside its doc page.
