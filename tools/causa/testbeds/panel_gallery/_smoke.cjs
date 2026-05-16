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
    const panels = [
      {
        id:          'event-detail',
        workspaceRe: /Workspace\.causa\.event-detail\/all/,
        cardTestid:  'panel-gallery-event-detail-card',
        expectedAtLeast: 12,
      },
      {
        id:          'app-db-diff',
        workspaceRe: /Workspace\.causa\.app-db-diff\/all/,
        cardTestid:  'panel-gallery-app-db-diff-card',
        expectedAtLeast: 11,
      },
      {
        id:          'subscriptions',
        workspaceRe: /Workspace\.causa\.subscriptions\/all/,
        cardTestid:  'panel-gallery-subscriptions-card',
        expectedAtLeast: 10,
      },
      {
        id:          'time-travel',
        workspaceRe: /Workspace\.causa\.time-travel\/all/,
        cardTestid:  'panel-gallery-time-travel-card',
        expectedAtLeast: 8,
      },
      {
        id:          'trace',
        workspaceRe: /Workspace\.causa\.trace\/all/,
        cardTestid:  'panel-gallery-trace-card',
        expectedAtLeast: 9,
      },
      {
        id:          'issues-ribbon',
        workspaceRe: /Workspace\.causa\.issues-ribbon\/all/,
        cardTestid:  'panel-gallery-issues-ribbon-card',
        expectedAtLeast: 9,
      },
      {
        id:          'causality-graph',
        workspaceRe: /Workspace\.causa\.causality-graph\/all/,
        cardTestid:  'panel-gallery-causality-graph-card',
        expectedAtLeast: 10,
      },
      {
        id:          'ai-co-pilot',
        workspaceRe: /Workspace\.causa\.ai-co-pilot\/all/,
        cardTestid:  'panel-gallery-ai-co-pilot-card',
        expectedAtLeast: 12,
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
