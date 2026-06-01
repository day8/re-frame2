#!/usr/bin/env node
/*
 * Capture annotated screenshots for the Story tutorial
 * (`docs/story/*.md`).
 *
 * NOTE: the legacy non-prefixed shots were retired with their orphaned
 * PNGs (rf2-azqaq); the live tutorial embeds the hand-captured s00–s13
 * set. SHOTS is currently empty — the capture machinery below is kept
 * for a refreshed s-prefixed pipeline. A no-arg run is therefore a
 * no-op ("No shots selected.") until shots are re-declared.
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
 * Once SHOTS is re-populated: a no-arg run captures every declared shot;
 * an argument filters to the named shots (e.g. `index-overview`
 * `mode-tabs-strip`). With SHOTS currently empty BOTH forms select zero
 * shots and `main()` exits non-zero ("No shots selected."); see the
 * empty-SHOTS note above.
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

// The legacy non-prefixed Story captures (01-shell-overview,
// 01-variant-loaded, 02-mode-tabs, 02-docs-mode, 02-test-mode,
// 03-recorder-modal, 04-workspace-grid, 06-time-travel,
// 07-multi-substrate) were retired with their orphaned PNGs (rf2-azqaq):
// the live Story tutorial now embeds the hand-captured s00–s13 set under
// docs/images/story/, which no script regenerates. No shots are defined
// here until a refreshed s-prefixed capture pipeline is authored.
const SHOTS = [];

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
