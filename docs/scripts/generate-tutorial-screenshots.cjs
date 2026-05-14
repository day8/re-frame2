#!/usr/bin/env node
/*
 * Tutorial screenshot generator (rf2-6e53j).
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
 *   3. Optional ANNOTATIONS are applied via Playwright's
 *      page.evaluate — we inject a tiny CSS+DOM overlay (boxes, arrows,
 *      labels) before the screenshot fires. The overlay is removed
 *      immediately after so subsequent shots are clean.
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

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const BASE_URL = process.env.SCREENSHOT_BASE_URL || 'http://127.0.0.1:8030';
const VIEWPORT = { width: 1280, height: 800 };

// ---------------------------------------------------------------------------
// Annotation helpers — injected into the page before each screenshot.
// ---------------------------------------------------------------------------
//
// We render a small overlay with absolutely-positioned <div>s for labelled
// rectangles + arrows. The overlay is added to <body> and torn down after
// the shot. We pass a list of {x,y,w,h,label,colour} regions.

// Annotation function — passed as a Playwright `pageFunction` so it
// executes inside the page context. We don't use addInitScript because
// the SPA's own JS may rewrite window before our handler attaches.
function inPageAnnotate(regions) {
  const root = document.createElement('div');
  root.id = '__rf2-tutorial-annotations';
  root.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;';
  document.body.appendChild(root);
  for (const r of regions) {
    const box = document.createElement('div');
    const colour = r.colour || '#e53935';
    box.style.cssText = [
      'position:absolute',
      'left:' + r.x + 'px',
      'top:' + r.y + 'px',
      'width:' + r.w + 'px',
      'height:' + r.h + 'px',
      'border:3px solid ' + colour,
      'border-radius:4px',
      'box-shadow:0 0 0 2px rgba(255,255,255,0.85)',
    ].join(';');
    root.appendChild(box);
    if (r.label) {
      const lbl = document.createElement('div');
      const placeBelow = r.y < 40;
      lbl.textContent = r.label;
      lbl.style.cssText = [
        'position:absolute',
        'left:' + r.x + 'px',
        (placeBelow ? 'top:' : 'top:') + (placeBelow ? (r.y + r.h + 6) : (r.y - 28)) + 'px',
        'background:' + colour,
        'color:#fff',
        'font:600 13px/1.2 -apple-system,BlinkMacSystemFont,Segoe UI,Helvetica,Arial,sans-serif',
        'padding:4px 8px',
        'border-radius:3px',
        'white-space:nowrap',
      ].join(';');
      root.appendChild(lbl);
    }
  }
}

function inPageClearAnnotations() {
  const n = document.getElementById('__rf2-tutorial-annotations');
  if (n) n.remove();
}

// ---------------------------------------------------------------------------
// Scenes — one per output screenshot. Each declares:
//   { id, out, url, before(page), regions(page), label }
//
// `before` drives the page into the target state. `regions` returns an
// array of annotation rectangles (or [] for an unannotated capture).
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
    'causality':   'rf-causa-causality-graph',
    'trace':       'rf-causa-trace',
    'machines':    'rf-causa-machine-inspector',
  };
  const canvasTestid = canvasMap[panelId];
  if (canvasTestid) {
    await page.locator(`[data-testid="${canvasTestid}"]`)
      .waitFor({ state: 'visible', timeout: 5000 });
  }
}

async function boxOf(page, locator) {
  const handle = await locator.elementHandle();
  if (!handle) return null;
  const b = await handle.boundingBox();
  await handle.dispose();
  return b;
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
  regions: async (page) => {
    // The floating launcher pill is anchored bottom-right by Causa's
    // mount css. We highlight roughly that region of the viewport.
    return [{
      x: VIEWPORT.width - 130,
      y: VIEWPORT.height - 70,
      w: 110,
      h: 50,
      colour: '#1976d2',
      label: 'Ctrl+Shift+C',
    }];
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
  regions: async (page) => {
    const shell = page.locator('[data-testid="rf-causa-shell"]');
    const b = await boxOf(page, shell);
    return b ? [{ x: b.x, y: b.y, w: b.width, h: 28, colour: '#43a047', label: 'Causa shell' }] : [];
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
  regions: async (page) => {
    // Highlight the sidebar list — first sidebar item gives us the
    // x-coord; we extend down to capture the full panel list.
    const first = page.locator('[data-testid="rf-causa-sidebar-item-event-detail"]');
    const b = await boxOf(page, first);
    if (!b) return [];
    return [{
      x: b.x - 4,
      y: b.y - 4,
      w: b.width + 8,
      h: 16 * Math.max(1, 30),
      colour: '#fb8c00',
      label: '16 panels',
    }];
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
  regions: async (page) => {
    const ed = page.locator('[data-testid="rf-causa-event-detail"]');
    const b = await boxOf(page, ed);
    return b ? [{ x: b.x + 4, y: b.y + 4, w: b.width - 8, h: 40, colour: '#43a047', label: 'Event detail (hero)' }] : [];
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
  regions: async () => [],
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
  regions: async (page) => {
    const counts = page.locator('[data-testid="rf-causa-trace-counts"]');
    const b = await boxOf(page, counts);
    return b ? [{ x: b.x - 4, y: b.y - 4, w: b.width + 8, h: b.height + 8, colour: '#1976d2', label: 'live event count' }] : [];
  },
});

SCENES.push({
  id: 'causa-app-db-diff',
  out: path.join(OUT_CAUSA, '10-app-db-diff.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await openCausa(page);
    await navCausa(page, 'app-db');
  },
  regions: async () => [],
});

SCENES.push({
  id: 'causa-causality',
  out: path.join(OUT_CAUSA, '02-causality.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await page.getByRole('button', { name: '+' }).click();
    await openCausa(page);
    await navCausa(page, 'causality');
  },
  regions: async () => [],
});

SCENES.push({
  id: 'causa-machines',
  out: path.join(OUT_CAUSA, '09-machines.png'),
  url: '/counter/',
  before: async (page) => {
    await page.locator('span').first().waitFor({ state: 'visible' });
    await openCausa(page);
    await navCausa(page, 'machines');
  },
  regions: async () => [],
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
  regions: async (page) => {
    const inc = page.getByRole('button', { name: '+' }).first();
    const b = await boxOf(page, inc);
    return b ? [{
      x: b.x - 4,
      y: b.y - 4,
      w: b.width + 8,
      h: b.height + 8,
      colour: '#e53935',
      label: 'data-rf2-source-coord',
    }] : [];
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
  regions: async (page) => {
    const sidebar = page.getByRole('navigation');
    const main = page.getByRole('main');
    const aside = page.getByRole('complementary');
    const out = [];
    for (const [loc, label, colour] of [
      [sidebar, 'sidebar', '#1976d2'],
      [main, 'canvas', '#43a047'],
      [aside, 'inspectors', '#fb8c00'],
    ]) {
      const b = await boxOf(page, loc);
      if (b) out.push({ x: b.x, y: b.y + 4, w: b.width, h: 22, colour, label });
    }
    return out;
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
  regions: async () => [],
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
  regions: async (page) => {
    const strip = page.locator('[data-test="story-mode-tabs"]');
    const b = await boxOf(page, strip);
    return b ? [{ x: b.x - 4, y: b.y - 4, w: b.width + 8, h: b.height + 8, colour: '#e53935', label: 'mode tabs' }] : [];
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
  regions: async () => [],
});

SCENES.push({
  id: 'story-test-mode',
  out: path.join(OUT_STORY, '03-test-mode.png'),
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
  regions: async () => [],
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
  regions: async () => [],
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
      const regions = await scene.regions(page);
      if (regions && regions.length > 0) {
        await page.evaluate(inPageAnnotate, regions);
      }
      ensureDir(path.dirname(scene.out));
      await page.screenshot({ path: scene.out, fullPage: false });
      if (regions && regions.length > 0) {
        await page.evaluate(inPageClearAnnotations);
      }
      console.log(`OK  [${n}/${SCENES.length}]  ${scene.id} → ${path.relative(REPO_ROOT, scene.out)}`);
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
