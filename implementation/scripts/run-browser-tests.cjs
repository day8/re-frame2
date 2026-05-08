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

const URL = process.env.BROWSER_TEST_URL || 'http://localhost:8021';
const TIMEOUT_MS = parseInt(process.env.BROWSER_TEST_TIMEOUT_MS || '120000', 10);
const POLL_MS = 200;

const RAN_RE = /Ran\s+(\d+)\s+tests?\s+containing\s+(\d+)\s+assertions?\./;
const FAIL_RE = /(\d+)\s+failures?,\s*(\d+)\s+errors?\.?/;

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();

  // Capture every console line so we can scan for the cljs.test summary.
  const consoleLines = [];
  page.on('console', (msg) => {
    const text = msg.text();
    consoleLines.push(text);
    console.log(`[browser:${msg.type()}] ${text}`);
  });
  page.on('pageerror', (err) => {
    console.error(`[browser:pageerror] ${err.message}`);
  });

  console.log(`Navigating to ${URL} ...`);
  await page.goto(URL, { waitUntil: 'load' });

  // Look for the cljs.test summary in one of three places, in order of preference:
  //   1. window.shadow$cljs_test_done   (custom hook a future runner may set)
  //   2. console output                 (the default place shadow.test.browser logs)
  //   3. document.body.innerText        (custom DOM reporter, if any)
  const start = Date.now();
  let ran = null;
  let failErr = null;
  let source = null;

  const findInText = (text) => {
    const r = text && text.match(RAN_RE);
    const f = text && text.match(FAIL_RE);
    return r && f ? { ran: r[0], failErr: f[0] } : null;
  };

  while (Date.now() - start < TIMEOUT_MS) {
    // 1. window flag
    const winPayload = await page.evaluate(() => {
      return (typeof window !== 'undefined' && window.shadow$cljs_test_done) || null;
    });
    if (winPayload) {
      const hit = findInText(typeof winPayload === 'string' ? winPayload : JSON.stringify(winPayload));
      if (hit) { ran = hit.ran; failErr = hit.failErr; source = 'window.shadow$cljs_test_done'; break; }
    }

    // 2. console buffer
    const consoleBlob = consoleLines.join('\n');
    const hit = findInText(consoleBlob);
    if (hit) { ran = hit.ran; failErr = hit.failErr; source = 'browser console'; break; }

    // 3. DOM body
    const bodyText = await page.evaluate(() => (document.body ? document.body.innerText : ''));
    const hit3 = findInText(bodyText);
    if (hit3) { ran = hit3.ran; failErr = hit3.failErr; source = 'document.body'; break; }

    await new Promise((r) => setTimeout(r, POLL_MS));
  }

  if (!ran || !failErr) {
    console.error(`Timed out after ${TIMEOUT_MS}ms waiting for cljs.test summary.`);
    await browser.close();
    process.exit(1);
  }

  console.log('--- cljs.test summary ---');
  console.log(`(source: ${source})`);
  console.log(ran);
  console.log(failErr);
  console.log('-------------------------');

  const fm = failErr.match(FAIL_RE);
  let exitCode = 0;
  if (fm) {
    const failures = parseInt(fm[1], 10);
    const errors = parseInt(fm[2], 10);
    if (failures > 0 || errors > 0) exitCode = 1;
  } else {
    console.error('Could not parse failures/errors counts; failing the run.');
    exitCode = 1;
  }

  await browser.close();
  process.exit(exitCode);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
