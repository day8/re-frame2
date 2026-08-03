#!/usr/bin/env node
/*
 * Browser smoke runner for the top-level tenant-switcher testbed
 * (testbeds/tenant_switcher/, rf2-5e22yc). The shared framework testbeds
 * under testbeds/** are normally Xray observation targets driven by CLJS
 * unit tests; the tenant-switcher additionally carries its OWN colocated
 * spec.cjs (testbeds are exempt from the examples-test-free rule — per
 * CLAUDE.md "Framework testbeds carry their own non-adapter spec.cjs for
 * cross-cutting surfaces"). This runner is its gate.
 *
 * It mirrors serve-and-run-browser-tests.cjs end-to-end:
 *   1. Compile the :testbeds/tenant-switcher shadow-cljs build, shell-free
 *      under THIS node binary (process.execPath + the resolved shadow-cljs
 *      JS entry-point) — never npx/npx.cmd under a shell (rf2-wn4o1).
 *   2. Stage the hand-written index.html into the build's :output-dir next
 *      to main.js.
 *   3. Serve the output dir over http-server (also shell-free), bound to
 *      127.0.0.1, with the per-run ownership-token handshake (rf2-gkf9).
 *   4. Drive the colocated spec.cjs in headless Chromium: navigate, run its
 *      assertions, and fail on any uncaught pageerror (rf2-mwx08 discipline:
 *      pageerrors are tracked in a dedicated array and flip the verdict).
 *   5. Always tear the server down.
 *
 * Cross-platform: same hardened spawn posture as the implementation/scripts
 * browser launchers; passes _script-spawn-policy.test.cjs.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { enforcePolicy, DEFAULT_OUT_ROOT } = require('./_path-policy.cjs');
const {
  createHarnessCleanup,
  publishOwnershipToken,
  resolveServePort,
  spawnHarnessProcess,
  waitForOwnedHttpReady,
} = require('./lib/local-browser-harness.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const BUILD_ID = ':testbeds/tenant-switcher';
// The build's :output-dir (shadow-cljs.edn) — kept under implementation/out.
const ROOT = enforcePolicy(
  'TENANT_SWITCHER_TESTBED_ROOT',
  path.join(IMPL_ROOT, 'out', 'testbeds', 'tenant-switcher'),
  { allowedRoots: [DEFAULT_OUT_ROOT] },
);
// The hand-written index.html staged into the output dir next to main.js.
const HTML_SRC = path.join(
  REPO_ROOT,
  'testbeds',
  'tenant_switcher',
  'index.html',
);
const SPEC = require(path.join(
  REPO_ROOT,
  'testbeds',
  'tenant_switcher',
  'spec.cjs',
));

const DEFAULT_PORT = 8060; // top-level testbeds band (806x); see shadow-cljs.edn :dev-http.
const READY_TIMEOUT_MS = 30000;
const SPEC_TIMEOUT_MS = parseInt(process.env.TESTBED_SPEC_TIMEOUT_MS || '30000', 10);
const POLL_MS = 200;

// Resolve the shadow-cljs + http-server JS entry-points so they can be
// spawned shell-free under process.execPath (rf2-wn4o1). Rooted at IMPL_ROOT
// regardless of this script's cwd.
let SHADOW_CLJS_RUNNER;
let HTTP_SERVER_BIN;
try {
  SHADOW_CLJS_RUNNER = require.resolve('shadow-cljs/cli/runner.js', {
    paths: [IMPL_ROOT],
  });
  HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
    paths: [IMPL_ROOT],
  });
} catch {
  console.error(
    'serve-and-run-tenant-switcher-testbed: could not resolve shadow-cljs / ' +
      `http-server. Run \`npm install\` in ${IMPL_ROOT} first.`,
  );
  process.exit(1);
}

const { spawnSync } = require('child_process');
const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));

const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

function compile() {
  // Spawn the resolved shadow-cljs runner under THIS node binary, shell-free.
  const result = spawnSync(
    process.execPath,
    [SHADOW_CLJS_RUNNER, 'compile', BUILD_ID],
    { cwd: IMPL_ROOT, stdio: 'inherit' },
  );
  if (result.status !== 0) {
    console.error(`shadow-cljs compile ${BUILD_ID} failed (exit ${result.status})`);
    return false;
  }
  return true;
}

function stageHtml() {
  if (!fs.existsSync(ROOT)) {
    console.error(`Build output dir missing: ${ROOT}. Did the compile run?`);
    return false;
  }
  if (!fs.existsSync(HTML_SRC)) {
    console.error(`HTML source missing: ${HTML_SRC}`);
    return false;
  }
  fs.copyFileSync(HTML_SRC, path.join(ROOT, 'index.html'));
  return true;
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

async function runSpec(baseUrl) {
  const browser = await chromium.launch({ headless: true });
  // rf2-mwx08 discipline: pageerrors are tracked in a DEDICATED array (not
  // merely the diagnostic buffer) and flip the verdict to fail, even if the
  // assertions otherwise pass.
  const pageErrors = [];
  const lines = [];
  const log = (s) => lines.push(s);
  let passed = false;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('console', (msg) => log(`[browser:${msg.type()}] ${msg.text()}`));
    page.on('pageerror', (err) => {
      pageErrors.push(err);
      log(`[browser:pageerror] ${err.message}`);
      if (err.stack) log(err.stack);
    });

    const fullUrl = SPEC.url.startsWith('http') ? SPEC.url : baseUrl + SPEC.url;
    log(`\n=== ${SPEC.name} ===`);
    log(`Navigating to ${fullUrl}`);
    await page.goto(fullUrl, { waitUntil: 'load', timeout: SPEC_TIMEOUT_MS });
    await withTimeout(SPEC.run(page), SPEC_TIMEOUT_MS, SPEC.name);
    if (pageErrors.length > 0) {
      throw new Error(`Page emitted ${pageErrors.length} uncaught error(s); first: ${pageErrors[0].message}`);
    }
    passed = true;
  } catch (err) {
    log(`FAIL ${SPEC.name}: ${err && err.message ? err.message : err}`);
    if (err && err.stack) log(err.stack);
  } finally {
    await browser.close();
  }
  if (!passed) for (const ln of lines) console.log(ln);
  console.log(passed ? `PASS  ${SPEC.name}` : `FAIL  ${SPEC.name}`);
  return passed ? 0 : 1;
}

(async () => {
  if (!compile()) return 1;
  if (!stageHtml()) return 1;

  const published = publishOwnershipToken(ROOT);
  if (!published) {
    console.error(`Asset root missing: ${ROOT}. Did shadow-cljs compile run?`);
    return 1;
  }
  const { token, remove } = published;
  cleanup.addCleanup(remove);

  const port = await resolveServePort(DEFAULT_PORT, {
    onFallback: (pref, fallback) =>
      console.warn(`Port ${pref} busy; falling back to free port ${fallback}.`),
  });
  console.log(`Serving ${ROOT} on http://127.0.0.1:${port}`);

  const args = [HTTP_SERVER_BIN, ROOT, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'];
  const server = cleanup.trackProcess(
    spawnHarnessProcess(process.execPath, args, {
      cwd: IMPL_ROOT,
      stdio: ['ignore', 'inherit', 'inherit'],
    }),
  );
  const state = { exited: false, exitCode: null, exitSignal: null };
  server.on('exit', (code, signal) => {
    state.exited = true;
    state.exitCode = code;
    state.exitSignal = signal;
  });

  const ready = await waitForOwnedHttpReady(port, token, Date.now() + READY_TIMEOUT_MS, {
    pollMs: POLL_MS,
    isAborted: () => state.exited,
  });
  if (!ready.ok) {
    if (ready.reason === 'child-exited' || state.exited) {
      console.error(
        `http-server exited before becoming reachable on :${port} ` +
          `(code=${state.exitCode}, signal=${state.exitSignal}).`,
      );
    } else if (ready.reason === 'token-mismatch') {
      console.error(
        `A foreign server is reachable on :${port} (token mismatch). Refusing to run.`,
      );
    } else {
      console.error(`http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`);
    }
    return 1;
  }

  return await runSpec(`http://127.0.0.1:${port}`);
})()
  .then(async (code) => {
    await cleanup.cleanup();
    process.exit(code == null ? 1 : code);
  })
  .catch(async (err) => {
    console.error(err);
    await cleanup.cleanup();
    process.exit(1);
  });
