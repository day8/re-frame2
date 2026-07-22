#!/usr/bin/env node
/*
 * Browser proof for the generated Reagent SSR scaffold's DOM ADOPTION.
 *
 * Driven by `emitted_test_run_test.clj`'s `run-ssr-emitted-test!` (behind the
 * `RF2_TEMPLATE_RUN_EMITTED_TESTS` gate). The JVM half has already:
 *   1. generated the SSR scaffold, rewritten its deps to :local/root, linked
 *      node_modules, and `shadow compile app`-built the client bundle into
 *      `<proj>/resources/public/js/main.js`;
 *   2. run the emitted `server/make-handler` once and spit its REAL `/`
 *      response (server-painted `#app` markup + the `__rf_payload` script + the
 *      matching `data-rf-render-hash`) to `<proj>/resources/public/_ssr_proof.html`,
 *      rewriting the `/main.js` bootstrap to `/js/main.js` so a static file
 *      server can serve it.
 *
 * This driver serves that public root over a tiny static HTTP server, loads the
 * server-painted page in Chromium, and proves the scaffold's `init` ADOPTS the
 * server DOM rather than mounting a fresh tree:
 *
 *   - Before the client bundle boots, an init-script MutationObserver stamps
 *     the ORIGINAL server-painted `[data-testid=increment]` node with a JS
 *     expando (`__rfProofServerNode`). React `hydrate-root` reconciles against
 *     the existing markup and REUSES that exact node, so the expando survives.
 *     `create-root` + `render` throws the server markup away and mounts a fresh
 *     node with no expando — which is exactly the regression this proves absent.
 *
 *   - After boot, the live `[data-testid=increment]` node must BE the stamped
 *     server node (adoption), and clicking it must increment
 *     `[data-testid=counter-value]` from 0 to 1 (the handler went live).
 *
 * Flip the scaffold's payload branch back to `create-root` and the
 * node-preservation assertion FAILS (a new node, no expando) — a real tooth,
 * not a string grep.
 *
 * Usage:  node ssr-hydration-dom-proof.cjs <publicRoot> <implRoot>
 *   publicRoot — <proj>/resources/public (holds _ssr_proof.html + js/ + css/)
 *   implRoot   — <repo>/implementation   (resolves the playwright devDep)
 *
 * Exit 0 iff both teeth pass; 1 on failure (with diagnostics on stdout); 2 if
 * Chromium is not launchable here — a REPORT, not a verdict, judged by the
 * caller (hard failure under CI, loud NOT PROVEN banner locally).
 */

const http = require('http');
const fs = require('fs');
const path = require('path');

const [, , PUBLIC_ROOT, IMPL_ROOT] = process.argv;
if (!PUBLIC_ROOT || !IMPL_ROOT) {
  console.error('usage: node ssr-hydration-dom-proof.cjs <publicRoot> <implRoot>');
  process.exit(2);
}

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const PROOF_PAGE = '/_ssr_proof.html';
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
// the SSR proof page, the compiled client bundle, its dev cljs-runtime chunks,
// and the CSS scaffold. Never escapes the root.
function startStaticServer(root) {
  const canonRoot = path.resolve(root);
  const server = http.createServer((req, res) => {
    const urlPath = decodeURIComponent(req.url.split('?')[0]);
    const target = path.resolve(canonRoot, '.' + urlPath);
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

// Runs at document-start, before any page script (i.e. before the end-of-body
// client bundle boots). Observes the document as it parses and stamps the very
// first server-painted increment button with a JS expando the moment it lands.
// React hydration reuses that DOM node; a fresh create-root mount does not.
// Also taps console.error so a cljs ExceptionInfo thrown from init (which
// shadow's loader swallows into a bare `console.error(..., e)`) surfaces its
// real message/stack for diagnosis.
function initScriptStampServerNode() {
  const origError = console.error.bind(console);
  console.error = (...args) => {
    for (const a of args) {
      if (a && (a.message || a.stack)) {
        window.__rfProofLastError = {
          message: String(a.message || ''),
          stack: String(a.stack || ''),
        };
      }
    }
    origError(...args);
  };
  const stamp = (el) => {
    if (el && !el.__rfProofServerNode) {
      el.__rfProofServerNode = true;
      window.__rfProofStampedServerNode = el;
    }
  };
  const scan = () => {
    const el = document.querySelector('#app [data-testid="increment"]');
    if (el) {
      stamp(el);
      return true;
    }
    return false;
  };
  const observer = new MutationObserver(() => {
    if (scan()) observer.disconnect();
  });
  observer.observe(document, { childList: true, subtree: true });
  // Also try immediately in case the node is already present.
  if (scan()) observer.disconnect();
}

(async () => {
  const server = await startStaticServer(PUBLIC_ROOT);
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;
  const consoleLines = [];
  const consoleErrorDetails = [];
  let browser;
  let failure = null;

  // Launch failure is REPORTED, not judged, here: exit 2 means "no launchable
  // browser on this machine" and the caller decides what that is worth. Under
  // CI it is a hard failure — every job that enables this tier runs
  // `npx playwright install --with-deps chromium`, so a job with no browser is
  // broken. Locally it is a skip under a loud NOT PROVEN banner.
  try {
    browser = await chromium.launch({ headless: true });
  } catch (launchErr) {
    server.close();
    console.log(
      'SSR hydration DOM proof SKIPPED: Chromium is not launchable here ' +
        `(${launchErr.message.split('\n')[0]}).`,
    );
    process.exit(2);
  }

  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.addInitScript(initScriptStampServerNode);

    page.on('console', (msg) => {
      consoleLines.push(`[browser:${msg.type()}] ${msg.text()}`);
      // For error-level messages, resolve each argument to its real
      // message/string in-page — a cljs ExceptionInfo prints only its type
      // name via the default console formatter, hiding the error id.
      if (msg.type() === 'error') {
        for (const arg of msg.args()) {
          arg
            .evaluate((e) => (e && e.message ? String(e.message) : String(e)))
            .then((m) => {
              if (m && m !== 'undefined' && m !== 'cljs$core$ExceptionInfo') {
                consoleErrorDetails.push(m);
              }
            })
            .catch(() => {});
        }
      }
    });
    page.on('pageerror', (err) => {
      consoleLines.push(`[browser:pageerror] ${err.message}`);
      consoleErrorDetails.push(err.message);
    });

    await page.goto(baseUrl + PROOF_PAGE, { waitUntil: 'load', timeout: TIMEOUT_MS });

    // The bundle's init runs on load: ssr/hydrate! seeds state, then
    // hydrate-root ADOPTS the server DOM. Wait for the stamped server node to
    // be the live increment button and for the seeded counter to be present.
    try {
      await page.waitForFunction(
        () => {
          const btn = document.querySelector('#app [data-testid="increment"]');
          const val = document.querySelector('#app [data-testid="counter-value"]');
          return !!btn && !!val && val.textContent.trim().length > 0;
        },
        { timeout: TIMEOUT_MS },
      );
    } catch (waitErr) {
      // Give the async console-arg resolution a beat to land, then surface any
      // captured cljs error id/message so the failure is diagnosable.
      await new Promise((r) => setTimeout(r, 200));
      if (consoleErrorDetails.length) {
        throw new Error(
          `client boot did not settle — browser error(s):\n  ${consoleErrorDetails.join('\n  ')}`,
        );
      }
      throw waitErr;
    }

    // -- Tooth 1: the live increment button IS the exact server-painted node --
    const adopted = await page.evaluate(() => {
      const live = document.querySelector('#app [data-testid="increment"]');
      return {
        serverNodePreserved: !!(live && live.__rfProofServerNode === true),
        sameAsStamped: !!(live && live === window.__rfProofStampedServerNode),
        initialCount: (
          document.querySelector('#app [data-testid="counter-value"]').textContent || ''
        ).trim(),
      };
    });
    if (!adopted.serverNodePreserved || !adopted.sameAsStamped) {
      throw new Error(
        `DOM adoption FAILED: the live #app increment button is NOT the ` +
          `server-painted node (serverNodePreserved=${adopted.serverNodePreserved}, ` +
          `sameAsStamped=${adopted.sameAsStamped}). The scaffold mounted a FRESH ` +
          `React tree (create-root) instead of adopting the server DOM (hydrate-root).`,
      );
    }
    if (adopted.initialCount !== '0') {
      throw new Error(
        `expected the hydrated counter to read 0, got '${adopted.initialCount}'`,
      );
    }

    // -- Tooth 2: that adopted node's handler is LIVE (hydration activated it) --
    await page.click('#app [data-testid="increment"]');
    await page.waitForFunction(
      () =>
        (
          document.querySelector('#app [data-testid="counter-value"]').textContent || ''
        ).trim() === '1',
      { timeout: TIMEOUT_MS },
    );
    // Re-confirm the clicked node was still the server node (not swapped mid-run).
    const stillServerNode = await page.evaluate(
      () => document.querySelector('#app [data-testid="increment"]').__rfProofServerNode === true,
    );
    if (!stillServerNode) {
      throw new Error('the incrementing button is no longer the server-painted node');
    }

    await context.close();
  } catch (err) {
    failure = err;
  } finally {
    if (browser) await browser.close();
    server.close();
  }

  if (failure) {
    console.log('=== SSR hydration DOM proof: FAIL ===');
    console.log(failure.message);
    if (failure.stack) console.log(failure.stack);
    if (consoleLines.length) {
      console.log('--- browser console ---');
      for (const l of consoleLines) console.log(l);
    }
    process.exit(1);
  }

  console.log(
    'SSR hydration DOM proof PASS: server-painted #app node adopted (hydrate-root) ' +
      'and its handler went live (0 -> 1).',
  );
  process.exit(0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
