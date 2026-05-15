#!/usr/bin/env node
/*
 * Playwright runner for the shadow-cljs :browser-test build.
 *
 * Launches headless Chromium, navigates to the static-server URL serving
 * out/browser-test/index.html (the page that shadow-cljs's :browser-test
 * target generates with shadow.test.browser as the runner-ns), waits for
 * cljs.test to finish, parses the summary, and exits 0 on green / 1 on red.
 *
 * Done-signal strategy: shadow.test.browser's default reporter writes the
 * cljs.test :summary report via `console.log` (default cljs.test prints
 * to *out*, which under the browser is the console). It does NOT render
 * results to the DOM body and does NOT set a window flag. We therefore
 * tap `page.on('console', ...)` and watch the captured stream for the
 * summary line. The DOM is also scraped as a belt-and-braces fallback
 * in case a custom reporter is wired up later, and `window.shadow$cljs_test_done`
 * is checked first in case a runner-ns sets it.
 *
 * Summary line format (cljs.test default reporter):
 *   "Ran N tests containing M assertions."
 *   "P failures, Q errors."
 */

const { chromium } = require('playwright');
const {
  createDiagnosticBuffer,
  formatCompactSummary,
  isVerboseTests,
  parseFailureCounts,
  summaryPartsFromText,
} = require('./lib/browser-test-report.cjs');

const URL = process.env.BROWSER_TEST_URL || 'http://localhost:8021';
const TIMEOUT_MS = parseInt(process.env.BROWSER_TEST_TIMEOUT_MS || '120000', 10);
const POLL_MS = 200;
const VERBOSE_TESTS = isVerboseTests();

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function rememberSummary(summary, hit, sourceName) {
  if (hit.ran && !summary.ran) {
    summary.ran = hit.ran;
    summary.ranSource = sourceName;
  }
  if (hit.failErr && !summary.failErr) {
    summary.failErr = hit.failErr;
    summary.failErrSource = sourceName;
  }
  if (summary.ran && summary.failErr) {
    summary.source = summary.ranSource === summary.failErrSource
      ? summary.ranSource
      : `${summary.ranSource}; ${summary.failErrSource}`;
    return true;
  }
  return false;
}

function printSummaryDetails(summary) {
  console.error('--- cljs.test summary ---');
  console.error(`(source: ${summary.source || 'not found'})`);
  console.error(summary.ran || '(missing "Ran ... assertions." line)');
  console.error(summary.failErr || '(missing "failures, errors" line)');
  console.error('-------------------------');
}

function flushDiagnostics(diagnostics) {
  if (diagnostics.isEmpty()) return;
  console.error('--- browser test diagnostics ---');
  diagnostics.flush({
    stdout: (line) => console.error(line),
    stderr: (line) => console.error(line),
  });
  console.error('--------------------------------');
}

async function main() {
  const diagnostics = createDiagnosticBuffer();
  let browser = null;

  try {
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();

    // Capture every console line so we can scan for the cljs.test summary.
    // Flush the buffer only on failure or RF2_VERBOSE_TESTS=1.
    const consoleLines = [];
    diagnostics.add(`URL: ${URL}`);
    page.on('console', (msg) => {
      const text = msg.text();
      consoleLines.push(text);
      diagnostics.add(`[browser:${msg.type()}] ${text}`);
    });
    page.on('pageerror', (err) => {
      diagnostics.add(`[browser:pageerror] ${err.message}`, 'stderr');
      if (err.stack) diagnostics.add(err.stack, 'stderr');
    });
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) {
        diagnostics.add(`[browser:navigation] ${frame.url()}`);
      }
    });

    diagnostics.add(`Navigating to ${URL} ...`);
    await page.goto(URL, { waitUntil: 'load' });

    // Look for the cljs.test summary in one of three places, in order of preference:
    //   1. window.shadow$cljs_test_done   (custom hook a future runner may set)
    //   2. console output                 (the default place shadow.test.browser logs)
    //   3. document.body.innerText        (custom DOM reporter, if any)
    const start = Date.now();
    const summary = {
      ran: null,
      failErr: null,
      source: null,
      ranSource: null,
      failErrSource: null,
    };

    while (Date.now() - start < TIMEOUT_MS) {
      // 1. window flag
      const winPayload = await page.evaluate(() => {
        return (typeof window !== 'undefined' && window.shadow$cljs_test_done) || null;
      });
      if (winPayload) {
        const text = typeof winPayload === 'string' ? winPayload : JSON.stringify(winPayload);
        if (rememberSummary(summary, summaryPartsFromText(text), 'window.shadow$cljs_test_done')) {
          break;
        }
      }

      // 2. console buffer
      const consoleBlob = consoleLines.join('\n');
      if (rememberSummary(summary, summaryPartsFromText(consoleBlob), 'browser console')) {
        break;
      }

      // 3. DOM body
      const bodyText = await page.evaluate(() => (document.body ? document.body.innerText : ''));
      if (rememberSummary(summary, summaryPartsFromText(bodyText), 'document.body')) {
        break;
      }

      await sleep(POLL_MS);
    }

    if (!summary.ran || !summary.failErr) {
      console.error(`Timed out after ${TIMEOUT_MS}ms waiting for cljs.test summary.`);
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    const counts = parseFailureCounts(summary.failErr);
    if (!counts) {
      console.error('Could not parse failures/errors counts; failing the run.');
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    if (counts.failures > 0 || counts.errors > 0) {
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    console.log(formatCompactSummary({
      ran: summary.ran,
      failErr: summary.failErr,
      source: summary.source,
    }));
    if (VERBOSE_TESTS) flushDiagnostics(diagnostics);
    return 0;
  } catch (err) {
    console.error(err && err.stack ? err.stack : err);
    flushDiagnostics(diagnostics);
    return 1;
  } finally {
    if (browser) await browser.close();
  }
}

main()
  .then((exitCode) => process.exit(exitCode))
  .catch((err) => {
    console.error(err && err.stack ? err.stack : err);
    process.exit(1);
  });
