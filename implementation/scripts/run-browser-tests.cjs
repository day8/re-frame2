#!/usr/bin/env node
/*
 * Playwright runner for the shadow-cljs :browser-test build.
 *
 * Launches headless Chromium, navigates to the static-server URL serving
 * out/browser-test/index.html (the page that shadow-cljs's :browser-test
 * target generates with shadow.test.browser as the runner-ns), waits for
 * cljs.test to finish, parses the summary, and exits 0 on green / 1 on red.
 *
 * "Green" means BOTH halves of the summary: a clean failure tally AND at
 * least RF2_MIN_TESTS tests actually executed (default 1). The tally alone
 * is not enough — `Ran 0 tests containing 0 assertions. / 0 failures, 0
 * errors.` is what a lane whose `:ns-regexp` selected nothing prints, and
 * shadow-cljs's find-test-namespaces returns `[]` on no match silently
 * (rf2-qqzmf).
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
  parseRanCounts,
  summaryPartsFromText,
} = require('./lib/browser-test-report.cjs');
// `sleep` is the shared poll primitive — verbatim the same
// `setTimeout`-backed Promise this runner used to define inline
// (rf2-j552l2 dedup; exported from lib/local-browser-harness.cjs).
const { sleep } = require('./lib/local-browser-harness.cjs');

const URL = process.env.BROWSER_TEST_URL || 'http://localhost:8021';
const TIMEOUT_MS = parseInt(process.env.BROWSER_TEST_TIMEOUT_MS || '120000', 10);
const POLL_MS = 200;
const VERBOSE_TESTS = isVerboseTests();

// The test-count floor (rf2-qqzmf). ONE name across every whole-suite lane:
// the JVM runner (re-frame.test-quiet.runner) and the CLJS node runner
// (re-frame.test-quiet.shadow-node) read the same variable. Because it is
// one name, scope it to the lane you are running — a value calibrated for
// the 30-file :browser-test build will red :browser-test-prod-elision.
const MIN_TESTS_ENV_VAR = 'RF2_MIN_TESTS';
// Default 1, not a per-lane calibrated number: 1 is the bound that can never
// go stale (no lane legitimately runs zero tests), and it is exactly what
// three shadow-cljs `:browser-test` builds selected by a single `:ns-regexp`
// suffix need — a one-character suffix drift or a dropped `:source-paths`
// entry empties a lane silently and shadow-cljs says nothing.
const DEFAULT_MIN_TESTS = 1;

// Resolve the floor, or return null after explaining a malformed value. A
// malformed floor must NOT fall back to the default: `RF2_MIN_TESTS=1O`
// (letter O) quietly disabling the gate that catches silent non-execution
// would be this bug wearing a hat.
function resolveMinTests(raw) {
  if (raw == null || String(raw).trim() === '') return DEFAULT_MIN_TESTS;
  const n = Number(String(raw).trim());
  if (!Number.isInteger(n) || n < 0) {
    console.error(
      `${MIN_TESTS_ENV_VAR}="${raw}" is not a non-negative integer. It is the ` +
        `minimum number of tests this lane must run; leave it unset for the ` +
        `default of ${DEFAULT_MIN_TESTS}.`,
    );
    return null;
  }
  return n;
}

// One-purpose bridge for the ViewCell evidence retention fixture. Browser
// JavaScript has no deterministic GC primitive, while Playwright exposes the
// engine-supported `page.requestGC()` control. The cljs.test publishes a
// token + in-page acknowledgement callback under this key; the runner serves
// exactly that request and nothing else. Engines without requestGC receive a
// supported=false acknowledgement so the fixture can skip cleanly instead of
// hanging. This is intentionally NOT a general page RPC channel.
const EVIDENCE_GC_REQUEST = '__RF2_TOOL_EVIDENCE_GC_REQUEST__';
const EVIDENCE_GC_CANONICAL = '__RF2_TOOL_EVIDENCE_GC_CANONICAL__';

async function serviceEvidenceGcRequest(page, diagnostics) {
  const request = await page.evaluate((key) => {
    const value = globalThis[key];
    return value && typeof value.token === 'string'
      ? { token: value.token }
      : null;
  }, EVIDENCE_GC_REQUEST);
  if (!request) return;

  let supported = typeof page.requestGC === 'function';
  let error = null;
  if (supported) {
    try {
      await page.requestGC();
    } catch (err) {
      supported = false;
      error = err && err.message ? err.message : String(err);
    }
  }
  diagnostics.add(
    `[browser:gc] tool-evidence token=${request.token} ` +
      `${supported ? 'collected' : `unsupported${error ? ` (${error})` : ''}`}`,
  );

  await page.evaluate(({ key, token, supported: ok, error: message }) => {
    const value = globalThis[key];
    if (!value || value.token !== token) return;
    delete globalThis[key];
    if (typeof value.ack === 'function') value.ack({ supported: ok, error: message });
  }, { key: EVIDENCE_GC_REQUEST, token: request.token, supported, error });
}

// rf2-mwx08: capture the `ran` + `failErr` lines as an ATOMIC pair from
// a single source. summaryPartsFromText now only ever yields a non-null
// `failErr` together with the `Ran ...` line it directly follows, so the
// failure verdict can never be assembled from a `ran` line in one source
// and a noise-shaped `failures, errors` line in another. We lock the
// summary the moment ONE source produces a complete pair. The `ran`-only
// half is still remembered (best-effort, for a meaningful timeout
// message) but never combined cross-source into a green verdict.
function rememberSummary(summary, hit, sourceName) {
  if (hit.ran && hit.failErr) {
    summary.ran = hit.ran;
    summary.failErr = hit.failErr;
    summary.ranSource = sourceName;
    summary.failErrSource = sourceName;
    summary.source = sourceName;
    return true;
  }
  // Partial: a `Ran ...` line with no failures/errors pair yet. Keep it
  // for diagnostics only — do NOT pair it with any prior `failErr`.
  if (hit.ran && !summary.failErr) {
    summary.ran = hit.ran;
    summary.ranSource = sourceName;
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

  // Resolved before Chromium launches: a malformed floor is a configuration
  // error, not something to discover after a two-minute browser run.
  const minTests = resolveMinTests(process.env[MIN_TESTS_ENV_VAR]);
  if (minTests === null) return 2;

  try {
    browser = await chromium.launch({ headless: true });
    const context = await browser.newContext();
    const page = await context.newPage();
    // The canonical runner must never silently take the manual/no-control
    // branch in the evidence GC fixture. Installed before any page script.
    await page.addInitScript((key) => {
      globalThis[key] = true;
    }, EVIDENCE_GC_CANONICAL);

    // Capture every console line so we can scan for the cljs.test summary.
    // Flush the buffer only on failure or RF2_VERBOSE_TESTS=1.
    const consoleLines = [];
    // rf2-mwx08: track uncaught browser/runtime exceptions SEPARATELY from
    // console noise. A green cljs.test summary must NOT exit 0 if Chromium
    // observed an uncaught `pageerror` (a real runtime regression the
    // suite happened not to assert on). Console output stays diagnostic-
    // only; only `pageerror` is fatal. Mirrors the rf2-wf5al fix.
    const pageErrors = [];
    diagnostics.add(`URL: ${URL}`);
    page.on('console', (msg) => {
      const text = msg.text();
      consoleLines.push(text);
      diagnostics.add(`[browser:${msg.type()}] ${text}`);
    });
    page.on('pageerror', (err) => {
      pageErrors.push(err.stack || err.message);
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
      // Serve a pending deterministic GC request before looking for the test
      // summary: the requesting cljs.test is async and cannot finish until
      // this acknowledgement arrives.
      await serviceEvidenceGcRequest(page, diagnostics);

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

    // rf2-mwx08: even a green cljs.test summary fails the run if Chromium
    // observed an uncaught `pageerror`. The suite may simply not assert on
    // the regression that threw; a passing summary is necessary but not
    // sufficient for a green verdict.
    if (pageErrors.length > 0) {
      console.error(
        `cljs.test summary was green, but the browser emitted ` +
          `${pageErrors.length} uncaught pageerror(s) — failing the run (rf2-mwx08).`,
      );
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }

    // rf2-qqzmf: the verdict above is derived from the FAILURE tally alone,
    // which `Ran 0 tests containing 0 assertions. / 0 failures, 0 errors.`
    // satisfies. A lane whose `:ns-regexp` selected nothing therefore looked
    // identical to a green suite. The `Ran N` count this runner has already
    // parsed is the missing half of the verdict.
    const ranCounts = parseRanCounts(summary.ran);
    if (!ranCounts) {
      console.error('Could not parse the "Ran N tests" count; failing the run.');
      printSummaryDetails(summary);
      flushDiagnostics(diagnostics);
      return 1;
    }
    if (ranCounts.tests < minTests) {
      console.error(
        `cljs.test summary was green, but the lane ran ${ranCounts.tests} ` +
          `test(s) — below the floor of ${minTests} (${MIN_TESTS_ENV_VAR}) — ` +
          `failing the run (rf2-qqzmf). A browser lane that discovered no ` +
          `tests is a configuration error: a renamed test namespace, a ` +
          `one-character :ns-regexp suffix drift, or a dropped :source-paths ` +
          `entry empties the build silently.`,
      );
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
