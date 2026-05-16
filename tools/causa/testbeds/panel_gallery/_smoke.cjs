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
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const consoleErrors = [];
    page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(`console.error: ${msg.text()}`);
    });

    // 1. landing
    await page.goto(`${BASE_URL}/index.html`, { waitUntil: 'load' });
    await page.waitForSelector('h1', { timeout: 5000 });
    const heading = await page.locator('h1').first().textContent();
    if (!heading.includes('Causa panel gallery')) throw new Error(`landing heading wrong: ${heading}`);

    // 2. shell
    await page.goto(`${BASE_URL}/index.html#/stories`, { waitUntil: 'load' });
    // Story shell mounts a sidebar; the sidebar always renders; variants
    // mount after the user picks a story or a workspace.
    await page.waitForTimeout(1500);

    // Probe the page DOM to discover what Story has rendered.
    const sidebarText = await page.locator('body').textContent({ timeout: 5000 });
    const hasStoryShell = sidebarText.includes('story.causa.event-detail') ||
                          sidebarText.includes('Workspace.causa.event-detail') ||
                          sidebarText.includes('event-detail');
    if (!hasStoryShell) throw new Error(`Story shell text not found; first 500: ${sidebarText.slice(0, 500)}`);

    // Dismiss the welcome overlay if present.
    const gotIt = page.locator('button:has-text("Got it")').first();
    if ((await gotIt.count()) > 0) await gotIt.click({ timeout: 2000 }).catch(() => {});
    await page.waitForTimeout(500);

    // The sidebar lists every variant + workspace by name. Click the
    // workspace-grid entry first; fall back to the first variant.
    let variantRoots = 0;
    const workspaceLine = page.locator('text=/Workspace.causa.event-detail\\/all/').first();
    if ((await workspaceLine.count()) > 0) {
      await workspaceLine.click({ timeout: 3000 }).catch(() => {});
      await page.waitForTimeout(2000);
      variantRoots = await page.locator('[data-rf-story-variant-root]').count();
    }
    if (variantRoots < 1) {
      const variantLine = page.locator('text=/empty-buffer/').first();
      if ((await variantLine.count()) > 0) {
        await variantLine.click({ timeout: 3000 }).catch(() => {});
        await page.waitForTimeout(1500);
        variantRoots = await page.locator('[data-rf-story-variant-root]').count();
      }
    }
    const cards = await page.locator('[data-testid="panel-gallery-event-detail-card"]').count();

    console.log(`landing OK • shell text contains story keys • variant-roots=${variantRoots} cards=${cards}`);
    if (consoleErrors.length) {
      console.log('--- console errors ---');
      for (const err of consoleErrors) console.log(err);
      console.log('----------------------');
    }
    if (variantRoots < 1) {
      // Debug — dump the visible body content so we can see what Story rendered.
      const bodyText = await page.locator('body').textContent({ timeout: 2000 });
      console.log('--- body text snippet ---');
      console.log(bodyText.slice(0, 2000));
      console.log('-------------------------');
      // Dump links / clickable elements
      const links = await page.evaluate(() => {
        const out = [];
        document.querySelectorAll('a,button,[role="button"],[data-testid]').forEach((el) => {
          out.push(`${el.tagName} testid=${el.getAttribute('data-testid')} text=${(el.textContent || '').slice(0, 60)}`);
        });
        return out.slice(0, 40);
      });
      console.log('--- clickable elements ---');
      console.log(links.join('\n'));
      console.log('--------------------------');
      throw new Error(`expected ≥1 variant root after navigation, got ${variantRoots}`);
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
