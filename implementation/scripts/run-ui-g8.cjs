#!/usr/bin/env node
'use strict';

// G-8 — the real-browser controlled-input correctness gate (07 §5; EP-0035
// widened G-8). S-5's evidence was jsdom-only; this runner serves one un-
// optimized :ui-g8 bundle and drives the SAME reusable-event-prefix fixture in
// real Chromium AND real WebKit. Both engines must pass the matrix (pre-paint
// synchronous commit, single revision advance, ordering, caret restoration, IME
// composition) under the sync door AND show the deliberate async-door regression
// FAIL the pre-paint + revision arms (the tooth). The runner's own assertions
// carry mutation teeth so a vacuous check cannot pass.

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { isDeepStrictEqual } = require('util');
const playwright = require('playwright');
const {
  createHarnessCleanup,
  resolveServePort,
  startLocalHttpServer,
} = require('./lib/local-browser-harness.cjs');

const IMPL = path.resolve(__dirname, '..');
const OUT = path.join(IMPL, 'out');
const DEV = path.join(OUT, 'ui-g8');
const REPORT = path.join(OUT, 'ui-g8.json');
const TIMEOUT = 90000;

// The two real engines. Chromium AND WebKit are both mandatory (EP-0035): the
// IME composition, caret-on-restore, and paint scheduling under test differ by
// engine, and jsdom fakes none of them.
const ENGINES = ['chromium', 'webkit'];

function fail(message) {
  throw new Error(`G-8 FAIL: ${message}`);
}

function resolveBin(modulePath) {
  return require.resolve(modulePath, { paths: [IMPL] });
}

function shadow(...args) {
  const runner = resolveBin('shadow-cljs/cli/runner.js');
  console.log(`> shadow-cljs ${args.join(' ')}`);
  const result = spawnSync(process.execPath, [runner, ...args], {
    cwd: IMPL, env: process.env, shell: false, stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) fail(`shadow-cljs ${args.join(' ')} exited ${result.status}`);
}

function writePage(dir) {
  fs.writeFileSync(
    path.join(dir, 'index.html'),
    '<!doctype html><meta charset="utf-8"><div id="app"></div>' +
      '<script src="./main.js"></script>\n',
    'utf8',
  );
}

// ---- the correctness contract -------------------------------------------------

// The exact matrix outcome the sync door must produce, and the exact tooth the
// async door must fail. Split into named checks so a mutation of any single
// field is caught by exactly one assertion.
function assertSyncMatrix(engine, sync) {
  if (sync['pre-paint-committed'] !== true) {
    fail(`[${engine}] sync door did not commit app-db before paint (pre-paint arm)`);
  }
  if (sync['revision-delta'] !== 1) {
    fail(`[${engine}] sync keystroke advanced the ViewCell ${sync['revision-delta']} times, not exactly once (tearing arm)`);
  }
  if (sync['pending-drained'] !== true) {
    fail(`[${engine}] sync door left the dirty registry undrained inside dispatch`);
  }
  if (!isDeepStrictEqual(sync.ordering, ['o1', 'o2', 'o3'])) {
    fail(`[${engine}] queued keystrokes lost order: ${JSON.stringify(sync.ordering)}`);
  }
  if (sync['committed-value'] !== 'o3' || sync['dom-value'] !== 'o3') {
    fail(`[${engine}] controlled value did not round-trip: committed=${sync['committed-value']} dom=${sync['dom-value']}`);
  }
  if (sync['caret-value'] !== 'abXc' || sync['caret-pos'] !== 3) {
    fail(`[${engine}] caret not restored to the insertion point: value=${sync['caret-value']} pos=${sync['caret-pos']}`);
  }
  if (sync['ime-committed'] !== 'が' || sync['ime-dom'] !== 'が') {
    fail(`[${engine}] IME composition did not commit the composed value: committed=${sync['ime-committed']} dom=${sync['ime-dom']}`);
  }
  if (sync['ime-caret'] !== 1) {
    fail(`[${engine}] IME caret not preserved: ${sync['ime-caret']}`);
  }
  if (!(sync['ime-revision-delta'] >= 1)) {
    fail(`[${engine}] IME composition did not commit through the door (revision-delta ${sync['ime-revision-delta']})`);
  }
}

// The tooth: under the deliberate async door the SAME keystroke must NOT commit
// before paint and must NOT advance the cell synchronously. If it does, the
// matrix is vacuous — it cannot tell a sync door from an async one — and the
// gate is red.
function assertAsyncTooth(engine, async) {
  if (async['pre-paint-committed'] !== false) {
    fail(`[${engine}] async-door regression committed before paint — the matrix cannot detect a broken door (tooth did not bite)`);
  }
  if (async['revision-delta'] !== 0) {
    fail(`[${engine}] async-door regression advanced the cell synchronously (${async['revision-delta']}) — tooth did not bite`);
  }
}

function assertEngineResult(engine, result) {
  if (!result || result.gate !== 'G-8' || result.status !== 'pass') {
    fail(`[${engine}] invalid result: ${JSON.stringify(result)}`);
  }
  assertSyncMatrix(engine, result.sync);
  assertAsyncTooth(engine, result.async);
}

// ---- mutation teeth: prove the runner's own checks bite -----------------------

function expectRejected(label, thunk) {
  try { thunk(); } catch (_) { return label; }
  fail(`runner assertion tooth was green: ${label}`);
}

function goldenResult() {
  return {
    gate: 'G-8', status: 'pass', 'user-agent': 'golden',
    sync: {
      'pre-paint-committed': true, 'revision-delta': 1, 'pending-drained': true,
      ordering: ['o1', 'o2', 'o3'], 'committed-value': 'o3', 'dom-value': 'o3',
      'caret-value': 'abXc', 'caret-pos': 3,
      'ime-committed': 'が', 'ime-dom': 'が', 'ime-caret': 1, 'ime-revision-delta': 1,
    },
    async: { 'pre-paint-committed': false, 'revision-delta': 0, eventual: 'abc' },
  };
}

function runMutationTeeth() {
  const mutate = (f) => { const r = JSON.parse(JSON.stringify(goldenResult())); f(r); return r; };
  return [
    expectRejected('sync pre-paint regressed', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['pre-paint-committed'] = false; }))),
    expectRejected('sync torn keystroke (2 advances)', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['revision-delta'] = 2; }))),
    expectRejected('sync ordering scrambled', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync.ordering = ['o1', 'o3', 'o2']; }))),
    expectRejected('sync caret jumped to end', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['caret-pos'] = 4; }))),
    expectRejected('sync IME value wrong', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ime-committed'] = 'x'; }))),
    expectRejected('async door accidentally committed pre-paint', () =>
      assertEngineResult('mutant', mutate((r) => { r.async['pre-paint-committed'] = true; }))),
    expectRejected('async door advanced the cell synchronously', () =>
      assertEngineResult('mutant', mutate((r) => { r.async['revision-delta'] = 1; }))),
  ];
}

function writeReport(report) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(REPORT, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
}

function appendSummary(report) {
  const target = process.env.GITHUB_STEP_SUMMARY;
  if (!target || report.status !== 'pass') return;
  const lines = ['### G-8 controlled-input correctness (real Chromium + WebKit)', '',
    '| engine | pre-paint | 1-advance | ordering | caret | IME | async tooth |',
    '|---|---|---|---|---|---|---|'];
  for (const e of ENGINES) {
    lines.push(`| ${e} | ✓ | ✓ | ✓ | ✓ | ✓ | bit |`);
  }
  fs.appendFileSync(target, `${lines.join('\n')}\n`, 'utf8');
}

async function driveEngine(name, url) {
  const browserType = playwright[name];
  const browser = await browserType.launch({ headless: true });
  try {
    const page = await browser.newPage();
    const pageErrors = [];
    page.on('pageerror', (e) => pageErrors.push(e.stack || String(e)));
    await page.goto(url);
    await page.waitForFunction(
      () => globalThis.__RF2_G8_RESULT__ || globalThis.__RF2_G8_ERROR__,
      null, { timeout: TIMEOUT });
    const state = await page.evaluate(() => ({
      result: globalThis.__RF2_G8_RESULT__ || null,
      error: globalThis.__RF2_G8_ERROR__ || null,
    }));
    if (state.error) fail(`[${name}] fixture error: ${state.error}`);
    if (pageErrors.length) fail(`[${name}] page errors:\n${pageErrors.join('\n')}`);
    assertEngineResult(name, state.result);
    return state.result;
  } finally {
    await browser.close();
  }
}

async function main() {
  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();
  let tearingDown = false;
  try {
    // The runner's own checks must bite BEFORE any browser runs — a vacuous
    // matrix assertion is a worse failure than a red gate.
    const mutationTeeth = runMutationTeeth();

    shadow('compile', 'ui-g8');
    writePage(DEV);

    const port = await resolveServePort(Number(process.env.UI_G8_PORT) || 8064);
    const httpServerBin = resolveBin('http-server/bin/http-server');
    const server = await startLocalHttpServer({
      cleanup, httpServerBin, root: DEV, port, cwd: IMPL,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!server.ready) fail('G-8 server did not prove owned readiness');
    const url = `http://127.0.0.1:${port}/index.html`;

    const engines = {};
    for (const name of ENGINES) {
      console.log(`> G-8 driving ${name}`);
      engines[name] = await driveEngine(name, url);
    }

    const report = {
      gate: 'G-8', status: 'pass',
      contract: 'real-browser controlled-input correctness through the reusable event-prefix door',
      engines: ENGINES,
      tooth: 'the async-door regression fails the pre-paint + revision arms in every engine',
      mutationTeeth,
      results: engines,
    };
    writeReport(report);
    appendSummary(report);
    console.log(`G-8 PASS (${ENGINES.join(' + ')}) — report: ${REPORT}`);
  } catch (error) {
    writeReport({ gate: 'G-8', status: 'fail', error: error.stack || String(error) });
    throw error;
  } finally {
    tearingDown = true;
    await cleanup.cleanup();
  }
}

module.exports = { assertEngineResult, assertSyncMatrix, assertAsyncTooth, goldenResult, runMutationTeeth };

if (require.main === module) {
  main().catch((error) => {
    console.error(error.stack || String(error));
    process.exitCode = 1;
  });
}
