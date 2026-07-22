#!/usr/bin/env node
/*
 * Browser proof that the generated scaffold's EMITTED `index.html` boots the
 * DEV bundle — the page a newcomer actually opens after
 * `npx shadow-cljs watch app`.
 *
 * Driven by `emitted_test_run_test.clj` (behind `RF2_TEMPLATE_RUN_EMITTED_TESTS`),
 * as a sibling of `ssr-hydration-dom-proof.cjs`. The JVM half has already
 * generated the scaffold, rewritten its deps to :local/root, linked
 * node_modules, and run `shadow compile app` — so `<proj>/resources/public`
 * holds the emitted `index.html`, `css/app.css`, and the DEV (`:optimizations
 * :none`) bundle at `js/main.js` + `js/cljs-runtime/`.
 *
 * WHY THIS EXISTS. Until this proof, no gate anywhere loaded the emitted
 * `index.html` in a browser. Everything upstream compiles through the pure-JVM
 * route, and the only browser proof drives a SYNTHETIC `_ssr_proof.html`
 * rendered by the SSR server — a page that never carries the emitted
 * `index.html`'s `<meta http-equiv="Content-Security-Policy">`. So a CSP that
 * blocks the dev bundle outright shipped green: shadow's `:none` builds load
 * every namespace through `goog.globalEval`, and without `'unsafe-eval'` in
 * `script-src` the documented first run is a BLANK PAGE on every variant.
 * That is the defect this driver exists to catch, and it can only be caught by
 * loading the REAL emitted page.
 *
 * The three teeth, in order:
 *
 *   1. MOUNT — `#app` must paint the scaffold's counter (an `<h1>` with the
 *      project name, a `<button>`, and the counter `<span>`). A CSP that
 *      blocks `eval`, a broken `:init-fn`, or a namespace that throws on load
 *      all leave `#app` empty; this is the blank-first-page tooth.
 *   2. LIVE — clicking the `+1` button must move the counter 0 -> 1, so the
 *      dataflow (event -> app-db -> subscription -> re-render) is actually
 *      wired, not just server-shaped markup.
 *   3. CLEAN — ZERO uncaught `pageerror`s. Console noise stays diagnostic
 *      (a 404 favicon, a dev-only warn); only `pageerror` is fatal, matching
 *      every adjacent runner's policy in this repo.
 *
 * The static server sets NO Content-Security-Policy response header — the
 * emitted `<meta>` CSP is the only policy in play, which is exactly the
 * surface under test.
 *
 * Usage:  node dev-page-boot-proof.cjs <publicRoot> <implRoot> [label]
 *   publicRoot — <proj>/resources/public (holds index.html + js/ + css/)
 *   implRoot   — <repo>/implementation   (resolves the playwright devDep)
 *   label      — optional variant name, echoed in diagnostics
 *
 * Exit 0 = all three teeth pass. Exit 1 = the proof FAILED. Exit 2 = Chromium
 * is not launchable here, so the proof was SKIPPED (the caller prints a loud
 * banner — a silent skip is how this whole class of defect stayed invisible).
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const [, , PUBLIC_ROOT, IMPL_ROOT, LABEL] = process.argv;
if (!PUBLIC_ROOT || !IMPL_ROOT) {
  console.error('usage: node dev-page-boot-proof.cjs <publicRoot> <implRoot> [label]');
  process.exit(2);
}
const label = LABEL || 'emitted scaffold';

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const BOOT_PAGE = '/index.html';
const TIMEOUT_MS = parseInt(process.env.RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS || '30000', 10);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.ico': 'image/x-icon',
};

// A tiny contained static file server rooted at PUBLIC_ROOT — enough to serve
// the emitted index.html, the dev bundle, its cljs-runtime chunks and the CSS.
// Never escapes the root, and deliberately sets no CSP header so the emitted
// page's own <meta> policy is the one under test.
function startStaticServer(root) {
  const canonRoot = path.resolve(root);
  const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent(req.url.split('?')[0]);
    const rel = urlPath === '/' ? '/index.html' : urlPath;
    const target = path.resolve(canonRoot, '.' + rel);
    if (target !== canonRoot && !target.startsWith(canonRoot + path.sep)) {
      res.statusCode = 403;
      res.end('forbidden');
      return;
    }
    fs.readFile(target, (err, buf) => {
      if (err) {
        res.statusCode = 404;
        res.end('not found: ' + urlPath);
        return;
      }
      res.setHeader('Content-Type', MIME[path.extname(target)] || 'application/octet-stream');
      res.end(buf);
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

// Turn the captured browser noise into a POINTED diagnosis where we can. A
// bare "the counter never appeared" is a true but useless verdict; naming the
// CSP as the blocker is what makes this gate worth having.
function diagnose(lines) {
  const blob = lines.join('\n');
  const hints = [];
  if (/Content Security Policy|EvalError|unsafe-eval|refused to evaluate/i.test(blob)) {
    hints.push(
      "the emitted index.html's <meta> Content-Security-Policy BLOCKS the dev " +
        "bundle: shadow-cljs :none builds load every namespace through " +
        "goog.globalEval, so `script-src` must admit 'unsafe-eval' or the " +
        'documented first run is a blank page.',
    );
  }
  if (/Refused to load|violates the following Content Security Policy/i.test(blob)) {
    hints.push(
      'a resource the emitted page needs was refused by its own <meta> CSP — ' +
        'widen the offending directive or drop the resource.',
    );
  }
  if (/Failed to load resource.*(main\.js|cljs-runtime)/i.test(blob)) {
    hints.push(
      'the dev bundle (js/main.js or a js/cljs-runtime chunk) did not load — ' +
        'check the emitted :output-dir / :asset-path against the index.html ' +
        '<script src>.',
    );
  }
  return hints;
}

(async () => {
  const server = await startStaticServer(PUBLIC_ROOT);
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  const consoleLines = [];
  // Tracked SEPARATELY from the diagnostic console buffer: the verdict below
  // flips to FAIL on a non-empty pageErrors array. Console noise is
  // diagnostic-only (a favicon 404 must not red the gate); an uncaught
  // pageerror is fatal.
  const pageErrors = [];
  let browser;
  let failure = null;

  // Launch is a SKIP boundary, not a failure: the PR-time template job runs
  // `npm ci` in implementation/ (which installs the playwright PACKAGE) but
  // never `npx playwright install`, so no browser binary is present there.
  // Exit 2 = "browser unavailable, skipped"; the caller prints a loud banner so
  // the skip cannot read as a pass.
  try {
    browser = await chromium.launch({ headless: true });
  } catch (launchErr) {
    server.close();
    console.log(
      `dev-page boot proof SKIPPED (${label}): Chromium is not launchable here ` +
        `(${launchErr.message.split('\n')[0]}).`,
    );
    process.exit(2);
  }

  try {
    const context = await browser.newContext();
    const page = await context.newPage();

    page.on('console', (msg) => {
      consoleLines.push(`[browser:${msg.type()}] ${msg.text()}`);
    });
    page.on('pageerror', (err) => {
      consoleLines.push(`[browser:pageerror] ${err.message}`);
      pageErrors.push(err.message);
    });

    await page.goto(baseUrl + BOOT_PAGE, { waitUntil: 'load', timeout: TIMEOUT_MS });

    // -- Tooth 1: the dev bundle booted and PAINTED the counter into #app ----
    try {
      await page.waitForFunction(
        () => {
          const h1 = document.querySelector('#app h1');
          const btn = document.querySelector('#app button');
          const val = document.querySelector('#app span');
          return (
            !!h1 &&
            !!btn &&
            !!val &&
            h1.textContent.trim().length > 0 &&
            val.textContent.trim().length > 0
          );
        },
        { timeout: TIMEOUT_MS },
      );
    } catch (waitErr) {
      const appHtml = await page
        .evaluate(() => {
          const app = document.querySelector('#app');
          return app ? app.innerHTML : '<no #app element>';
        })
        .catch(() => '<unreadable>');
      const hints = diagnose(consoleLines.concat(pageErrors));
      throw new Error(
        `the emitted dev page did NOT mount: #app never painted the scaffold ` +
          `counter (BLANK FIRST PAGE). #app innerHTML was ${JSON.stringify(appHtml)}.` +
          (hints.length ? `\nLikely cause:\n  - ${hints.join('\n  - ')}` : '') +
          `\n(underlying wait: ${waitErr.message.split('\n')[0]})`,
      );
    }

    // -- Tooth 2: the dataflow is LIVE — clicking +1 moves the counter -------
    const initial = await page.evaluate(() =>
      (document.querySelector('#app span').textContent || '').trim(),
    );
    if (initial !== '0') {
      throw new Error(`expected the seeded counter to read 0, got '${initial}'`);
    }
    await page.click('#app button');
    await page.waitForFunction(
      () => (document.querySelector('#app span').textContent || '').trim() === '1',
      { timeout: TIMEOUT_MS },
    );

    await context.close();
  } catch (err) {
    failure = err;
  } finally {
    if (browser) await browser.close();
    server.close();
  }

  // -- Tooth 3: ZERO uncaught pageerrors on the emitted dev page ------------
  if (!failure && pageErrors.length) {
    const hints = diagnose(consoleLines.concat(pageErrors));
    failure = new Error(
      `the emitted dev page mounted but raised ${pageErrors.length} uncaught ` +
        `pageerror(s):\n  ${pageErrors.join('\n  ')}` +
        (hints.length ? `\nLikely cause:\n  - ${hints.join('\n  - ')}` : ''),
    );
  }

  if (failure) {
    console.log(`=== dev-page boot proof (${label}): FAIL ===`);
    console.log(failure.message);
    if (consoleLines.length) {
      console.log('--- browser console ---');
      for (const l of consoleLines) console.log(l);
    }
    process.exit(1);
  }

  console.log(
    `dev-page boot proof PASS (${label}): the emitted index.html booted the dev ` +
      'bundle, #app painted the counter, the click moved it 0 -> 1, and Chromium ' +
      'reported zero uncaught pageerrors.',
  );
  process.exit(0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
