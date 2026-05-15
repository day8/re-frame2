#!/usr/bin/env node
/*
 * Quiet Playwright runner for the occasional Story feature/load gate.
 *
 * Green output is one summary line. On failure the runner flushes the
 * buffered browser diagnostics plus Story-specific context: feature,
 * phase, URL, active variant, active mode, and browser console/page
 * errors. See docs/quiet-tests.md.
 */

const path = require('path');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const BASE_URL = process.env.STORY_FEATURE_LOAD_BASE_URL || 'http://127.0.0.1:8031';
const TIMEOUT_MS = parseInt(process.env.STORY_FEATURE_LOAD_TIMEOUT_MS || '300000', 10);
const VERBOSE = process.env.RF2_VERBOSE_TESTS === '1';

const ALL_SPEC_FILES = [
  path.join(REPO_ROOT, 'tools', 'story', 'test', 'story_feature_load.cjs'),
  path.join(REPO_ROOT, 'tools', 'story', 'test', 'story_browser_scenarios.cjs'),
];

const SPEC_FILTER = process.env.STORY_FEATURE_LOAD_SPEC || '';
const SPEC_FILES = SPEC_FILTER
  ? ALL_SPEC_FILES.filter((file) => path.basename(file).includes(SPEC_FILTER))
  : ALL_SPEC_FILES;

if (SPEC_FILTER && SPEC_FILES.length === 0) {
  throw new Error(`STORY_FEATURE_LOAD_SPEC=${SPEC_FILTER} matched no Story specs`);
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

function formatStoryContext(ctx) {
  if (!ctx) return 'Story context unavailable';
  return [
    `url=${ctx.url || ''}`,
    `hash=${ctx.hash || ''}`,
    `activeVariant=${ctx.activeVariant || ''}`,
    `activeMode=${ctx.activeMode || ''}`,
    `activeToolbarModes=${JSON.stringify(ctx.activeToolbarModes || [])}`,
  ].join('\n');
}

(async () => {
  const specs = SPEC_FILES.map((file) => {
    const mod = require(file);
    if (!mod || typeof mod.run !== 'function' || typeof mod.url !== 'string') {
      throw new Error(`Bad Story feature-load spec at ${file}: must export {name, url, run}`);
    }
    return { ...mod, file };
  });

  const browser = await chromium.launch({ headless: true });
  const results = [];
  let anyFailed = false;

  for (const spec of specs) {
    const label = spec.name || path.basename(spec.file);
    const lines = [];
    const consoleErrors = [];
    const pageErrors = [];
    const log = (s) => lines.push(s);
    const flush = () => {
      for (const line of lines) console.log(line);
    };

    log(`\n=== ${label} ===`);
    const context = await browser.newContext();
    const page = await context.newPage();

    page.on('console', (msg) => {
      const text = `[browser:${msg.type()}] ${msg.text()}`;
      if (msg.type() === 'error') {
        consoleErrors.push(text);
      }
      log(text);
    });
    page.on('pageerror', (err) => {
      const text = `[browser:pageerror] ${err.message}`;
      pageErrors.push(text);
      log(text);
      if (err.stack) log(err.stack);
    });

    const fullUrl = spec.url.startsWith('http') ? spec.url : BASE_URL + spec.url;
    log(`Navigating to ${fullUrl}`);

    let passed = false;
    let failure = null;
    try {
      await page.goto(fullUrl, { waitUntil: 'load', timeout: TIMEOUT_MS });
      await withTimeout(spec.run(page), TIMEOUT_MS, label);

      if (consoleErrors.length > 0 || pageErrors.length > 0) {
        throw new Error(
          `Browser emitted ${consoleErrors.length} console error(s) and ` +
            `${pageErrors.length} page error(s)`,
        );
      }

      passed = true;
    } catch (err) {
      failure = err;
      anyFailed = true;
      log(`FAIL ${label}: ${err.message}`);
      if (err.feature || err.phase) {
        log(`feature=${err.feature || ''}`);
        log(`phase=${err.phase || ''}`);
      }
      const ctx =
        err.storyContext ||
        (typeof spec.context === 'function'
          ? await spec.context(page).catch(() => null)
          : null);
      log(formatStoryContext(ctx));
      if (consoleErrors.length > 0) {
        log('console errors:');
        for (const line of consoleErrors) log(line);
      }
      if (pageErrors.length > 0) {
        log('page errors:');
        for (const line of pageErrors) log(line);
      }
      if (err.stack) log(err.stack);
    } finally {
      await context.close();
    }

    if (!passed || VERBOSE) flush();
    results.push({ label, passed, failure });
  }

  await browser.close();

  const failedCount = results.filter((r) => !r.passed).length;
  if (anyFailed) {
    console.log('\n=== summary ===');
    for (const r of results) {
      console.log(`${r.passed ? 'PASS' : 'FAIL'}  ${r.label}`);
    }
  }
  console.log(`Ran ${results.length} Story feature-load specs. ${failedCount} failures.`);
  process.exit(anyFailed ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
