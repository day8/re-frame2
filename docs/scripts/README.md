# docs/scripts/

Build-time and content-generation helpers for the docs site.

Two scripts generate the tutorial screenshots, one for
[Story](../story/index.md) and one for [Xray](../xray/index.md). Each
serves compiled testbed bundles from `implementation/out` over its own
HTTP server, drives them with Playwright, and writes annotated PNGs.
They use different testbeds and output directories, and you run them
separately. The Story generator reuses the Xray generator's annotation
functions.

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
npm ci                                        # one-time
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

This generator drives a headless Chromium through the Xray testbeds. It
captures the screenshots embedded in the [Xray](../xray/index.md)
tutorial.

Like the Story generator, it serves the compiled testbed bundles from
`implementation/out` over its own internal HTTP server. Each testbed
page carries the Xray preload and a `[data-rf-xray-host]` element, so
Xray opens inline as the page loads. The scenes use five testbeds:

| Testbed | Build |
| ------- | ----- |
| `tools/xray/testbeds/standard_epochs/` | `:examples/standard-epochs` |
| `tools/xray/testbeds/machine_epochs/` | `:examples/machine-epochs` |
| `testbeds/ssr_hydration_mismatch/` | `:testbeds/ssr-hydration-mismatch` |
| `tools/xray/testbeds/routes_epochs/` | `:examples/routes-epochs` |
| `testbeds/tenant_switcher/` | `:testbeds/tenant-switcher` |

### How to run

Compile the testbed bundles first, then run the generator from the repo
root:

```bash
cd implementation
npm ci                                        # one-time
npx shadow-cljs compile :examples/standard-epochs \
                        :examples/machine-epochs \
                        :testbeds/ssr-hydration-mismatch \
                        :examples/routes-epochs \
                        :testbeds/tenant-switcher
cd ..
node docs/scripts/generate-tutorial-screenshots.cjs
```

Name one or more output files to capture only those shots:

```bash
node docs/scripts/generate-tutorial-screenshots.cjs xray-tutorial-trace.png
```

It writes:

```
docs/images/xray/xray-tutorial-*.png
```

A scene that fails, including one whose annotation region does not
resolve, is reported and the run exits non-zero.

### Determinism notes

- viewport pinned to 1440×900
- each scene runs in a fresh browser context, so no Xray settings or
  panel width carry over from the previous scene
- the Xray host is widened to 900px, to keep the current panel and its controls readable
- each scene waits for the Xray shell, for the event rows its steps
  dispatch, and for the selected tab's panel before shooting
- a scene's `clipHeight` trims empty panel space below the content, so
  the committed PNG stays small

### When to re-run

Re-run after any Xray UI change that would alter how the panels look,
then commit the regenerated PNGs alongside the doc page.

### Annotations (data-driven)

Annotations live in a sibling JSON file —
[`tutorial-annotation-spec.json`](tutorial-annotation-spec.json) — keyed
by scene id. The pipeline resolves each region's DOM anchor (selector or
absolute xy box) via Playwright `boundingBox`, then injects an SVG
overlay (anti-aliased boxes, drop-shadowed labels, optional arrows)
just before `page.screenshot` fires. There is no external
image-processing dependency — Playwright plus inline SVG is enough.

Region shape:

```jsonc
{
  // Either a CSS / [data-testid] selector resolved at runtime ...
  "selector": "[data-testid=\"rf-xray-tab-trace\"]",
  // ... or absolute xy box (no DOM anchor needed):
  "xy":       { "x": 1150, "y": 730, "w": 110, "h": 50 },
  // Optional adjustments:
  "groupExtendTo": "[data-testid=\"...\"]",            // grow to cover a second element
  "inset":    { "x": 8, "y": 8, "w": -16, "h": 48 },  // negative w/h trims
  "padding":  6,                                       // halo around the box
  // Visual:
  "colour":   "#e53935",
  "label":    "thing to call out",
  "labelPos": "above" | "below" | "left" | "right" | "auto",
  "labelAt":  { "x": 40, "y": 60 }                     // overrides labelPos
}
```

Resolved regions paint a 3-px stroke with a white halo for contrast,
a rounded-corner label background, and (optionally) an SVG arrow with
arrowhead marker.

Each label starts with the number the tutorial's prose refers to, such as
`"1  Current route"`, and the image's alt text names the numbers too, so
a reader who cannot see the image still gets the callouts. Change a label
and the prose together.

### Adding a new Xray scene

1. Add the scene to the `SCENES` vector in
   `generate-tutorial-screenshots.cjs`. The output file is `<id>.png`,
   `app` names the testbed, `clip: 'xray'` captures only the Xray host,
   and `before` drives the page after Xray is open:
   ```js
   {
     id: 'xray-tutorial-frames',
     app: '/standard-epochs',
     clip: 'xray',
     before: async (page) => {
       await runSteps(page, SE, [SE_STEPS.increment]);
       await focusRow(page, ':standard-epochs/increment');
       await selectTab(page, 'module-view');
     },
   },
   ```
   A scene on a testbed the generator does not serve yet also needs an
   entry in its `APPS` map.
2. If the shot needs callouts, add an entry to
   `tutorial-annotation-spec.json` under the scene id:
   ```json
   "xray-tutorial-frames": [
     { "selector": "[data-testid=\"rf-xray-detail-panel-module-view\"]",
       "inset": { "x": 8, "y": 8, "w": -16, "h": 48 },
       "colour": "#1976d2",
       "label": "1  The frame's registrations",
       "labelPos": "below" }
   ]
   ```
3. Re-run the generator and commit the new PNG alongside its doc page.

The Xray scenes also capture filters, settings and value dependencies. The
Story scenes include editing Controls and a recorder export. Each annotation
is resolved against the actual UI before capture. Missing regions fail the
generator, so update selectors and captions together when the UI changes.
