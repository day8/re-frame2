#!/usr/bin/env node
/*
 * Tutorial screenshot generator.
 *
 * Drives a headless Chromium through the Causa + Story testbeds and
 * captures annotated screenshots for the Causa and Story tutorials at
 * `docs/causa/` and `docs/story/`.
 *
 * Pipeline:
 *   1. The orchestrator at examples/scripts/serve-and-run-examples-tests.cjs
 *      builds + serves every example bundle on http://127.0.0.1:8030.
 *      We assume the same orchestrator has already been run (or we invoke
 *      `npm run test:examples -- --serve-only` in a future iteration).
 *   2. For each declared scene, we navigate to the testbed URL, drive
 *      the UI into the target state, then call page.screenshot.
 *   3. ANNOTATIONS are data-driven. The companion file
 *      `tutorial-annotation-spec.json` declares one entry per scene-id
 *      with a list of regions (DOM selectors or absolute xy boxes,
 *      labels, colours, optional arrows). The pipeline resolves the
 *      DOM-anchored regions via Playwright `boundingBox`, then injects
 *      an SVG overlay (anti-aliased boxes, drop-shadowed labels) before
 *      the screenshot fires. The overlay is removed immediately after so
 *      subsequent shots are clean.
 *
 * Determinism:
 *   - Viewport pinned to 1280x800.
 *   - The Story shell's first-visit help overlay is dismissed via
 *     localStorage seeding (`re-frame.story/seen-help-v1`).
 *   - Causa's first-paint <80ms gate is satisfied by waiting for the
 *     shell's data-testid="rf-causa-shell" before shooting.
 *   - The counter-with-stories testbed seeds :count=5 deterministically.
 *
 * How to run (from repo root):
 *
 *   # one-time, builds the example bundles and serves them on :8030
 *   cd implementation && npm run test:examples:serve-only &
 *
 *   # then, from repo root:
 *   node docs/scripts/generate-tutorial-screenshots.cjs
 *
 * Outputs:
 *   docs/images/causa/*.png
 *   docs/images/story/*.png
 *
 * Reproducibility: each shot's filename is locked; re-running overwrites.
 * The output PNGs are tracked in git so the docs site stays self-contained
 * (no CI dependency on shadow-cljs / Playwright to RENDER the docs);
 * regeneration is opt-in by re-running this script.
 *
 * The Playwright driver here is the same package examples/scripts uses;
 * we resolve it out of implementation/node_modules.
 */

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_CAUSA = path.join(REPO_ROOT, 'docs', 'images', 'causa');
const OUT_STORY = path.join(REPO_ROOT, 'docs', 'images', 'story');
const ANNOTATION_SPEC = path.join(__dirname, 'tutorial-annotation-spec.json');

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const BASE_URL = process.env.SCREENSHOT_BASE_URL || 'http://127.0.0.1:8030';
const VIEWPORT = { width: 1280, height: 800 };

// ---------------------------------------------------------------------------
// Annotation spec loader — data-driven.
// ---------------------------------------------------------------------------

function loadAnnotationSpec() {
  const raw = fs.readFileSync(ANNOTATION_SPEC, 'utf8');
  const parsed = JSON.parse(raw);
  // Strip any keys that start with `$` (json schema-style comments).
  const cleaned = {};
  for (const [k, v] of Object.entries(parsed)) {
    if (!k.startsWith('$')) cleaned[k] = v;
  }
  return cleaned;
}

async function boxOf(page, selector) {
  const loc = page.locator(selector).first();
  const handle = await loc.elementHandle();
  if (!handle) return null;
  try {
    return await handle.boundingBox();
  } finally {
    await handle.dispose();
  }
}

/**
 * Resolve a region declaration from the spec into an absolute
 * `{x,y,w,h,colour,label,labelPos,arrow}` shape.
 *
 * Returns null when the selector resolves to nothing — that scene will
 * still capture, just without annotation. (No throw — we'd rather ship
 * an un-annotated frame than fail the whole pipeline on a UI rename.)
 */
async function resolveRegion(page, region) {
  let x, y, w, h;

  if (region.xy) {
    ({ x, y, w, h } = region.xy);
  } else if (region.selector) {
    const box = await boxOf(page, region.selector);
    if (!box) return null;
    x = box.x;
    y = box.y;
    w = box.width;
    h = box.height;
    if (region.groupExtendTo) {
      const tail = await boxOf(page, region.groupExtendTo);
      if (tail) {
        const xMax = Math.max(x + w, tail.x + tail.width);
        const yMax = Math.max(y + h, tail.y + tail.height);
        w = xMax - x;
        h = yMax - y;
      }
    }
    if (region.inset) {
      const ins = region.inset;
      if (ins.x != null) x += ins.x;
      if (ins.y != null) y += ins.y;
      // w/h can be: positive delta (override), or a tweak via offset.
      // Convention: ins.w > 0 means "use this as final width"; ins.w < 0
      // means "trim by abs(ins.w)". Same for ins.h.
      if (ins.w != null) w = ins.w > 0 ? ins.w : w + ins.w;
      if (ins.h != null) h = ins.h > 0 ? ins.h : h + ins.h;
    }
    if (region.padding) {
      const p = region.padding;
      x -= p;
      y -= p;
      w += 2 * p;
      h += 2 * p;
    }
  } else {
    return null;
  }

  return {
    x: Math.round(x),
    y: Math.round(y),
    w: Math.round(w),
    h: Math.round(h),
    colour: region.colour || '#e53935',
    label: region.label || null,
    labelPos: region.labelPos || 'auto',
    arrow: region.arrow || null,
  };
}

// In-page annotation overlay. SVG-based — crisp anti-aliased strokes,
// drop-shadowed labels. Single root that's torn down after each shot.
function inPageAnnotateSvg(regions, viewport) {
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const root = document.createElementNS(SVG_NS, 'svg');
  root.id = '__rf2-tutorial-annotations';
  root.setAttribute('width', String(viewport.width));
  root.setAttribute('height', String(viewport.height));
  root.setAttribute('viewBox', '0 0 ' + viewport.width + ' ' + viewport.height);
  root.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;';

  // <defs> — shared drop shadow + arrowhead.
  const defs = document.createElementNS(SVG_NS, 'defs');
  defs.innerHTML =
    '<filter id="__rf2-ann-shadow" x="-20%" y="-20%" width="140%" height="140%">' +
    '  <feDropShadow dx="0" dy="1" stdDeviation="1.2" flood-color="rgba(0,0,0,0.35)"/>' +
    '</filter>' +
    '<marker id="__rf2-ann-arrowhead" viewBox="0 0 10 10" refX="9" refY="5"' +
    '        markerWidth="7" markerHeight="7" orient="auto-start-reverse">' +
    '  <path d="M 0 0 L 10 5 L 0 10 z" fill="currentColor"/>' +
    '</marker>';
  root.appendChild(defs);

  for (const r of regions) {
    const g = document.createElementNS(SVG_NS, 'g');
    g.setAttribute('color', r.colour);

    // White halo behind the stroke for contrast against dark UI.
    const halo = document.createElementNS(SVG_NS, 'rect');
    halo.setAttribute('x', String(r.x - 1));
    halo.setAttribute('y', String(r.y - 1));
    halo.setAttribute('width', String(r.w + 2));
    halo.setAttribute('height', String(r.h + 2));
    halo.setAttribute('rx', '5');
    halo.setAttribute('fill', 'none');
    halo.setAttribute('stroke', 'rgba(255,255,255,0.95)');
    halo.setAttribute('stroke-width', '5');
    g.appendChild(halo);

    const box = document.createElementNS(SVG_NS, 'rect');
    box.setAttribute('x', String(r.x));
    box.setAttribute('y', String(r.y));
    box.setAttribute('width', String(r.w));
    box.setAttribute('height', String(r.h));
    box.setAttribute('rx', '4');
    box.setAttribute('fill', 'none');
    box.setAttribute('stroke', 'currentColor');
    box.setAttribute('stroke-width', '3');
    box.setAttribute('shape-rendering', 'geometricPrecision');
    g.appendChild(box);

    // Optional arrow.
    if (r.arrow) {
      const line = document.createElementNS(SVG_NS, 'line');
      line.setAttribute('x1', String(r.arrow.from.x));
      line.setAttribute('y1', String(r.arrow.from.y));
      line.setAttribute('x2', String(r.arrow.to.x));
      line.setAttribute('y2', String(r.arrow.to.y));
      line.setAttribute('stroke', 'currentColor');
      line.setAttribute('stroke-width', '2.5');
      line.setAttribute('marker-end', 'url(#__rf2-ann-arrowhead)');
      line.setAttribute('shape-rendering', 'geometricPrecision');
      g.appendChild(line);
    }

    // Label placement. We measure text length crudely (no DOM measure
    // for SVG without paint) — assume ~7.2px per char at 13px Inter-ish.
    if (r.label) {
      const labelText = r.label;
      const labelW = Math.max(60, labelText.length * 7.6 + 16);
      const labelH = 24;
      let lx, ly;
      const pos = r.labelPos === 'auto' ? (r.y < 40 ? 'below' : 'above') : r.labelPos;
      switch (pos) {
        case 'below':
          lx = r.x;
          ly = r.y + r.h + 6;
          break;
        case 'right':
          lx = r.x + r.w + 8;
          ly = r.y;
          break;
        case 'left':
          lx = Math.max(4, r.x - labelW - 8);
          ly = r.y;
          break;
        case 'above':
        default:
          lx = r.x;
          ly = Math.max(4, r.y - labelH - 6);
          break;
      }
      // Keep on-canvas.
      lx = Math.min(Math.max(4, lx), viewport.width - labelW - 4);
      ly = Math.min(Math.max(4, ly), viewport.height - labelH - 4);

      const labelG = document.createElementNS(SVG_NS, 'g');
      labelG.setAttribute('filter', 'url(#__rf2-ann-shadow)');

      const bg = document.createElementNS(SVG_NS, 'rect');
      bg.setAttribute('x', String(lx));
      bg.setAttribute('y', String(ly));
      bg.setAttribute('width', String(labelW));
      bg.setAttribute('height', String(labelH));
      bg.setAttribute('rx', '4');
      bg.setAttribute('fill', 'currentColor');
      labelG.appendChild(bg);

      const text = document.createElementNS(SVG_NS, 'text');
      text.setAttribute('x', String(lx + 8));
      text.setAttribute('y', String(ly + 16));
      text.setAttribute('fill', '#ffffff');
      text.setAttribute(
        'font-family',
        '-apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif',
      );
      text.setAttribute('font-size', '13');
      text.setAttribute('font-weight', '600');
      text.textContent = labelText;
      labelG.appendChild(text);

      g.appendChild(labelG);
    }

    root.appendChild(g);
  }

  document.body.appendChild(root);
}

function inPageClearAnnotations() {
  const n = document.getElementById('__rf2-tutorial-annotations');
  if (n) n.remove();
}

// ---------------------------------------------------------------------------
// Scenes — one per output screenshot. Each declares:
//   { id, out, url, before(page) }
//
// Annotation regions are looked up by `id` from the annotation spec.
// `before` drives the page into the target state.
// ---------------------------------------------------------------------------

const SCENES = [];

// ------------------------------ Causa scenes ------------------------------

async function dismissStoryHelp(page) {
  await page.evaluate(() => {
    try { localStorage.setItem('re-frame.story/seen-help-v1', '1'); } catch (_) {}
  });
}

async function openCausa(page) {
  // The preload only attaches keybindings; the shell mounts lazily on
  // the first Ctrl+Shift+C.
  await page.keyboard.press('Control+Shift+C');
  await page.locator('[data-testid="rf-causa-shell"]').waitFor({ state: 'visible', timeout: 10000 });
}

async function navCausa(page, panelId) {
  const item = page.locator(`[data-testid="rf-causa-sidebar-item-${panelId}"]`);
  await item.click();
  // Distinctive top-level testid per panel — wait for it to render.
  const canvasMap = {
    'event-detail': 'rf-causa-event-detail',
    'time-travel': 'rf-causa-time-travel',
    'app-db':      'rf-causa-app-db-diff',
    'trace':       'rf-causa-trace',
    'machines':    'rf-causa-machine-inspector',
    'schemas':     'rf-causa-schema-violation-timeline',
    'hydration':   'rf-causa-hydration-debugger',
    'copilot':     'rf-causa-copilot-panel',
  };
  const canvasTestid = canvasMap[panelId];
  if (canvasTestid) {
    await page.locator(`[data-testid="${canvasTestid}"]`)
      .waitFor({ state: 'visible', timeout: 5000 });
  }
}

SCENES.push({
  id: 'causa-floating-pill',
  out: path.join(OUT_CAUSA, '01-floating-pill.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    // Click the counter once so the trace bus has a settled epoch — the
    // pill is visible regardless, but a non-zero epoch count makes the
    // screenshot tell a story.
    await page.getByRole('button', { name: '+' }).click();
  },
});

SCENES.push({
  id: 'causa-shell-opened',
  out: path.join(OUT_CAUSA, '02-shell-opened.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await openCausa(page);
  },
});

SCENES.push({
  id: 'causa-sidebar-panels',
  out: path.join(OUT_CAUSA, '02-sidebar-panels.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await openCausa(page);
  },
});

SCENES.push({
  id: 'causa-event-detail',
  out: path.join(OUT_CAUSA, '02-event-detail.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '+' }).click();
    await openCausa(page);
    await navCausa(page, 'event-detail');
  },
});

SCENES.push({
  id: 'causa-time-travel',
  out: path.join(OUT_CAUSA, '03-time-travel.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    // Drive a few events so the time-travel scrubber has history.
    for (let i = 0; i < 5; i++) {
      await page.getByRole('button', { name: '+' }).click();
    }
    await page.getByRole('button', { name: '-' }).click();
    await openCausa(page);
    await navCausa(page, 'time-travel');
  },
});

SCENES.push({
  id: 'causa-trace',
  out: path.join(OUT_CAUSA, '04-trace.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await openCausa(page);
    await navCausa(page, 'trace');
  },
});

SCENES.push({
  id: 'causa-app-db-diff',
  out: path.join(OUT_CAUSA, '09-app-db-diff.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await openCausa(page);
    await navCausa(page, 'app-db');
  },
});

SCENES.push({
  id: 'causa-machines',
  out: path.join(OUT_CAUSA, '08-machines.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await openCausa(page);
    await navCausa(page, 'machines');
  },
});

SCENES.push({
  id: 'causa-click-to-source-dom-attribute',
  out: path.join(OUT_CAUSA, '05-dom-attribute.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    // Highlight the data-rf2-source-coord attribute by adding a visual
    // marker on the + button + a callout above it. The screenshot
    // captures the live counter (no Causa) — the message is "every
    // rendered element carries a coord."
    await page.evaluate(() => {
      const btns = Array.from(document.querySelectorAll('button'));
      const inc = btns.find((b) => b.textContent.trim() === '+');
      if (inc) inc.setAttribute('data-rf2-source-coord', 'counter.core:counter:48:5');
    });
  },
});

// --- Additional Causa scenes ----------------------------------------------

SCENES.push({
  id: 'causa-copilot-rail',
  out: path.join(OUT_CAUSA, '10-copilot-rail.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await openCausa(page);
    // The co-pilot cue is rendered in the shell's bottom rail; click it
    // to slide the rail open.
    await page.locator('[data-testid="rf-causa-copilot-cue"]').click();
    await page.locator('[data-testid="rf-causa-copilot-rail"]')
      .waitFor({ state: 'visible', timeout: 5000 });
    // Type a slash to render the slash-command popover so the rail has
    // visible content — the empty rail is less informative.
    await page.locator('[data-testid="rf-causa-copilot-input"]').fill('/explain');
  },
});

SCENES.push({
  id: 'causa-schemas-empty',
  out: path.join(OUT_CAUSA, '06-schema-timeline.png'),
  url: '/counter/',
  before: async (page) => {
    // The counter example registers no schemas — the panel renders its
    // empty-state. The screenshot still illustrates the panel chrome;
    // the prose can carry the "what it'd look like populated" weight
    // until a schemas-registered testbed lands.
    await page.locator('span').first().waitFor({ state: 'visible' });
    await openCausa(page);
    await navCausa(page, 'schemas');
  },
});

SCENES.push({
  id: 'causa-hydration-empty',
  out: path.join(OUT_CAUSA, '07-hydration.png'),
  url: '/counter/',
  before: async (page) => {
    // Counter is SPA-only — the hydration panel renders its
    // empty-state (no SSR detected). Same rationale as schemas: the
    // screenshot illustrates the panel; a hydration-mismatch testbed
    // can supersede this scene later.
    await page.locator('span').first().waitFor({ state: 'visible' });
    await openCausa(page);
    await navCausa(page, 'hydration');
  },
});

SCENES.push({
  id: 'causa-app-db-modes',
  out: path.join(OUT_CAUSA, '09-app-db-modes.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    // Multiple dispatches so the diff panel has several slices to
    // illustrate the three rendering modes prose describes.
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '+' }).click();
    await page.getByRole('button', { name: '-' }).click();
    await openCausa(page);
    await navCausa(page, 'app-db');
  },
});

// ------------------------------ Story scenes ------------------------------

SCENES.push({
  id: 'story-shell-overview',
  out: path.join(OUT_STORY, '01-shell-overview.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible', timeout: 10000 });
    // Click into the loaded variant so the canvas paints something.
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    await page.locator('[data-test="count"]').first().waitFor({ state: 'visible', timeout: 10000 });
  },
});

SCENES.push({
  id: 'story-variant-loaded',
  out: path.join(OUT_STORY, '01-variant-loaded.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    await page.locator('[data-test="count"]').first().waitFor({ state: 'visible' });
  },
});

SCENES.push({
  id: 'story-mode-tabs',
  out: path.join(OUT_STORY, '02-mode-tabs.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    await page.locator('[data-test="story-mode-tabs"]').waitFor({ state: 'visible' });
  },
});

SCENES.push({
  id: 'story-docs-mode',
  out: path.join(OUT_STORY, '02-docs-mode.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    await page.locator('[data-mode-tab="docs"]').click();
    await page.locator('[data-test="story-docs-view"]').waitFor({ state: 'visible' });
  },
});

// File is 02-test-mode.png to match its referring chapter (02-mode-tabs.md).
SCENES.push({
  id: 'story-test-mode',
  out: path.join(OUT_STORY, '02-test-mode.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    await page.locator('[data-mode-tab="test"]').click();
    await page.locator('[data-test="story-test-view"]').waitFor({ state: 'visible' });
  },
});

SCENES.push({
  id: 'story-workspace-grid',
  out: path.join(OUT_STORY, '04-workspace-grid.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    // Workspaces are sidebar entries beginning with ":Workspace."
    const ws = page.getByRole('navigation').getByText(':Workspace.counter/all-states', { exact: false }).first();
    if (await ws.count()) {
      await ws.click();
      // Wait for at least two counter cells to confirm the workspace mounted.
      await page.waitForTimeout(500);
    }
  },
});

// --- Additional Story scenes --------------------------------------------

SCENES.push({
  id: 'story-recorder-modal',
  out: path.join(OUT_STORY, '03-recorder-modal.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    // The recorder UX: click record, interact, click stop, modal opens
    // with the captured :play body. Each affordance has a stable
    // data-test; if the recorder UI hasn't shipped on the testbed yet
    // the scene falls back to capturing the canvas with a callout —
    // resolveRegion returns null for missing selectors, so the shot
    // still happens un-annotated. Re-run after wiring lands.
    const recordBtn = page.locator('[data-test="story-recorder-record"]');
    if (await recordBtn.count()) {
      await recordBtn.click();
      // Drive the counter a few times so the recorder captures events.
      const inc = page.getByRole('button', { name: '+' }).first();
      if (await inc.count()) {
        await inc.click();
        await inc.click();
      }
      const stopBtn = page.locator('[data-test="story-recorder-stop"]');
      if (await stopBtn.count()) {
        await stopBtn.click();
        await page.locator('[data-test="story-recorder-modal"]')
          .waitFor({ state: 'visible', timeout: 5000 });
      }
    }
  },
});

SCENES.push({
  id: 'story-qr-share',
  out: path.join(OUT_STORY, '05-qr-share.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const row = page.getByRole('navigation').getByText('/loaded', { exact: false }).first();
    await row.click();
    // QR-share affordance — opens an inline QR rendering of the picked
    // (variant × mode × per-cell args) tuple. Tolerant to a not-yet-
    // wired affordance per the recorder note above.
    const shareBtn = page.locator('[data-test="story-share-qr"]');
    if (await shareBtn.count()) {
      await shareBtn.click();
      await page.locator('[data-test="story-qr-share"]')
        .waitFor({ state: 'visible', timeout: 5000 });
    }
  },
});

SCENES.push({
  id: 'story-time-travel-mini',
  out: path.join(OUT_STORY, '06-time-travel-mini.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    const ws = page.getByRole('navigation').getByText(':Workspace.counter/all-states', { exact: false }).first();
    if (await ws.count()) {
      await ws.click();
      await page.waitForTimeout(500);
      // Toggle the per-cell mini-scrubbers on (default off) — the
      // workspace exposes a `show scrubbers` chip on its top rail.
      const toggle = page.locator('[data-test="story-show-mini-scrubbers"]');
      if (await toggle.count()) await toggle.click();
    }
  },
});

SCENES.push({
  id: 'story-multi-substrate',
  out: path.join(OUT_STORY, '07-multi-substrate.png'),
  url: '/counter-with-stories/#/stories',
  before: async (page) => {
    await dismissStoryHelp(page);
    await page.reload();
    await page.getByRole('navigation').waitFor({ state: 'visible' });
    // Multi-substrate workspaces declare :substrates on the parent
    // story; the canvas mounts three columns. The counter testbed may
    // not yet declare :substrates — the scene captures whatever the
    // sidebar exposes as a `:substrate-grid/*` entry.
    const grid = page.getByRole('navigation')
      .getByText(':substrate-grid', { exact: false })
      .first();
    if (await grid.count()) {
      await grid.click();
      await page.waitForTimeout(500);
    }
  },
});

// ---------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

async function probeBaseUrl() {
  // Single GET; if the server isn't up we fail fast with a helpful message.
  const http = require('http');
  return new Promise((resolve) => {
    const req = http.get(BASE_URL, (res) => {
      res.resume();
      resolve(res.statusCode != null);
    });
    req.on('error', () => resolve(false));
    req.setTimeout(2000, () => { req.destroy(); resolve(false); });
  });
}

(async () => {
  ensureDir(OUT_CAUSA);
  ensureDir(OUT_STORY);

  if (!(await probeBaseUrl())) {
    console.error(`Static server not reachable at ${BASE_URL}.`);
    console.error('Run the example orchestrator first:');
    console.error('  cd implementation && npm run test:examples');
    console.error('— or — start a long-running server with:');
    console.error('  cd implementation && SCREENSHOT_SERVE_ONLY=1 npm run test:examples');
    process.exit(2);
  }

  const annotations = loadAnnotationSpec();
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: VIEWPORT });
  const page = await context.newPage();

  const failures = [];
  let n = 0;

  for (const scene of SCENES) {
    n += 1;
    try {
      await page.goto(BASE_URL + scene.url, { waitUntil: 'load', timeout: 30000 });
      await scene.before(page);

      // Resolve the spec-declared regions for this scene.
      const regionSpecs = annotations[scene.id] || [];
      const resolved = [];
      for (const region of regionSpecs) {
        const r = await resolveRegion(page, region);
        if (r) resolved.push(r);
      }
      if (resolved.length > 0) {
        await page.evaluate(inPageAnnotateSvg, resolved, VIEWPORT);
      }

      ensureDir(path.dirname(scene.out));
      await page.screenshot({ path: scene.out, fullPage: false });

      if (resolved.length > 0) {
        await page.evaluate(inPageClearAnnotations);
      }
      const tag = resolved.length === regionSpecs.length ? 'OK ' : 'PART';
      console.log(`${tag} [${n}/${SCENES.length}]  ${scene.id} → ${path.relative(REPO_ROOT, scene.out)}` +
                  ` (annotations ${resolved.length}/${regionSpecs.length})`);
    } catch (err) {
      failures.push({ id: scene.id, err: err.message });
      console.error(`FAIL [${n}/${SCENES.length}] ${scene.id}: ${err.message}`);
    }
  }

  await context.close();
  await browser.close();

  if (failures.length > 0) {
    console.error(`\n${failures.length}/${SCENES.length} scenes failed.`);
    process.exit(1);
  }
  console.log(`\nAll ${SCENES.length} scenes captured.`);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
