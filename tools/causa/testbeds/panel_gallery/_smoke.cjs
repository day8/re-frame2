#!/usr/bin/env node
/*
 * Local-only smoke for the panel-gallery testbed (rf2-1o7mp). Boots an
 * http-server against the testbed bundle and drives Playwright to:
 *   1. Open `/index.html`              — landing renders.
 *   2. Open `/#/stories`               — Story shell mounts.
 *   3. Wait for the workspace gallery  — twelve variant cells render.
 *   4. Snapshot console errors         — none beyond the expected.
 *
 * Not wired into CI. Run manually post-compile to verify the gallery
 * boots end-to-end:
 *
 *   shadow-cljs compile testbeds/panel-gallery
 *   cp tools/causa/testbeds/panel_gallery/index.html implementation/out/testbeds/panel-gallery/
 *   node tools/causa/testbeds/panel_gallery/_smoke.cjs
 */

const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_DIR = path.join(IMPL_ROOT, 'out', 'testbeds', 'panel-gallery');
const PORT = Number(process.env.PANEL_GALLERY_SMOKE_PORT || 8766);
const BASE_URL = `http://127.0.0.1:${PORT}`;

const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});

function waitForReady(url, timeoutMs) {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    function probe() {
      const req = http.get(url, (res) => {
        res.resume();
        if (res.statusCode === 200) resolve();
        else if (Date.now() - start > timeoutMs) {
          reject(new Error(`bad status ${res.statusCode} after ${timeoutMs}ms`));
        } else setTimeout(probe, 200);
      });
      req.on('error', () => {
        if (Date.now() - start > timeoutMs) reject(new Error(`server not ready in ${timeoutMs}ms`));
        else setTimeout(probe, 200);
      });
    }
    probe();
  });
}

(async () => {
  const server = spawn(process.execPath, [HTTP_SERVER_BIN, OUT_DIR, '-p', String(PORT), '-s'], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  let exitCode = 0;
  try {
    await waitForReady(`${BASE_URL}/index.html`, 5000);
    const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));
    const browser = await chromium.launch();

    // 1. landing (single page)
    {
      const page = await (await browser.newContext()).newPage();
      await page.goto(`${BASE_URL}/index.html`, { waitUntil: 'load' });
      await page.waitForSelector('h1', { timeout: 5000 });
      const heading = await page.locator('h1').first().textContent();
      if (!heading.includes('Causa panel gallery')) {
        throw new Error(`landing heading wrong: ${heading}`);
      }
      console.log('landing OK');
      await page.close();
    }

    // 2. per-panel workspace walkthrough on FRESH pages.
    //
    // Each panel registered by Phase 1a (event-detail) and Phase 1b
    // (app-db-diff, subscriptions) gets its own browser context + page.
    // Workspace teardown across substrates in a single session has been
    // observed to surface stale reaction derefs (variants registered for
    // an unmounted-but-still-cached cell), so a clean page per workspace
    // is the conservative assertion of "every variant renders" without
    // entangling with shell-state teardown semantics.
    // Per rf2-sszlr: gallery rebuilt for the new 6-tab Causa shape.
    // One workspace per L4 tab + one for the full chrome. Causality
    // and time-travel are no longer panel tabs (Causality dropped
    // entirely in rf2-y0z5b; Time Travel folds into the spine per
    // spec/018-Event-Spine).
    const panels = [
      {
        id:          'event',
        workspaceRe: /Workspace\.causa\.event\/all/,
        cardTestid:  'panel-gallery-event-card',
        expectedAtLeast: 12,
      },
      {
        id:          'app-db',
        workspaceRe: /Workspace\.causa\.app-db\/all/,
        cardTestid:  'panel-gallery-app-db-card',
        expectedAtLeast: 12,
      },
      {
        id:          'views',
        workspaceRe: /Workspace\.causa\.views\/all/,
        cardTestid:  'panel-gallery-views-card',
        expectedAtLeast: 7,
      },
      {
        id:          'trace',
        workspaceRe: /Workspace\.causa\.trace\/all/,
        cardTestid:  'panel-gallery-trace-card',
        expectedAtLeast: 10,
      },
      {
        id:          'machines',
        workspaceRe: /Workspace\.causa\.machines\/all/,
        cardTestid:  'panel-gallery-machines-card',
        expectedAtLeast: 7,
      },
      {
        id:          'issues',
        workspaceRe: /Workspace\.causa\.issues\/all/,
        cardTestid:  'panel-gallery-issues-card',
        expectedAtLeast: 11,
      },
      // Chrome workspace renders 10 cells in a :variants-grid; all
      // share :rf/causa state but each cell IS mounted, so the
      // card-count assertion still proves the chrome renders.
      {
        id:          'chrome',
        workspaceRe: /Workspace\.causa\.chrome\/all/,
        cardTestid:  'panel-gallery-chrome-card',
        expectedAtLeast: 10,
      },
      // Settings popup workspace (rf2-mpn8m, rf2-jh9ws) — 3
      // variants. Each cell mounts the chrome + opens the Settings
      // popup on a different tab (General / Filters / Theme). The
      // Telemetry tab was removed (rf2-jh9ws) because no telemetry
      // endpoint exists — chrome must not pretend. Same shared
      // :rf/causa caveat as the chrome workspace.
      {
        id:          'settings-popup',
        workspaceRe: /Workspace\.causa\.settings-popup\/all/,
        cardTestid:  'panel-gallery-chrome-card',
        expectedAtLeast: 3,
      },
      // Auto-filter pill + edit-popup workspace (rf2-kbrkx) — 5
      // variants. Each cell mounts the chrome and either seeds
      // ribbon pill state or opens the edit popup against a
      // different trigger shape (:add, :pill, :context).
      {
        id:          'filters',
        workspaceRe: /Workspace\.causa\.filters\/all/,
        cardTestid:  'panel-gallery-chrome-card',
        expectedAtLeast: 5,
      },
    ];

    const results = [];
    for (const panel of panels) {
      const page = await (await browser.newContext()).newPage();
      const errs = [];
      page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`));
      page.on('console', (m) => {
        if (m.type() === 'error') errs.push(`console.error: ${m.text()}`);
      });
      await page.goto(`${BASE_URL}/index.html#/stories`, { waitUntil: 'load' });
      await page.waitForTimeout(1500);
      const gotIt = page.locator('button:has-text("Got it")').first();
      if ((await gotIt.count()) > 0) await gotIt.click({ timeout: 2000 }).catch(() => {});
      await page.waitForTimeout(500);

      const workspaceLine = page.getByText(panel.workspaceRe).first();
      if ((await workspaceLine.count()) === 0) {
        results.push({ ...panel, variantRoots: 0, cards: 0, missingWorkspace: true, errs });
        await page.close();
        continue;
      }
      await workspaceLine.click({ timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(2500);
      const variantRoots = await page.locator('[data-rf-story-variant-root]').count();
      const cards        = await page.locator(`[data-testid="${panel.cardTestid}"]`).count();
      results.push({ ...panel, variantRoots, cards, missingWorkspace: false, errs });
      await page.close();
    }

    let totalRoots = 0;
    let totalCards = 0;
    let totalExpected = 0;
    let failed = false;
    for (const r of results) {
      console.log(`panel=${r.id} workspace=${r.missingWorkspace ? 'MISSING' : 'OK'} ` +
                  `variant-roots=${r.variantRoots}/${r.expectedAtLeast} ` +
                  `cards=${r.cards}/${r.expectedAtLeast} ` +
                  `page-errors=${r.errs.length}`);
      if (r.errs.length > 0) {
        for (const e of r.errs.slice(0, 3)) console.log(`  ${e}`);
      }
      totalRoots    += r.variantRoots;
      totalCards    += r.cards;
      totalExpected += r.expectedAtLeast;
      if (r.missingWorkspace || r.variantRoots < r.expectedAtLeast || r.cards < r.expectedAtLeast) {
        failed = true;
      }
    }
    console.log(`TOTAL variant-roots=${totalRoots}/${totalExpected} cards=${totalCards}/${totalExpected}`);

    if (failed) {
      throw new Error('one or more panels failed the variant-root / card count assertion');
    }
    console.log('smoke OK');
    await browser.close();
  } catch (err) {
    console.error('smoke FAILED:', err.message);
    exitCode = 1;
  } finally {
    server.kill('SIGTERM');
    setTimeout(() => process.exit(exitCode), 200);
  }
})();
