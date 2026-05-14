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

### Annotations (data-driven, rf2-we013)

Annotations live in a sibling JSON file —
[`tutorial-annotation-spec.json`](tutorial-annotation-spec.json) — keyed
by scene id. The pipeline resolves each region's DOM anchor (selector or
absolute xy box) via Playwright `boundingBox`, then injects an SVG
overlay (anti-aliased boxes, drop-shadowed labels, optional arrows)
just before `page.screenshot` fires. The overlay is torn down between
scenes. No external image-processing dependency — Playwright + inline
SVG is enough.

Region shape:

```jsonc
{
  // Either a CSS / [data-testid] selector resolved at runtime ...
  "selector": "[data-testid=\"rf-causa-trace-counts\"]",
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

### Adding a new scene

1. Add the scene to `generate-tutorial-screenshots.cjs` `SCENES.push(...)`:
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
   });
   ```
2. Add the matching annotation entry to `tutorial-annotation-spec.json`:
   ```json
   "causa-my-new-panel": [
     { "selector": "[data-testid=\"rf-causa-my-new-panel\"]",
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
