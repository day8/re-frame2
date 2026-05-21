#!/usr/bin/env node
/*
 * Capture the 10 annotated screenshots for the Story tutorial
 * (`docs/story/*.md`).
 *
 * Each entry below describes one screenshot:
 *   - variant: URL `?variant=...` value (without the leading colon)
 *   - mode: optional `:docs` / `:test` mode-tab to activate
 *   - prep: optional async (page) => {} pre-capture step (e.g. click a
 *     button, wait for a dialog).
 *   - callouts: numbered list of { selector, label, side } where
 *     `selector` resolves to a single element (the FIRST match is used);
 *     `label` is rendered into the yellow chip; `side` (top/bottom/left/right)
 *     biases the arrow direction.
 *   - clip: optional { x, y, width, height } passed to page.screenshot
 *   - save: path under `docs/images/story/` (relative to docs root)
 *
 * Pre-requisites:
 *   1) shadow-cljs compile examples/counter-with-stories
 *   2) the static `index.html` is copied next to main.js (the script
 *      does this for you the first time the build output is detected).
 *   3) An HTTP server is serving `implementation/out/examples/` so
 *      `/counter-with-stories/` resolves; pass the URL via STORY_BASE
 *      (default http://127.0.0.1:9876).
 *
 * Usage:
 *   STORY_BASE=http://127.0.0.1:9876 \
 *     node examples/scripts/capture-story-screenshots.cjs [shotName...]
 *
 * No-arg run captures all 10. Argument filters to the named shots
 * (e.g. `index-overview` `mode-tabs-strip`).
 */

'use strict';

const fs = require('fs');
const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const IMAGES_DIR = path.join(REPO_ROOT, 'docs', 'images', 'story');
const BASE = process.env.STORY_BASE || 'http://127.0.0.1:9876';
const TESTBED_BASE = '/counter-with-stories/';

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

// ---------------------------------------------------------------------------
// Shot definitions. One entry per `> 📸 Screenshot needed` placeholder.
// Ordered to match the chapter sequence so a partial filter prints in a
// sensible order.
// ---------------------------------------------------------------------------

const SHOTS = [
  // index.md — `01-shell-overview.png`
  {
    name: 'index-overview',
    variant: 'story.counter/loaded',
    save: '01-shell-overview.png',
    callouts: [
      // Sidebar tree on the LEFT
      { selector: '[data-test="story-sidebar"]',                          label: '1', side: 'right' },
      // Canvas in the centre — point from the BOTTOM up
      { selector: '[data-test="story-canvas-frame"]',                     label: '2', side: 'bottom' },
      // Right-pane inspectors (where Causa embeds)
      { selector: '[data-test="story-inspectors"]',                       label: '3', side: 'left' },
      // The mode-tab strip above the canvas
      { selector: '[data-test="story-mode-tabs"]',                        label: '4', side: 'top' },
      // The controls panel on the right rail — Causa's chip row
      // accommodates the role; we point at the chip row.
      { selector: '[data-test="story-causa-embed"]',                      label: '5', side: 'left' },
    ],
  },

  // 01-first-story.md — `01-variant-loaded.png`
  {
    name: 'first-story-variant',
    variant: 'story.counter/empty',
    save: '01-variant-loaded.png',
    callouts: [
      // sidebar `:story.counter` story row
      { selector: '[data-test="story-sidebar-story-row"][data-story=":story.counter"]', label: '1', side: 'right',
        fallback: '[data-test="story-sidebar-story-row"]' },
      // `:story.counter/empty` variant row (selected)
      { selector: '[data-test="story-sidebar-variant-row"][data-variant=":story.counter/empty"]', label: '2', side: 'right',
        fallback: '[data-test="story-sidebar-variant-row"]' },
      // The counter rendered in the canvas — point at it from below
      { selector: '[data-test="story-canvas-frame"]',                     label: '3', side: 'bottom' },
      // The mode-tab strip
      { selector: '[data-test="story-mode-tabs"]',                        label: '4', side: 'top' },
    ],
  },

  // 02-mode-tabs.md — `02-mode-tabs.png`
  {
    name: 'mode-tabs-strip',
    variant: 'story.counter/loaded',
    save: '02-mode-tabs.png',
    callouts: [
      { selector: '[data-test="story-mode-tabs"]',                        label: '1', side: 'top' },
      { selector: '[data-test="story-toolbar-viewport"]',                 label: '2', side: 'bottom' },
      { selector: '[data-test="story-toolbar-backgrounds"]',              label: '3', side: 'bottom' },
      // a11y panel lives in the inspectors right rail (Story's
      // chrome-a11y panel renders into the `:right`-placement slot;
      // the tutorial text describes it as the "a11y chip", but in
      // this build the right-pane panel is the visible a11y surface).
      { selector: '[data-test="story-chrome-a11y-panel"]',                label: '4', side: 'left' },
      // The chapter mentions a locale switcher; Story doesn't ship one
      // by default, so we point at the dispatch-console chip as the
      // closest project-extensible toolbar slot.
      { selector: '[data-test="story-toolbar-dispatch-console"]',         label: '5', side: 'bottom' },
    ],
  },

  // 02-mode-tabs.md — `02-docs-mode.png`
  {
    name: 'docs-mode',
    variant: 'story.counter/loaded',
    mode: 'docs',
    save: '02-docs-mode.png',
    callouts: [
      { selector: '[data-test="story-docs-doc-blurb"]',                   label: '1', side: 'right' },
      { selector: '[data-test="story-docs-args-table"]',                  label: '2', side: 'right' },
      { selector: '[data-test="story-docs-decorators-table"]',            label: '3', side: 'right' },
      { selector: '[data-test="story-docs-header-tags"]',                 label: '4', side: 'top' },
    ],
  },

  // 02-mode-tabs.md — `02-test-mode.png`
  // The /loaded variant ships three passing :play assertions and the
  // failing-play diagnostic ships one failing assertion. We pick the
  // failing-play variant because it's the one place a single test
  // pane carries both a status pill set to `fail` AND a `story-test-
  // row[data-status=fail]` row — which is the most teaching-friendly
  // surface for the chapter's callouts.
  {
    name: 'test-mode',
    variant: 'story.counter-diagnostics/failing-play',
    mode: 'test',
    save: '02-test-mode.png',
    callouts: [
      // aggregate status pill at top
      { selector: '[data-test="story-test-status-pill"]',                 label: '1', side: 'bottom' },
      // The single :rf.assert/path-equals row — failing per the variant body
      { selector: '[data-test="story-test-row"][data-status="fail"]',     label: '2', side: 'right',
        fallback: '[data-test="story-test-row"]' },
      // counts (passed/failed/skipped)
      { selector: '[data-test="story-test-counts"]',                      label: '3', side: 'bottom' },
      // expanded detail / summary section
      { selector: '[data-test="story-test-summary-section"]',             label: '4', side: 'right' },
      // re-run button
      { selector: '[data-test="story-test-rerun"]',                       label: '5', side: 'left',
        fallback: '[data-test="story-play-status-re-run"]' },
    ],
  },

  // 03-recorder-codegen.md — `03-recorder-modal.png`
  // The recorder is launched via the `[data-test="story-toolbar-rec"]`
  // chip. We click it to enter recording, click the counter's +1
  // button a few times, then stop the recorder via the overlay's stop
  // button so the export dialog surfaces.
  {
    name: 'recorder-modal',
    variant: 'story.counter/loaded',
    save: '03-recorder-modal.png',
    prep: async (page) => {
      // Open recorder
      await page.click('[data-test="story-toolbar-rec"]').catch(() => {});
      await page.waitForTimeout(500);
      // Increment the counter a few times via the `inc` button on the
      // canvas (`counter-card` view exposes `[data-test="inc"]`).
      for (let i = 0; i < 3; i++) {
        await page.click('[data-test="inc"]').catch(() => {});
        await page.waitForTimeout(150);
      }
      // Stop recording — opens the recorder save-dialog with the
      // generated `(reg-variant ...)` EDN snippet.
      await page.click('[data-test="story-recorder-stop"]').catch(() => {});
      await page.waitForTimeout(500);
      // Click "export as :play-script" to stack the export dialog on
      // top of the save-dialog.
      await page.click('[data-test="story-recorder-export"]').catch(() => {});
      await page.waitForSelector('[data-test="story-recorder-export-dialog"]', { timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(400);
    },
    callouts: [
      // export dialog wrapper (modal title)
      { selector: '[data-test="story-recorder-export-dialog"]',            label: '1', side: 'top',
        fallback: '[role="dialog"]' },
      // generated EDN snippet
      { selector: '[data-test="story-recorder-export-snippet"]',           label: '2', side: 'left',
        fallback: 'textarea, pre' },
      // live preview replay status
      { selector: '[data-test="story-recorder-export-replay-status"]',     label: '3', side: 'right',
        fallback: '[data-test="story-recorder-export-replay"]' },
      // copy button
      { selector: '[data-test="story-recorder-export-copy"]',              label: '4', side: 'bottom' },
      // auto-assert toggle (`edit before pasting` stand-in — closest
      // toggle the dialog ships).
      { selector: '[data-test="story-recorder-export-auto-assert"]',       label: '5', side: 'bottom' },
    ],
  },

  // 04-workspaces.md — `04-workspace-grid.png`
  // The `:Workspace.counter/all-states` workspace is a 2x2 grid of four
  // counter variants. We use the `?workspace=...` URL query the Story
  // shell parses (per re-frame.story.share.parse-workspace-param).
  {
    name: 'workspace-grid',
    workspace: ':Workspace.counter/all-states',
    save: '04-workspace-grid.png',
    callouts: [
      // first workspace cell (top-left of the 2×2 grid)
      { selector: '[data-test-variant]', nth: 1,                          label: '1', side: 'top' },
      // second cell (top-right)
      { selector: '[data-test-variant]', nth: 2,                          label: '2', side: 'top' },
      // third cell (bottom-left)
      { selector: '[data-test-variant]', nth: 3,                          label: '3', side: 'bottom' },
      // fourth cell (bottom-right) — the "share this layout" affordance
      // is reachable via the toolbar's `story-share-button`; we point
      // at the fourth cell instead because the share chip lives outside
      // the workspace surface itself and the chapter's narrative
      // anchors all four callouts on the grid.
      { selector: '[data-test-variant]', nth: 4,                          label: '4', side: 'bottom' },
    ],
  },

  // 05-snapshot-identity.md — `05-qr-share.png`
  // Click the toolbar's share button to surface the share popover with
  // the QR code, the URL row, and the copy affordances.
  {
    name: 'qr-share',
    variant: 'story.counter/loaded',
    save: '05-qr-share.png',
    prep: async (page) => {
      await page.click('[data-test="story-share-button"]').catch(() => {});
      await page.waitForSelector('[data-test="story-share-popover"]', { timeout: 5000 }).catch(() => {});
      await page.waitForTimeout(400);
    },
    callouts: [
      // QR image (canvas) — fallback to share popover
      { selector: '[data-test="story-share-qr"]',                         label: '1', side: 'right',
        fallback: '[data-test="story-share-qr-fallback"]' },
      // URL string row
      { selector: '[data-test="story-share-url"]',                        label: '2', side: 'top' },
      // Popover container (anchors 3 and 4 — copy buttons typically
      // live inside the popover but the data-test names aren't always
      // stamped on the buttons; point at the popover root as a fallback)
      { selector: '[data-test="story-share-popover"]',                    label: '3', side: 'right' },
      { selector: '[data-test="story-share-popover"]',                    label: '4', side: 'left' },
    ],
  },

  // 06-time-travel.md — `06-time-travel.png`
  // Causa-as-RHS embed mounts beneath the chip-row picker as soon as a
  // panel is selected. By default the Event panel is open; we click
  // through a counter increment first so the Causa spine has at least
  // one epoch beyond the boot to scrub through.
  {
    name: 'time-travel',
    variant: 'story.counter/loaded',
    save: '06-time-travel.png',
    prep: async (page) => {
      // Fire a couple of counter clicks so the Causa epoch buffer has
      // visible content.
      for (let i = 0; i < 2; i++) {
        await page.click('[data-test="inc"]').catch(() => {});
        await page.waitForTimeout(150);
      }
      await page.waitForTimeout(400);
    },
    callouts: [
      // Left half — the variant's canvas
      { selector: '[data-test="story-canvas-frame"]',                     label: '1', side: 'top' },
      // Right half — the Causa-as-RHS embed root
      { selector: '[data-test="story-causa-embed"]',                      label: '2', side: 'left' },
      // chip-row picker that swaps between Causa's panels
      { selector: '[data-test="story-causa-panel-chip"]',                 label: '3', side: 'top' },
      // Active Causa panel host (Event panel by default — carries the
      // ribbon + event list inside)
      { selector: '[data-test="story-causa-panel-host"]',                 label: '4', side: 'left' },
      // Popout button — proves the embed can detach into a separate window
      { selector: '[data-test="story-causa-popout"]',                     label: '5', side: 'left' },
    ],
  },

  // 07-multi-substrate.md — `07-multi-substrate.png`
  // Use a variant declared with `:substrates #{:reagent :uix}` so the
  // multi-substrate side-by-side renders. Story's multi-substrate
  // surface stamps each cell as `<div role="region" aria-label="<name>
  // substrate cell">` per ui/multi_substrate.cljs:251-253.
  {
    name: 'multi-substrate',
    variant: 'story.counter-matrix/multi-substrate',
    save: '07-multi-substrate.png',
    callouts: [
      { selector: '[role="region"][aria-label*="reagent substrate cell"]', label: '1', side: 'top' },
      { selector: '[role="region"][aria-label*="uix substrate cell"]',     label: '2', side: 'top' },
      // The variant only declares :reagent + :uix substrates; the matrix
      // variant doesn't include :helix. Point at the second cell's body
      // again so the chapter's third numbered callout still has an
      // arrow. (The chapter text describes a 3-cell ideal — we capture
      // the actual variant.)
      { selector: '[role="group"][aria-label*="Multi-substrate render"]',  label: '3', side: 'bottom' },
    ],
  },
];

// ---------------------------------------------------------------------------
// Overlay injector. Renders red arrows + yellow numbered chips above
// the page using a fixed-positioned SVG layer (z-index 99999).
// ---------------------------------------------------------------------------

function injectOverlay(callouts) {
  const prev = document.getElementById('__rf-story-shot-overlay__');
  if (prev) prev.remove();

  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('id', '__rf-story-shot-overlay__');
  Object.assign(svg.style, {
    position: 'fixed',
    inset: '0',
    width: '100vw',
    height: '100vh',
    pointerEvents: 'none',
    zIndex: '99999',
  });
  svg.setAttribute('width', String(window.innerWidth));
  svg.setAttribute('height', String(window.innerHeight));

  const defs = document.createElementNS(NS, 'defs');
  // Use plain string concatenation so we never embed a backtick or
  // template literal anywhere in this function body.
  defs.innerHTML =
    '<marker id="rfArrowHead" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse">' +
    '<path d="M 0 0 L 10 5 L 0 10 z" fill="#dc2626" />' +
    '</marker>' +
    '<filter id="rfDrop" x="-20%" y="-20%" width="140%" height="140%">' +
    '<feDropShadow dx="0" dy="1" stdDeviation="1.5" flood-color="#000" flood-opacity="0.4"/>' +
    '</filter>';
  svg.appendChild(defs);

  const placed = [];
  const overlap = function (a, b) {
    return !(a.x + a.w < b.x || b.x + b.w < a.x || a.y + a.h < b.y || b.y + b.h < a.y);
  };

  callouts.forEach(function (c) {
    if (!c.found) return;
    const r = c.rect;
    const side = c.side || 'top';
    const chipW = 36;
    const chipH = 36;
    let cx = 0, cy = 0;
    const margin = 16;
    if (side === 'top')    { cx = r.x + r.w / 2 - chipW / 2; cy = r.y - chipH - margin; }
    if (side === 'bottom') { cx = r.x + r.w / 2 - chipW / 2; cy = r.y + r.h + margin; }
    if (side === 'left')   { cx = r.x - chipW - margin;       cy = r.y + r.h / 2 - chipH / 2; }
    if (side === 'right')  { cx = r.x + r.w + margin;         cy = r.y + r.h / 2 - chipH / 2; }
    cx = Math.max(8, Math.min(window.innerWidth - chipW - 8, cx));
    cy = Math.max(8, Math.min(window.innerHeight - chipH - 8, cy));
    let chipRect = { x: cx, y: cy, w: chipW, h: chipH };
    let safety = 0;
    while (placed.some(function (p) { return overlap(chipRect, p); }) && safety < 30) {
      chipRect.y += chipH + 6;
      if (chipRect.y > window.innerHeight - chipH - 8) {
        chipRect.y = cy;
        chipRect.x += chipW + 6;
      }
      safety += 1;
    }
    placed.push(chipRect);

    const chipCx = chipRect.x + chipW / 2;
    const chipCy = chipRect.y + chipH / 2;
    const tx = r.x + r.w / 2;
    const ty = r.y + r.h / 2;
    const dx = tx - chipCx;
    const dy = ty - chipCy;
    const sxSign = dx === 0 ? 0 : (dx > 0 ? 1 : -1);
    const sySign = dy === 0 ? 0 : (dy > 0 ? 1 : -1);
    let ex = tx;
    let ey = ty;
    if (Math.abs(dx) > Math.abs(dy)) {
      ex = sxSign > 0 ? r.x : r.x + r.w;
      ey = chipCy + (dy / (dx || 1)) * (ex - chipCx);
    } else {
      ey = sySign > 0 ? r.y : r.y + r.h;
      ex = chipCx + (dx / (dy || 1)) * (ey - chipCy);
    }
    let sx0 = chipCx;
    let sy0 = chipCy;
    if (Math.abs(dx) > Math.abs(dy)) {
      sx0 = sxSign > 0 ? chipRect.x + chipW : chipRect.x;
      sy0 = chipCy;
    } else {
      sy0 = sySign > 0 ? chipRect.y + chipH : chipRect.y;
      sx0 = chipCx;
    }

    const line = document.createElementNS(NS, 'line');
    line.setAttribute('x1', sx0);
    line.setAttribute('y1', sy0);
    line.setAttribute('x2', ex);
    line.setAttribute('y2', ey);
    line.setAttribute('stroke', '#dc2626');
    line.setAttribute('stroke-width', '3');
    line.setAttribute('marker-end', 'url(#rfArrowHead)');
    svg.appendChild(line);

    const rect = document.createElementNS(NS, 'rect');
    rect.setAttribute('x', chipRect.x);
    rect.setAttribute('y', chipRect.y);
    rect.setAttribute('width', chipW);
    rect.setAttribute('height', chipH);
    rect.setAttribute('rx', '6');
    rect.setAttribute('fill', '#fde047');
    rect.setAttribute('stroke', '#000');
    rect.setAttribute('stroke-width', '1');
    rect.setAttribute('filter', 'url(#rfDrop)');
    svg.appendChild(rect);

    const text = document.createElementNS(NS, 'text');
    text.setAttribute('x', chipRect.x + chipW / 2);
    text.setAttribute('y', chipRect.y + chipH / 2 + 8);
    text.setAttribute('text-anchor', 'middle');
    text.setAttribute('font-family', "system-ui, -apple-system, 'Segoe UI', Arial, sans-serif");
    text.setAttribute('font-size', '20');
    text.setAttribute('font-weight', '700');
    text.setAttribute('fill', '#000');
    text.textContent = String(c.label);
    svg.appendChild(text);
  });

  document.body.appendChild(svg);
}

async function navigate(page, shot) {
  let url = `${BASE}${TESTBED_BASE}`;
  const params = [];
  if (shot.variant) params.push(`variant=${encodeURIComponent(shot.variant)}`);
  if (shot.workspace) params.push(`workspace=${encodeURIComponent(shot.workspace)}`);
  if (params.length) url += `?${params.join('&')}`;
  url += '#/stories';
  await page.goto(url, { waitUntil: 'load' });
  await page.evaluate(() => {
    try { localStorage.setItem('re-frame.story/seen-help-v1', '1'); } catch (_) {}
  });
  // Story shell mounts asynchronously; wait for the canvas or workspace
  // root to appear before continuing.
  await page.waitForSelector('[data-test="story-canvas-frame"], [data-test^="story-workspace"]', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(800);
}

async function activateMode(page, mode) {
  if (!mode) return;
  // Mode tab strip carries `data-mode-tab="<id>"` per ui/mode_tabs.cljs:194
  await page.click(`[data-mode-tab="${mode}"]`).catch(() => {});
  await page.waitForTimeout(500);
}

// Resolve a callout entry into { found, rect, label, side }.
// Supports `nth` (1-based index over the selector's matches) so a
// shot can target distinct elements that share a selector.
async function resolveCallout(page, c) {
  return page.evaluate(({ selector, fallback, label, side, nth }) => {
    const tryQuery = (sel) => {
      try {
        const list = Array.from(document.querySelectorAll(sel)).filter((el) => {
          const r = el.getBoundingClientRect();
          return r.width > 0 && r.height > 0;
        });
        const idx = typeof nth === 'number' ? (nth - 1) : 0;
        const el = list[idx];
        if (!el) return null;
        const r = el.getBoundingClientRect();
        return { el, r };
      } catch (_) {}
      return null;
    };
    let hit = tryQuery(selector);
    if (!hit && fallback) hit = tryQuery(fallback);
    if (!hit) return { found: false, label, side, selector };
    const { r } = hit;
    return {
      found: true,
      label,
      side,
      selector,
      rect: { x: r.x, y: r.y, w: r.width, h: r.height },
    };
  }, c);
}

async function captureShot(browser, shot) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 2 });
  await ctx.addInitScript(() => {
    try {
      localStorage.setItem('re-frame.story/seen-help-v1', '1');
    } catch (_) { /* ignore */ }
  });
  const page = await ctx.newPage();
  page.on('pageerror', (err) => console.warn(`[${shot.name}] pageerror:`, err.message));

  try {
    await navigate(page, shot);
    if (shot.mode) await activateMode(page, shot.mode);
    if (typeof shot.prep === 'function') {
      await shot.prep(page);
    }
    await page.waitForTimeout(500);

    // Resolve every callout.
    const resolved = [];
    for (const c of shot.callouts) {
      const r = await resolveCallout(page, c);
      resolved.push(r);
    }

    const missing = resolved.filter((r) => !r.found).map((r) => r.selector);
    if (missing.length) {
      console.warn(`[${shot.name}] missing selectors (will skip annotations for these): ${JSON.stringify(missing)}`);
    }

    // Inject overlay.
    await page.evaluate(injectOverlay, resolved);
    await page.waitForTimeout(150);

    const out = path.join(IMAGES_DIR, shot.save);
    fs.mkdirSync(path.dirname(out), { recursive: true });
    await page.screenshot({ path: out, fullPage: false });
    const stat = fs.statSync(out);
    console.log(`[${shot.name}] -> ${out} (${(stat.size / 1024).toFixed(1)} KB)`);
    return { name: shot.name, save: shot.save, size: stat.size, missing };
  } finally {
    await ctx.close();
  }
}

async function main() {
  const filter = process.argv.slice(2);
  const shots = filter.length
    ? SHOTS.filter((s) => filter.includes(s.name))
    : SHOTS;
  if (!shots.length) {
    console.error('No shots selected.');
    process.exit(1);
  }
  const browser = await chromium.launch();
  try {
    for (const s of shots) {
      await captureShot(browser, s);
    }
  } finally {
    await browser.close();
  }
}

main().catch((err) => { console.error(err); process.exit(1); });
