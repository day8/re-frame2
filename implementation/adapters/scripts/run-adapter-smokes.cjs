#!/usr/bin/env node
/*
 * Playwright runner for the adapter smokes (implementation/adapters/TESTING.md).
 *
 * The orchestrator (serve-and-run-adapter-smokes.cjs) builds and serves each
 * testbed, then spawns this runner, which loads every selected spec.cjs in
 * headless Chromium. Before running, it reconciles the ADAPTER_SMOKES set
 * (adapter-smoke-filter.cjs) against the spec files on disk and refuses on
 * drift in either direction.
 *
 * Spec format: each spec exports
 *   {
 *     name:  string,                          // human-readable
 *     url:   string,                          // path under the static server root
 *     run:   async (page) => void,            // Playwright assertions
 *     expectedConsoleErrors?: RegExp[],       // console errors this spec allows
 *   }
 *
 * The page is clean or the spec fails: an uncaught page error, or a console
 * error matching none of the spec's `expectedConsoleErrors`, fails it even when
 * `run` resolved. The listeners attach before the page loads, so a boot-time
 * error counts — a React development-build warning at mount is exactly what a
 * mount smoke must catch.
 *
 * Exit code: 0 when every selected spec passes; 1 otherwise.
 */

const path = require('path');
const {
  ADAPTER_SMOKES,
  ADAPTER_SMOKE_SPEC_ROOTS,
  parseFilterPatterns,
  selectEntries,
  listSpecFiles,
  reconcile,
} = require('./adapter-smoke-filter.cjs');

// __dirname is <repo>/implementation/adapters/scripts, so IMPL_ROOT is two
// levels up. playwright is an implementation/ devDependency; resolve it from
// there so the runner works from any cwd.
const IMPL_ROOT = path.resolve(__dirname, '..', '..');
const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

// The orchestrator always sets ADAPTER_SMOKE_BASE_URL to the port it resolved;
// the fallback, its default port, serves a standalone run.
const BASE_URL = process.env.ADAPTER_SMOKE_BASE_URL || 'http://127.0.0.1:8050';
// Same filter, same `selectEntries`, as the orchestrator's compile phase.
const FILTER = (process.env.ADAPTER_SMOKE_FILTER || '').trim();
const FILTER_PATTERNS = parseFilterPatterns(FILTER);
const TIMEOUT_MS = parseInt(process.env.EXAMPLE_SPEC_TIMEOUT_MS || '30000', 10);
const { isVerboseTests } = require(path.join(
  IMPL_ROOT,
  'scripts',
  'lib',
  'browser-test-report.cjs',
));
const VERBOSE_TESTS = isVerboseTests();
const { withTimeout } = require(path.join(IMPL_ROOT, 'scripts', 'lib', 'local-browser-harness.cjs'));

(async () => {
  const discovered = listSpecFiles(ADAPTER_SMOKE_SPEC_ROOTS);
  const declared = ADAPTER_SMOKES.map((e) => e.specPath);
  const { missing, undeclared } = reconcile(declared, discovered);
  if (missing.length > 0) {
    console.error(
      `ADAPTER_SMOKES references spec(s) not on disk:\n  ${missing.join('\n  ')}\n` +
        `Fix ADAPTERS in implementation/adapters/scripts/adapter-smoke-filter.cjs (a renamed/removed smoke?).`,
    );
    process.exit(1);
  }
  if (undeclared.length > 0) {
    console.error(
      `Found spec(s) under ${ADAPTER_SMOKE_SPEC_ROOTS.join(', ')} not in ADAPTER_SMOKES:\n  ${undeclared.join('\n  ')}\n` +
        `Add the adapter to ADAPTERS in implementation/adapters/scripts/adapter-smoke-filter.cjs (or remove the stray spec).`,
    );
    process.exit(1);
  }

  const selected = selectEntries(FILTER_PATTERNS);
  if (selected.length === 0) {
    if (FILTER) {
      console.error(
        `ADAPTER_SMOKE_FILTER='${FILTER}' matched zero smokes (of ${ADAPTER_SMOKES.length} declared).`,
      );
    } else {
      console.error(`No smokes declared in ADAPTER_SMOKES.`);
    }
    process.exit(1);
  }

  const specs = selected.map((e) => {
    const file = e.specPath;
    const mod = require(file);
    if (!mod || typeof mod.run !== 'function' || typeof mod.url !== 'string') {
      throw new Error(`Bad spec module at ${file}: must export {name, url, run}`);
    }
    return { ...mod, file };
  });

  const browser = await chromium.launch({ headless: true });
  const results = [];
  let anyFailed = false;

  // Silent on success: per-spec narration is buffered and printed only for a
  // failing spec (or under verbose tests).
  for (const spec of specs) {
    const label = spec.name || path.basename(spec.file);
    const expected = spec.expectedConsoleErrors || [];
    const lines = [];
    const log = (s) => lines.push(s);
    const flush = () => {
      for (const ln of lines) console.log(ln);
    };

    log(`\n=== ${label} ===`);

    const context = await browser.newContext();
    const page = await context.newPage();

    const consoleErrors = [];
    page.on('console', (msg) => {
      const text = msg.text();
      log(`[browser:${msg.type()}] ${text}`);
      if (msg.type() === 'error' && !expected.some((re) => re.test(text))) {
        consoleErrors.push(text);
      }
    });
    let pageErrored = null;
    page.on('pageerror', (err) => {
      pageErrored = err;
      log(`[browser:pageerror] ${err.message}`);
      if (err.stack) log(err.stack);
    });

    const fullUrl = spec.url.startsWith('http') ? spec.url : BASE_URL + spec.url;
    log(`Navigating to ${fullUrl}`);

    let passed = false;
    let failure = null;
    try {
      await page.goto(fullUrl, { waitUntil: 'load', timeout: TIMEOUT_MS });
      await withTimeout(spec.run(page), TIMEOUT_MS, `Spec '${label}'`);
      if (pageErrored) {
        throw new Error(`Page emitted an uncaught error: ${pageErrored.message}`);
      }
      if (consoleErrors.length > 0) {
        throw new Error(
          `Page logged ${consoleErrors.length} unexpected console error(s):\n  ` +
            consoleErrors.join('\n  '),
        );
      }
      passed = true;
    } catch (err) {
      failure = err;
      anyFailed = true;
      log(`FAIL ${label}: ${err.message}`);
      if (err.stack) log(err.stack);
    } finally {
      await context.close();
    }

    if (!passed || VERBOSE_TESTS) flush();
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
  console.log(
    `Ran ${results.length} adapter-smoke specs. ${failedCount} failures.`,
  );

  process.exit(anyFailed ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
