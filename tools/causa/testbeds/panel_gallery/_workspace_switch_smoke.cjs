#!/usr/bin/env node
/*
 * Local-only single-page workspace-switch smoke for the panel-gallery
 * testbed (rf2-kgn0c regression gate).
 *
 * The sibling `_smoke.cjs` uses a FRESH page per workspace — conservative
 * isolation that side-steps the workspace-teardown bug. This script
 * walks every panel-gallery workspace in ONE browser session, asserting
 * zero pageerrors across the whole walk. Pre-rf2-kgn0c fix, switching
 * between two `:variants-grid` workspaces let React's reconciler reuse
 * the prior workspace's `variant-cell` components (cell keys were
 * position-only); the cell's `r/with-let` initialiser ran with the OLD
 * variant id, the NEW variant's frame was never allocated, and the
 * rendered view's subscribe returned nil — `@nil` then threw
 * `No protocol method IDeref.-deref defined for type null`, surfacing
 * as the ~22 pageerrors observed in PR #1254's Phase 1b smoke when
 * clicking from `event-detail/all` to `app-db-diff/all`.
 *
 * Not wired into CI (mirrors `_smoke.cjs`'s posture). Run manually
 * post-compile to verify the fix end-to-end:
 *
 *   shadow-cljs compile testbeds/panel-gallery
 *   cp tools/causa/testbeds/panel_gallery/index.html implementation/out/testbeds/panel-gallery/
 *   node tools/causa/testbeds/panel_gallery/_workspace_switch_smoke.cjs
 */

const { spawn } = require('child_process');
const path = require('path');
const http = require('http');

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_DIR = path.join(IMPL_ROOT, 'out', 'testbeds', 'panel-gallery');
const PORT = Number(process.env.PANEL_GALLERY_WS_SWITCH_PORT || 8767);
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

    // Walk every gallery workspace in one session. Round-trip too —
    // the bug surfaces on the SECOND workspace's first render.
    const walk = [
      { name: 'Workspace.causa.event-detail/all',   expectedAtLeast: 12 },
      { name: 'Workspace.causa.app-db-diff/all',    expectedAtLeast: 11 },
      { name: 'Workspace.causa.subscriptions/all',  expectedAtLeast: 10 },
      { name: 'Workspace.causa.event-detail/all',   expectedAtLeast: 12 }, // round-trip
      { name: 'Workspace.causa.app-db-diff/all',    expectedAtLeast: 11 }, // round-trip
    ];

    const stepResults = [];
    for (const step of walk) {
      const errsBefore = errs.length;
      const row = page.getByText(step.name, { exact: false }).first();
      await row.waitFor({ state: 'visible', timeout: 10000 });
      await row.click({ timeout: 5000 });
      await page.waitForTimeout(2500);
      const variantRoots = await page.locator('[data-rf-story-variant-root]').count();
      const errsAfter    = errs.length;
      stepResults.push({
        name: step.name,
        expectedAtLeast: step.expectedAtLeast,
        variantRoots,
        newErrors: errsAfter - errsBefore,
      });
    }

    let failed = false;
    for (const r of stepResults) {
      const ok = r.variantRoots >= r.expectedAtLeast && r.newErrors === 0;
      console.log(`step ${r.name}: variant-roots=${r.variantRoots}/${r.expectedAtLeast} ` +
                  `new-page-errors=${r.newErrors} ${ok ? 'OK' : 'FAIL'}`);
      if (!ok) failed = true;
    }
    console.log(`TOTAL page errors across single-session walk: ${errs.length}`);
    if (errs.length > 0) {
      for (const e of errs.slice(0, 10)) console.log(`  ${e}`);
    }
    if (failed || errs.length > 0) {
      throw new Error('single-session workspace-switch walk surfaced page errors / missing variants');
    }
    console.log('workspace-switch smoke OK — zero stale subscribe→nil derefs');
    await browser.close();
  } catch (err) {
    console.error('workspace-switch smoke FAILED:', err.message);
    exitCode = 1;
  } finally {
    server.kill('SIGTERM');
    setTimeout(() => process.exit(exitCode), 200);
  }
})();
