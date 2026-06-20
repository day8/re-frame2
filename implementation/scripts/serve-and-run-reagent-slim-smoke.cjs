#!/usr/bin/env node
'use strict';

/*
 * reagent-slim adapter CLIENT-RUNTIME browser smoke runner (rf2-xsgu8a).
 *
 * Compiles the `:adapters/reagent-slim-testbed` shadow-cljs build, stages
 * the testbed's hand-written index.html next to the compiled main.js,
 * serves it over a loopback-only http-server with per-run ownership-token
 * readiness, then drives the single scenario in
 * implementation/adapters/reagent-slim/testbed/smoke.cjs in headless
 * Chromium. Exit 0 on PASS, 1 on FAIL (an uncaught browser pageerror is
 * fatal even when the visible assertions pass).
 *
 * WHY A DEDICATED RUNNER (not the shared adapter-smoke harness):
 * the slim substrate's behavioural smoke is the day8/reagent-slim adapter's
 * own gate. Folding it into examples/scripts/adapter-smoke-filter.cjs would
 * couple it to that shared Reagent/UIx/Helix manifest + reconcile guard;
 * instead — mirroring how the slim bundle-isolation contract ships its OWN
 * script (check-reagent-slim-bundle-isolation.cjs) rather than the shared
 * check-bundle-isolation.cjs — this gate is self-contained and adapter-
 * owned. npm script: `test:reagent-slim:smoke`.
 *
 * Cross-platform hardening mirrors serve-and-run-xray-feature-gate.cjs:
 * shadow-cljs + http-server are resolved to their JS entry-points and
 * spawned shell-free under THIS node binary (never npx/npx.cmd under a
 * shell — the Windows command-hijack accident class), the server binds
 * 127.0.0.1 only, and a per-run ownership token guards against a
 * stale/foreign listener serving a false-green asset tree.
 */

const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const { isVerboseTests } = require('./lib/browser-test-report.cjs');
const {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  resolveServePort,
  spawnHarnessProcess,
  waitForOwnedHttpReady,
} = require('./lib/local-browser-harness.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const TESTBED_DIR = path.join(IMPL_ROOT, 'adapters', 'reagent-slim', 'testbed');
// The shadow-cljs build (declared in implementation/shadow-cljs.edn)
// compiles the testbed core into this dir; the runner serves it as the
// static root.
const BUILD = 'adapters/reagent-slim-testbed';
const OUT_DIR = path.join(IMPL_ROOT, 'out', 'examples', 'adapter-testbeds', 'reagent-slim');
const HTML_SRC = path.join(TESTBED_DIR, 'index.html');
const SMOKE = path.join(TESTBED_DIR, 'smoke.cjs');

const PREFERRED_PORT = Number(process.env.REAGENT_SLIM_SMOKE_PORT || 8051);
const TIMEOUT_MS = Number(process.env.REAGENT_SLIM_SMOKE_TIMEOUT_MS || 30000);
const READY_TIMEOUT_MS = 30000;
const VERBOSE_TESTS = isVerboseTests();

const { chromium } = require(require.resolve('playwright', { paths: [IMPL_ROOT] }));
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', { paths: [IMPL_ROOT] });
let SHADOW_CLJS_RUNNER;
try {
  SHADOW_CLJS_RUNNER = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
} catch {
  throw new Error(
    `serve-and-run-reagent-slim-smoke: could not resolve shadow-cljs. Run ` +
      `\`npm install\` in ${IMPL_ROOT} first.`,
  );
}

const TOKEN_PATH = path.join(OUT_DIR, TOKEN_FILE_BASENAME);
const cleanup = createHarnessCleanup();

function removeOwnershipToken() {
  try {
    if (fs.existsSync(TOKEN_PATH)) fs.unlinkSync(TOKEN_PATH);
  } catch (_) {
    // best-effort; teardown bookkeeping must never fail the run.
  }
}
cleanup.addCleanup(removeOwnershipToken);
cleanup.installSignalHandlers();

function cleanAndStage() {
  // Clean-stage so no stale file from a previous run can satisfy a
  // browser request the current source no longer produces.
  fs.rmSync(OUT_DIR, { recursive: true, force: true });
}

function compile() {
  const args = [SHADOW_CLJS_RUNNER, 'compile', BUILD];
  const result = spawnSync(process.execPath, args, { cwd: IMPL_ROOT, stdio: 'inherit' });
  if (result.status !== 0) {
    console.error(`> shadow-cljs compile ${BUILD}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

function stageHtml() {
  if (!fs.existsSync(OUT_DIR)) {
    throw new Error(`Build output dir missing after compile: ${OUT_DIR}`);
  }
  if (!fs.existsSync(HTML_SRC)) {
    throw new Error(`Testbed HTML source missing: ${HTML_SRC}`);
  }
  fs.copyFileSync(HTML_SRC, path.join(OUT_DIR, 'index.html'));
}

function writeOwnershipToken() {
  if (!fs.existsSync(OUT_DIR)) return null;
  const token = crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(TOKEN_PATH, token, 'utf8');
  return token;
}

async function runSmoke(baseUrl) {
  const spec = require(SMOKE);
  if (!spec || typeof spec.run !== 'function' || typeof spec.url !== 'string') {
    throw new Error(`Bad smoke module at ${SMOKE}: must export { name, url, run }`);
  }
  const label = spec.name || path.basename(SMOKE);
  const browser = await chromium.launch({ headless: true });
  cleanup.addCleanup(async () => {
    try { await browser.close(); } catch (_) {}
  });

  const consoleLines = [];
  const pageErrors = [];
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('console', (msg) => consoleLines.push(`[browser:${msg.type()}] ${msg.text()}`));
  page.on('pageerror', (err) => pageErrors.push(err.stack || err.message));

  const fullUrl = spec.url.startsWith('http') ? spec.url : baseUrl + spec.url;
  let passed = false;
  let failure = null;
  try {
    await page.goto(fullUrl, { waitUntil: 'load', timeout: TIMEOUT_MS });
    await Promise.race([
      spec.run(page),
      new Promise((_, reject) =>
        setTimeout(() => reject(new Error(`Smoke '${label}' timed out after ${TIMEOUT_MS}ms`)), TIMEOUT_MS),
      ),
    ]);
    // An uncaught browser/runtime exception is fatal even when the visible
    // assertions all passed (rf2-mwx08 pattern): a slim client-runtime
    // regression the smoke happened not to assert on must still flip the
    // verdict.
    if (pageErrors.length > 0) {
      throw new Error(
        `Smoke emitted ${pageErrors.length} uncaught browser pageerror(s) — ` +
          `failing the gate. First: ${pageErrors[0]}`,
      );
    }
    passed = true;
  } catch (err) {
    failure = err;
  } finally {
    await context.close();
    await browser.close();
  }

  if (!passed) {
    console.error(`FAIL ${label}: ${failure.message}`);
    if (failure.stack) console.error(failure.stack);
    if (consoleLines.length > 0) {
      console.error('--- browser console ---');
      for (const line of consoleLines) console.error(`  ${line}`);
      console.error('-----------------------');
    }
    for (const e of pageErrors) console.error(`  [pageerror] ${e}`);
  } else if (VERBOSE_TESTS) {
    for (const line of consoleLines) console.log(line);
    console.log(`PASS ${label}`);
  }

  console.log(`Ran 1 reagent-slim client-runtime smoke. ${passed ? 0 : 1} failures.`);
  return passed ? 0 : 1;
}

async function main() {
  cleanAndStage();
  compile();
  stageHtml();

  const token = writeOwnershipToken();
  if (!token) throw new Error(`Staging dir missing: ${OUT_DIR}. Did staging run?`);

  const port = await resolveServePort(PREFERRED_PORT, {
    onFallback: (preferred, fallback) => {
      console.warn(
        `Preferred port ${preferred} is busy; falling back to free port ${fallback}. ` +
          `Set REAGENT_SLIM_SMOKE_PORT to pin a specific port.`,
      );
    },
  });
  const baseUrl = `http://127.0.0.1:${port}`;
  console.log(`Serving ${OUT_DIR} on ${baseUrl}`);

  const server = cleanup.trackProcess(spawnHarnessProcess(
    process.execPath,
    [HTTP_SERVER_BIN, OUT_DIR, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'],
    { cwd: IMPL_ROOT, stdio: ['ignore', 'inherit', 'inherit'] },
  ));

  let serverDown = false;
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForOwnedHttpReady(port, token, Date.now() + READY_TIMEOUT_MS, {
    isAborted: () => serverDown,
  });
  if (!ready.ok) {
    if (ready.reason === 'child-exited' || serverDown) {
      throw new Error(
        `http-server exited before becoming reachable on :${port}. ` +
          `Likely cause: port already in use or http-server failed to start.`,
      );
    }
    if (ready.reason === 'token-mismatch') {
      throw new Error(
        `A server is reachable on :${port}, but its /${TOKEN_FILE_BASENAME} does not ` +
          `match this run's ownership token (got "${ready.got}", expected "${token}"). ` +
          `Refusing to run the slim smoke against a server this harness did not launch. ` +
          `Set REAGENT_SLIM_SMOKE_PORT to pin a different port if this is intentional.`,
      );
    }
    if (ready.reason === 'token-never-served') {
      throw new Error(
        `http-server on :${port} became reachable but never served ` +
          `/${TOKEN_FILE_BASENAME} within ${READY_TIMEOUT_MS}ms. Staging may be inconsistent.`,
      );
    }
    throw new Error(`http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`);
  }

  return await runSmoke(baseUrl);
}

main()
  .then(async (code) => {
    await cleanup.cleanup();
    process.exit(code);
  })
  .catch(async (err) => {
    console.error(err && err.stack ? err.stack : err);
    await cleanup.cleanup();
    process.exit(1);
  });
