#!/usr/bin/env node
/*
 * Playwright runner for the example apps.
 *
 * Each example is built by shadow-cljs into out/examples/<name>/main.js
 * and is paired with a hand-written index.html (staged into the same
 * directory by the orchestrator). This runner spins up a Chromium
 * browser and executes the spec files under examples/playwright/, each
 * of which navigates to the example's URL and asserts a user-visible
 * behaviour (initial render + an interaction + post-interaction state).
 *
 * Why a hand-rolled runner rather than @playwright/test:
 *   - Mirrors the run-browser-tests.cjs pattern already used by rf2-zoem
 *     (PR #15) — same dependencies (`playwright`), same console-tap +
 *     pageerror discipline, same exit-code contract.
 *   - Avoids dragging in @playwright/test as a separate devDep when
 *     the spec surface is small and the orchestration is straightforward.
 *
 * Spec format: each spec exports an object
 *   {
 *     name:  string,                         // human-readable
 *     url:   string,                         // path under the static server root
 *     run:   async (page) => void            // Playwright assertions
 *   }
 *
 * Exit code: 0 if every spec's `run` resolves; 1 if any spec throws or
 * a pageerror fires during a spec.
 */

const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const BASE_URL = process.env.EXAMPLES_BASE_URL || 'http://127.0.0.1:8030';
const SPECS_DIR = path.resolve(__dirname, '..', '..', 'examples', 'playwright');
const TIMEOUT_MS = parseInt(process.env.EXAMPLE_SPEC_TIMEOUT_MS || '30000', 10);

function listSpecFiles(dir) {
  if (!fs.existsSync(dir)) {
    console.error(`Specs directory does not exist: ${dir}`);
    return [];
  }
  return fs
    .readdirSync(dir)
    .filter((f) => f.endsWith('.spec.cjs'))
    .sort()
    .map((f) => path.join(dir, f));
}

function withTimeout(promise, ms, label) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(
      () => reject(new Error(`Spec '${label}' timed out after ${ms}ms`)),
      ms,
    );
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

(async () => {
  const specFiles = listSpecFiles(SPECS_DIR);
  if (specFiles.length === 0) {
    console.error(`No specs found in ${SPECS_DIR}`);
    process.exit(1);
  }

  const specs = specFiles.map((file) => {
    const mod = require(file);
    if (!mod || typeof mod.run !== 'function' || typeof mod.url !== 'string') {
      throw new Error(`Bad spec module at ${file}: must export {name, url, run}`);
    }
    return { ...mod, file };
  });

  const browser = await chromium.launch({ headless: true });
  const results = [];
  let anyFailed = false;

  for (const spec of specs) {
    const label = spec.name || path.basename(spec.file);
    console.log(`\n=== ${label} ===`);
    const context = await browser.newContext();
    const page = await context.newPage();

    const consoleLines = [];
    page.on('console', (msg) => {
      const text = msg.text();
      consoleLines.push(text);
      console.log(`[browser:${msg.type()}] ${text}`);
    });
    let pageErrored = null;
    page.on('pageerror', (err) => {
      pageErrored = err;
      console.error(`[browser:pageerror] ${err.message}`);
      if (err.stack) console.error(err.stack);
    });

    const fullUrl = spec.url.startsWith('http') ? spec.url : BASE_URL + spec.url;
    console.log(`Navigating to ${fullUrl}`);

    let passed = false;
    let failure = null;
    try {
      await page.goto(fullUrl, { waitUntil: 'load', timeout: TIMEOUT_MS });
      await withTimeout(spec.run(page), TIMEOUT_MS, label);
      if (pageErrored) {
        throw new Error(`Page emitted an uncaught error: ${pageErrored.message}`);
      }
      passed = true;
    } catch (err) {
      failure = err;
      anyFailed = true;
      console.error(`FAIL ${label}: ${err.message}`);
      if (err.stack) console.error(err.stack);
    } finally {
      await context.close();
    }

    results.push({ label, passed, failure });
  }

  await browser.close();

  console.log('\n=== summary ===');
  for (const r of results) {
    console.log(`${r.passed ? 'PASS' : 'FAIL'}  ${r.label}`);
  }

  process.exit(anyFailed ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
