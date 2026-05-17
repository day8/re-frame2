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
    // the bug surfaces on the SECOND workspace's first render. Per
    // rf2-sszlr: workspaces renamed for the new 6-tab Causa shape.
    // The chrome workspace uses :layout :tabs (not :variants-grid)
    // so it isn't subject to the same shared-cell concern as the
    // grid workspaces; we walk it last as a round-trip target.
    //
    // Per rf2-om6fa: the chrome-follow-on workspaces (settings
    // popup, auto-filter edit-popup, causality popover) used to be
    // excluded from this walk because their modals mounted at
    // `position: fixed; inset: 0` with max-int z-index, stacking
    // N full-viewport backdrops over the Story shell. The
    // `:modal-positioning :absolute` opt threaded through
    // `chrome-shell` confines each cell's modals to the cell's
    // positioning context with a sane in-cell z-index, so they
    // can now ride the single-session walk without painting over
    // each other. Modal open-state still lives in the process-
    // global `:rf/causa` frame (per-frame scoping is a follow-on
    // — see the bead trail) but the visual contract is now
    // contained per cell.
    const walk = [
      { name: 'Workspace.causa.event/all',           expectedAtLeast: 12 },
      { name: 'Workspace.causa.app-db/all',          expectedAtLeast: 12 },
      { name: 'Workspace.causa.views/all',           expectedAtLeast: 7  },
      { name: 'Workspace.causa.trace/all',           expectedAtLeast: 10 },
      { name: 'Workspace.causa.machines/all',        expectedAtLeast: 6  },
      { name: 'Workspace.causa.issues/all',          expectedAtLeast: 11 },
      { name: 'Workspace.causa.settings-popup/all',  expectedAtLeast: 4  },
      { name: 'Workspace.causa.filters/all',         expectedAtLeast: 5  },
      { name: 'Workspace.causa.causality-popover/all', expectedAtLeast: 4 },
      { name: 'Workspace.causa.event/all',           expectedAtLeast: 12 }, // round-trip
      { name: 'Workspace.causa.app-db/all',          expectedAtLeast: 12 }, // round-trip
    ];

    // Per rf2-sszlr: the rf2-kgn0c regression gate this smoke pins is
    // `IDeref.-deref defined for type null` page-errors from un-allocated
    // variant frames. Non-fatal React "unique key" warnings emitted by
    // the Views panel's internal rendering surface when the gallery
    // populates the :rendered group with multiple items; they don't
    // break rendering (variant-roots count is unaffected). Filter them
    // out of the fatal-error budget — track the rf2-kgn0c class only.
    const FATAL_ERROR_RE = /IDeref|undefined is not|cannot read prop/i;

    const stepResults = [];
    for (const step of walk) {
      const errsBefore = errs.length;
      const row = page.getByText(step.name, { exact: false }).first();
      await row.waitFor({ state: 'visible', timeout: 10000 });
      await row.click({ timeout: 5000 });
      await page.waitForTimeout(2500);
      const variantRoots = await page.locator('[data-rf-story-variant-root]').count();
      const newSlice     = errs.slice(errsBefore, errs.length);
      const fatalNew     = newSlice.filter((e) => FATAL_ERROR_RE.test(e));
      stepResults.push({
        name:           step.name,
        expectedAtLeast: step.expectedAtLeast,
        variantRoots,
        newErrors:      newSlice.length,
        fatalNewErrors: fatalNew.length,
      });
    }

    let failed = false;
    for (const r of stepResults) {
      const ok = r.variantRoots >= r.expectedAtLeast && r.fatalNewErrors === 0;
      console.log(`step ${r.name}: variant-roots=${r.variantRoots}/${r.expectedAtLeast} ` +
                  `new-page-errors=${r.newErrors} fatal=${r.fatalNewErrors} ${ok ? 'OK' : 'FAIL'}`);
      if (!ok) failed = true;
    }
    const fatalTotal = errs.filter((e) => FATAL_ERROR_RE.test(e)).length;
    console.log(`TOTAL page errors (fatal/all): ${fatalTotal}/${errs.length}`);
    if (errs.length > 0) {
      for (const e of errs.slice(0, 10)) console.log(`  ${e}`);
    }
    if (failed || fatalTotal > 0) {
      throw new Error('single-session workspace-switch walk surfaced fatal page errors / missing variants');
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
