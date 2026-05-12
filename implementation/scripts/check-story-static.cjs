#!/usr/bin/env node
/*
 * Story static-export sanity check (rf2-8wgpm).
 *
 * 1. Runs `story-build.cjs` to produce the static export at
 *    `implementation/out/story-static/counter-with-stories/`.
 * 2. Serves the output directory via http-server on port 8040.
 * 3. Drives a headless Chromium against http://127.0.0.1:8040/ and
 *    verifies the Story shell mounted (the canonical "Select a variant
 *    or workspace" placeholder is rendered, and the chrome landmarks
 *    are present).
 * 4. Verifies the first-visit help overlay is suppressed (per
 *    spec/013 §Static-mode runtime semantics — visitors arriving at a
 *    published docs site don't get the dev-time onboarding modal).
 * 5. Tears the server down.
 *
 * Mirrors the shape of `serve-and-run-browser-tests.cjs` so the
 * orchestration is familiar to anyone who's wired a per-build
 * smoke spec.
 */

'use strict';

const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');

const PORT = 8040;
const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const OUT_DIR = path.join(
  IMPL_ROOT,
  'out',
  'story-static',
  'counter-with-stories',
);
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const READY_TIMEOUT_MS = 30000;
const POLL_MS = 200;

function runBuild() {
  // Use process.execPath directly without shell:true — on Windows the
  // installed node.exe path commonly contains spaces ("Program Files"),
  // which the shell wrapper splits on. Skipping `shell` keeps the
  // argv pass-through verbatim.
  const script = path.join(__dirname, 'story-build.cjs');
  const result = spawnSync(process.execPath, [script], {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    throw new Error(`story-build.cjs failed (exit ${result.status})`);
  }
}

function probe(port) {
  return new Promise((resolve) => {
    const req = http.get(
      { host: '127.0.0.1', port, path: '/', timeout: 1000 },
      (res) => {
        res.resume();
        resolve(res.statusCode != null);
      },
    );
    req.on('error', () => resolve(false));
    req.on('timeout', () => {
      req.destroy();
      resolve(false);
    });
  });
}

async function waitForReady(port, deadline) {
  while (Date.now() < deadline) {
    if (await probe(port)) return true;
    await new Promise((r) => setTimeout(r, POLL_MS));
  }
  return false;
}

async function smokeTest(baseUrl) {
  let playwright;
  try {
    playwright = require('playwright');
  } catch (e) {
    throw new Error(
      'playwright not installed — run `npm install --prefix implementation` first.',
    );
  }

  const browser = await playwright.chromium.launch();
  const context = await browser.newContext();
  const page = await context.newPage();

  const consoleErrors = [];
  page.on('pageerror', (err) => consoleErrors.push(`pageerror: ${err.message}`));
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(`console.error: ${msg.text()}`);
  });

  try {
    await page.goto(baseUrl, { waitUntil: 'load', timeout: 30000 });

    // The shell renders the "Select a variant or workspace" placeholder
    // when no variant / workspace is selected. The Story chrome lands
    // around it: the sidebar lists the four counter variants + two
    // workspaces.
    await page
      .getByText(/Select a variant or workspace from the sidebar/i, { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 15000 });

    // Three landmarks (nav / main / aside) — same shape as the dev-mode
    // shell. Static-mode flips the dev-time affordances OFF; the chrome
    // structure is identical.
    await page.getByRole('navigation').waitFor({ state: 'visible', timeout: 5000 });
    await page.getByRole('main').waitFor({ state: 'visible', timeout: 5000 });
    await page
      .getByRole('complementary')
      .waitFor({ state: 'visible', timeout: 5000 });

    // The chrome-level toolbar renders. Per spec/010 the strip emits
    // `data-test="story-toolbar"`.
    await page
      .locator('[data-test="story-toolbar"]')
      .waitFor({ state: 'visible', timeout: 5000 });

    // First-visit help overlay must be SUPPRESSED in static mode (per
    // spec/013 §Static-mode runtime semantics). The dev-mode shell pops
    // a `role="dialog" aria-modal="true"` overlay on first paint; the
    // static-mode shell does not. We assert the overlay is absent ~1s
    // after first paint (enough for any `component-did-mount` race).
    await new Promise((r) => setTimeout(r, 1000));
    const dialogCount = await page.getByRole('dialog').count();
    if (dialogCount > 0) {
      throw new Error(
        `expected the first-visit help overlay to be suppressed in static mode, found ${dialogCount} dialog(s)`,
      );
    }

    // Click a variant from the sidebar — the canvas re-renders with
    // that variant's title, proving the registry survived `:advanced`
    // compilation and the dispatch / subscription path is wired.
    const navRow = page
      .getByRole('navigation')
      .getByText('/empty', { exact: false })
      .first();
    await navRow.waitFor({ state: 'visible', timeout: 10000 });
    await navRow.click();
    await page
      .getByText(':story.counter/empty', { exact: false })
      .first()
      .waitFor({ state: 'visible', timeout: 10000 });

    if (consoleErrors.length > 0) {
      console.warn('console errors observed (non-fatal):');
      for (const e of consoleErrors) console.warn('  ' + e);
    }
  } finally {
    await browser.close();
  }
}

(async () => {
  // 1. Build.
  runBuild();

  // 2. Sanity-check the on-disk shape — the build script writes
  //    index.html + main.js + manifest.json next to a `cljs-runtime/`
  //    directory.
  for (const required of ['index.html', 'main.js', 'manifest.json']) {
    const p = path.join(OUT_DIR, required);
    if (!fs.existsSync(p)) {
      console.error(`Build output missing ${required} at ${p}`);
      process.exit(1);
    }
  }

  // 3. Serve.
  const server = spawn(
    process.execPath,
    [HTTP_SERVER_BIN, OUT_DIR, '-p', String(PORT), '-s', '-c-1'],
    { cwd: IMPL_ROOT, stdio: ['ignore', 'inherit', 'inherit'] },
  );

  let serverDown = false;
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForReady(PORT, Date.now() + READY_TIMEOUT_MS);
  if (!ready || serverDown) {
    console.error(
      `http-server did not become reachable on :${PORT} within ${READY_TIMEOUT_MS}ms.`,
    );
    if (!serverDown) server.kill();
    process.exit(1);
  }

  // 4. Smoke.
  let smokeError = null;
  try {
    await smokeTest(`http://127.0.0.1:${PORT}/`);
    console.log('story-static smoke passed.');
  } catch (err) {
    smokeError = err;
    console.error('story-static smoke failed:', err.message || err);
  }

  // 5. Tear down.
  if (!serverDown) {
    server.kill();
  }
  process.exit(smokeError ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
