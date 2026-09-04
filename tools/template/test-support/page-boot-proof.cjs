#!/usr/bin/env node
/*
 * Browser proof that the generated scaffold's EMITTED `index.html` BOOTS — run
 * twice per substrate, once over each bundle the scaffold's own commands
 * produce:
 *
 *   * `dev`     — `:optimizations :none`, the page a newcomer opens after
 *                 `npx shadow-cljs watch app`.
 *   * `release` — `:advanced`, the page a newcomer DEPLOYS after
 *                 `npm run release`. Closure renames every un-inferrable
 *                 property access and eliminates code behind `goog.DEBUG`, so
 *                 an interop defect that is invisible in dev appears only
 *                 here.
 *
 * Driven by `emitted_test_run_test.clj` (behind `RF2_TEMPLATE_RUN_EMITTED_TESTS`),
 * which runs it for the Reagent variant, the UIx variant, and the setup
 * skill's shipped leaf scaffold. The JVM half has already generated the
 * scaffold, rewritten its deps to :local/root, linked node_modules, and built
 * the bundle under test into `<proj>/resources/public` beside the emitted
 * `index.html` and `css/app.css`.
 *
 * WHY THIS EXISTS. Every other gate over the generated app stops at the
 * pure-JVM route: it compiles, so it is presumed to boot. But a scaffold can
 * compile clean and still hand a newcomer a BLANK PAGE — an `:output-dir` /
 * `:asset-path` that disagrees with the `<script src>`, a `:init-fn` that never
 * fires, a namespace that throws on load. Nothing but loading the REAL emitted
 * page in a REAL browser catches that class, which is what this driver does.
 *
 * The four teeth, in order:
 *
 *   1. MOUNT — `#app` must paint the scaffold's counter (an `<h1>` with the
 *      project name, a `<button>`, and the counter `<span>`). A bundle that
 *      does not load, a broken `:init-fn`, or a namespace that throws on load
 *      all leave `#app` empty; this is the blank-first-page tooth.
 *   2. LIVE — clicking the `+1` button must move the counter 0 -> 1, so the
 *      dataflow (event -> app-db -> subscription -> re-render) is actually
 *      wired, not just static markup.
 *   3. BUNDLE SHAPE — the page must have run the bundle the caller SAID it
 *      built. See below; this is what keeps the two verdicts apart.
 *   4. CLEAN — ZERO uncaught `pageerror`s. Console noise stays diagnostic
 *      (a 404 favicon, a dev-only warn); only `pageerror` is fatal, matching
 *      every adjacent runner's policy in this repo.
 *
 * TOOTH 3, AND WHY IT IS NOT DECORATION. Both proofs load the same URL out of
 * the same directory; the release build simply overwrites `js/main.js` in
 * place. So without a positive check, a release build that silently failed to
 * replace the dev bundle would be certified by a browser verdict the DEV
 * bundle earned — the exact substitution this file's two modes exist to
 * prevent. The check reads two globals whose presence IS the un-optimised
 * bundle: `goog` (the Closure runtime namespace) and `cljs` (the
 * ClojureScript one). `:advanced` renames both away. Measured on a real
 * emitted UIx scaffold, in both directions:
 *
 *     bundle   typeof goog   typeof cljs   js/main.js
 *     dev      "object"      "object"      6,311,996 bytes
 *     release  "undefined"   "undefined"      620,222 bytes
 *
 * (The byte counts drift by a few between runs — the build bakes in its own
 * temp path — so it is the ORDER OF MAGNITUDE that is the signal, and the
 * JVM side asserts only that the two differ, never a figure.)
 *
 * Each mode's assertion therefore reads the OPPOSITE way on the other mode's
 * artefact, which is what makes both non-vacuous without a third fixture.
 *
 * NOT a discriminator, and measured so rather than assumed: NETWORK REQUESTS.
 * The dev `:browser` bundle produced by `shadow-cljs compile` is
 * self-contained (`CLOSURE_NO_DEPS = true`), so it fetches ZERO
 * `js/cljs-runtime/` chunks — exactly like the release bundle. Both modes
 * request precisely `index.html`, `css/app.css` and `js/main.js`.
 *
 * NO CONTENT-SECURITY-POLICY IS IN PLAY, by design on both ends: the emitted
 * `index.html` carries no `<meta http-equiv="Content-Security-Policy">` (both
 * `template_test.clj` and the setup skill's drift lock forbid one), and the
 * static server below sets no CSP response header. A CSP recipe added later
 * brings its own policy-bearing fixture and its own diagnostics — this driver
 * deliberately keeps none for a policy no page under it can have.
 *
 * Usage:  node page-boot-proof.cjs <publicRoot> <implRoot> [label]
 *   publicRoot — <proj>/resources/public (holds index.html + js/ + css/)
 *   implRoot   — <repo>/implementation   (resolves the playwright devDep)
 *   label      — optional variant name, echoed in diagnostics
 *
 * Env:
 *   RF2_TEMPLATE_BUNDLE_MODE            `dev` (default) or `release`
 *   RF2_TEMPLATE_EXPECT_H1              pin the exact <h1> text
 *   RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS
 *
 * Exit 0 = all four teeth pass. Exit 1 = the proof FAILED. Exit 2 = Chromium
 * is not launchable here. Exit 2 is a REPORT, not a verdict: the caller
 * decides. Under CI it fails the run (every job enabling this tier provisions
 * a browser, so a missing one is a broken job); locally it is a skip under a
 * loud NOT PROVEN banner. A silent skip is how this class of defect stayed
 * invisible.
 */

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');

const [, , PUBLIC_ROOT, IMPL_ROOT, LABEL] = process.argv;
if (!PUBLIC_ROOT || !IMPL_ROOT) {
  console.error('usage: node page-boot-proof.cjs <publicRoot> <implRoot> [label]');
  process.exit(2);
}
const label = LABEL || 'emitted scaffold';

// Which bundle the caller says it built. Defaults to `dev` — the historical
// behaviour and the only mode a hand invocation is likely to want — but an
// unrecognised value is a harness error and fails closed rather than silently
// proving the wrong thing.
const MODE = (process.env.RF2_TEMPLATE_BUNDLE_MODE || 'dev').trim().toLowerCase();

// typeof for two globals the un-optimised bundle defines and `:advanced`
// renames away. See "TOOTH 3" above for the measurements.
const BUNDLE_SHAPES = {
  dev: { goog: 'object', cljs: 'object' },
  release: { goog: 'undefined', cljs: 'undefined' },
};
const BUNDLE_NOUN = { dev: 'development', release: 'optimized (:advanced) release' };

if (!Object.prototype.hasOwnProperty.call(BUNDLE_SHAPES, MODE)) {
  console.log(
    `page boot proof CONFIGURATION ERROR (${label}): RF2_TEMPLATE_BUNDLE_MODE ` +
      `must be one of ${JSON.stringify(Object.keys(BUNDLE_SHAPES))}, got ` +
      `${JSON.stringify(MODE)}.`,
  );
  process.exit(1);
}

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const BOOT_PAGE = '/index.html';
const TIMEOUT_MS = parseInt(process.env.RF2_TEMPLATE_BROWSER_PROOF_TIMEOUT_MS || '30000', 10);
// Optional: the exact <h1> text the mounted page must show (unset = any non-empty heading).
const EXPECT_H1 = process.env.RF2_TEMPLATE_EXPECT_H1 || '';

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
// the emitted index.html, the bundle under test, any cljs-runtime chunks and
// the CSS. Never escapes the root, and sets no security headers of its own:
// the page under test is served exactly as emitted.
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
// unloadable bundle is what makes this gate worth having.
function diagnose(lines) {
  const blob = lines.join('\n');
  const hints = [];
  if (/Failed to load resource.*(main\.js|cljs-runtime)/i.test(blob)) {
    hints.push(
      'the bundle (js/main.js or a js/cljs-runtime chunk) did not load — ' +
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
  let observedShape = null;

  // Launch failure is REPORTED, not judged, here: exit 2 means "no launchable
  // browser on this machine" and the caller decides what that is worth. Under
  // CI it is a hard failure — every job that enables this tier runs
  // `npx playwright install --with-deps chromium`, so a job with no browser is
  // broken. Locally it is a skip under a loud NOT PROVEN banner. Keeping the
  // judgement in the caller is what let the CI half be tightened without
  // making a browser-less local checkout unrunnable.
  try {
    browser = await chromium.launch({ headless: true });
  } catch (launchErr) {
    server.close();
    console.log(
      `page boot proof SKIPPED (${label}, ${MODE} bundle): Chromium is not ` +
        `launchable here (${launchErr.message.split('\n')[0]}).`,
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

    // -- Tooth 1: the bundle booted and PAINTED the counter into #app --------
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
        `the emitted ${BUNDLE_NOUN[MODE]} page did NOT mount: #app never painted ` +
          `the scaffold counter (BLANK FIRST PAGE). #app innerHTML was ` +
          `${JSON.stringify(appHtml)}.` +
          (hints.length ? `\nLikely cause:\n  - ${hints.join('\n  - ')}` : '') +
          `\n(underlying wait: ${waitErr.message.split('\n')[0]})`,
      );
    }

    // -- Tooth 1b (optional): the heading TEXT is the one the caller expects.
    // Set RF2_TEMPLATE_EXPECT_H1 to pin it — a match is positive evidence that
    // the source under test is what the browser actually rendered.
    if (EXPECT_H1) {
      const h1 = await page.evaluate(() =>
        (document.querySelector('#app h1').textContent || '').trim(),
      );
      if (h1 !== EXPECT_H1) {
        throw new Error(`expected the heading to read '${EXPECT_H1}', got '${h1}'`);
      }
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

    // -- Tooth 3: the page ran the bundle the caller SAID it built -----------
    observedShape = await page.evaluate(() => ({
      goog: typeof window.goog,
      cljs: typeof window.cljs,
    }));
    const expectedShape = BUNDLE_SHAPES[MODE];
    const mismatched = Object.keys(expectedShape).filter(
      (k) => observedShape[k] !== expectedShape[k],
    );
    if (mismatched.length) {
      const other = MODE === 'dev' ? 'release' : 'dev';
      throw new Error(
        `the page booted, but it did NOT run the ${BUNDLE_NOUN[MODE]} bundle: ` +
          `expected ${JSON.stringify(expectedShape)}, observed ` +
          `${JSON.stringify(observedShape)} (differs on ${JSON.stringify(mismatched)}). ` +
          `That shape is the ${BUNDLE_NOUN[other]} one — js/main.js under ` +
          `${PUBLIC_ROOT} is not the artefact this proof was asked about, so a ` +
          `PASS here would have credited one bundle with the other's verdict.`,
      );
    }

    await context.close();
  } catch (err) {
    failure = err;
  } finally {
    if (browser) await browser.close();
    server.close();
  }

  // -- Tooth 4: ZERO uncaught pageerrors on the emitted page ----------------
  if (!failure && pageErrors.length) {
    const hints = diagnose(consoleLines.concat(pageErrors));
    failure = new Error(
      `the emitted ${BUNDLE_NOUN[MODE]} page mounted but raised ` +
        `${pageErrors.length} uncaught pageerror(s):\n  ${pageErrors.join('\n  ')}` +
        (hints.length ? `\nLikely cause:\n  - ${hints.join('\n  - ')}` : ''),
    );
  }

  if (failure) {
    console.log(`=== page boot proof (${label}, ${MODE} bundle): FAIL ===`);
    console.log(failure.message);
    if (consoleLines.length) {
      console.log('--- browser console ---');
      for (const l of consoleLines) console.log(l);
    }
    process.exit(1);
  }

  console.log(
    `page boot proof PASS (${label}, ${MODE} bundle): the emitted index.html ` +
      `booted the ${BUNDLE_NOUN[MODE]} bundle (${JSON.stringify(observedShape)}), ` +
      '#app painted the counter, the click moved it 0 -> 1, and Chromium ' +
      'reported zero uncaught pageerrors.',
  );
  process.exit(0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
