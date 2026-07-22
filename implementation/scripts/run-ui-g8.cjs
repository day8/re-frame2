#!/usr/bin/env node
'use strict';

// G-8 — the real-browser controlled-input gate (07 §5; EP-0034 §4 widened G-8;
// EP-0035). S-5's evidence was jsdom-only; this runner serves one un-optimized
// :ui-g8 bundle and drives the SAME reusable-event-prefix fixture in real
// Chromium AND real WebKit. The canonical G-8 is TWO channels, kept separate:
//
//   DETERMINISTIC CORRECTNESS (the hard gate) — pre-paint synchronous commit,
//   single revision advance, ONE attributable React commit per ordinary input,
//   ordering, caret restoration, and an IME composition boundary that commits
//   EXACTLY ONCE (one revision AND one React commit). Both engines must pass
//   this matrix under the sync door AND show the deliberate async-door
//   regression FAIL the pre-paint + revision arms (the tooth). The runner's own
//   assertions carry mutation teeth so a vacuous check cannot pass — including
//   teeth for an extra React commit, an IME duplicate, and a vacuous commit
//   counter.
//
//   COMPARATIVE LATENCY (evidence only; NO threshold — G-13's posture) — the
//   compiled reusable control measured against an equivalent hand-written
//   React control in the SAME warmed run, on TWO channels per engine
//   (rf2-dpwel): `commit` — the TRUE event-to-commit boundary, each sample
//   keyed to a fresh input and ended at the arm's own React.Profiler onRender
//   commitTime with exactly one attributable commit asserted; `settlement` —
//   the former input-to-flush-settlement metric, retained for comparison.
//   Pair order alternates compiled-first / hand-written-first (no fixed
//   sequence bias). The p95 ratios, the within-10% observation, the sample
//   count, and the noise policy are RECORDED and reported; nothing gates on a
//   wall-clock number. The budget comparison is kept non-vacuous by mutation
//   teeth over synthetic data.

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
const {
  RECORDED_SAMPLES,
  PERCENTILE_CONVENTION,
  RATIO_BUDGET,
  NOISE_POLICY,
  CHANNELS,
  withinBudget,
  engineLatencyEvidence,
  engineLatencyChannels,
  buildPerformanceSummary,
} = require('./lib/g8-latency-evidence.cjs');

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
  // One attributable React commit per ordinary input, read SEPARATELY from the
  // ViewCell revision. Exactly one advance AND exactly one commit — a vacuous
  // (never-incrementing) commit counter reports 0 and fails here.
  if (sync['ordinary-revision-delta'] !== 1) {
    fail(`[${engine}] one ordinary input advanced the ViewCell ${sync['ordinary-revision-delta']} times, not exactly once`);
  }
  if (sync['ordinary-commit-delta'] !== 1) {
    fail(`[${engine}] one ordinary input produced ${sync['ordinary-commit-delta']} React commits, not exactly one (commit-count arm — a vacuous counter reports 0)`);
  }
  if (sync['handwritten-commit-delta'] !== 1) {
    fail(`[${engine}] the hand-written baseline produced ${sync['handwritten-commit-delta']} React commits for one input, not exactly one`);
  }
  if (sync['ime-committed'] !== 'が' || sync['ime-dom'] !== 'が') {
    fail(`[${engine}] IME composition did not commit the composed value: committed=${sync['ime-committed']} dom=${sync['ime-dom']}`);
  }
  if (sync['ime-caret'] !== 1) {
    fail(`[${engine}] IME caret not preserved: ${sync['ime-caret']}`);
  }
  // The IME boundary commits the composed value EXACTLY ONCE — one ViewCell
  // revision AND one React commit. `=== 1`, not `>= 1`: a duplicate revision or
  // commit at the composition boundary reddens the gate.
  if (sync['ime-revision-delta'] !== 1) {
    fail(`[${engine}] IME composition advanced the ViewCell ${sync['ime-revision-delta']} times, not exactly once (IME duplicate-revision arm)`);
  }
  if (sync['ime-commit-delta'] !== 1) {
    fail(`[${engine}] IME composition produced ${sync['ime-commit-delta']} React commits, not exactly one (IME duplicate-commit arm)`);
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
      'ordinary-revision-delta': 1, 'ordinary-commit-delta': 1,
      'handwritten-commit-delta': 1,
      'ime-committed': 'が', 'ime-dom': 'が', 'ime-caret': 1,
      'ime-revision-delta': 1, 'ime-commit-delta': 1,
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
    // One-commit-per-input teeth — an EXTRA React commit and a VACUOUS
    // (never-incrementing) commit counter must both redden the gate.
    expectRejected('ordinary input produced an extra React commit', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ordinary-commit-delta'] = 2; }))),
    expectRejected('ordinary commit counter vacuous (0 commits observed)', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ordinary-commit-delta'] = 0; }))),
    expectRejected('ordinary input advanced the ViewCell twice', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ordinary-revision-delta'] = 2; }))),
    // IME duplicate teeth — a duplicate revision OR a duplicate React commit at
    // the composition boundary is rejected (`=== 1`, not `>= 1`).
    expectRejected('sync IME value wrong', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ime-committed'] = 'x'; }))),
    expectRejected('IME committed a duplicate revision (>= 1 accepted a duplicate)', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ime-revision-delta'] = 2; }))),
    expectRejected('IME committed a duplicate React commit', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ime-commit-delta'] = 2; }))),
    expectRejected('IME commit counter vacuous (0 commits observed)', () =>
      assertEngineResult('mutant', mutate((r) => { r.sync['ime-commit-delta'] = 0; }))),
    expectRejected('async door accidentally committed pre-paint', () =>
      assertEngineResult('mutant', mutate((r) => { r.async['pre-paint-committed'] = true; }))),
    expectRejected('async door advanced the cell synchronously', () =>
      assertEngineResult('mutant', mutate((r) => { r.async['revision-delta'] = 1; }))),
  ];
}

// The evidence-only budget comparison must still BITE on the data: a tight
// sample set keeps `within-10pct` true, an over-budget compiled control flips
// it false, and a vacuous (zero) baseline is rejected as a broken measurement.
// These teeth run over SYNTHETIC arrays — they prove the comparison is
// non-vacuous WITHOUT ever gating the real measured latency.
function runBudgetTeeth() {
  const tight = Array(RECORDED_SAMPLES).fill(1.0);
  const overBudget = Array(RECORDED_SAMPLES).fill(2.0); // 2x the baseline p95
  const teeth = [];
  const ok = engineLatencyEvidence('synthetic', tight, tight);
  if (ok['within-10pct'] !== true) {
    fail('budget tooth vacuous: a tight compiled p95 was not reported within 10% of the baseline');
  }
  teeth.push('tight compiled p95 reported within 10% of baseline');
  const bad = engineLatencyEvidence('synthetic', overBudget, tight);
  if (bad['within-10pct'] !== false) {
    fail('budget tooth did not bite: a 2x compiled p95 was still reported within 10%');
  }
  teeth.push('over-budget compiled p95 (2x baseline) flagged outside 10%');
  teeth.push(expectRejected('a zero baseline p95 is rejected as a broken measurement', () => {
    withinBudget(1.0, 0);
  }));
  return teeth;
}

function writeReport(report) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(REPORT, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
}

function appendSummary(report) {
  const target = process.env.GITHUB_STEP_SUMMARY;
  if (!target || report.status !== 'pass') return;
  const lines = [
    '### G-8 controlled-input correctness (real Chromium + WebKit)',
    '',
    'Deterministic correctness — the hard gate. One attributable React commit ' +
      'per ordinary input; the IME boundary commits EXACTLY once (one revision, ' +
      'one commit).',
    '',
    '| engine | pre-paint | 1-advance | 1-commit | ordering | caret | IME=1 | async tooth |',
    '|---|---|---|---|---|---|---|---|',
  ];
  for (const e of ENGINES) {
    lines.push(`| ${e} | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | bit |`);
  }
  fs.appendFileSync(target, `${lines.join('\n')}\n\n`, 'utf8');
  fs.appendFileSync(target, buildPerformanceSummary(report.performance.engines), 'utf8');
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
    // matrix assertion (or a vacuous budget comparison) is a worse failure than
    // a red gate.
    const mutationTeeth = runMutationTeeth();
    const budgetTeeth = runBudgetTeeth();

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

    // Comparative-latency evidence — derive the per-engine, per-channel p95s,
    // ratios, and within-10% OBSERVATIONS from the raw samples the fixture
    // measured in the same warmed run: the `commit` channel (true
    // event-to-commit — Profiler onRender endpoint) and the `settlement`
    // channel (the former input-to-quiescence metric, retained for
    // comparison). Evidence-only; the gate's pass/fail does NOT depend on any
    // of these numbers (only their sample SHAPE is validated, inside
    // engineLatencyEvidence). The stated relative budget is surfaced as an
    // observation, not a threshold.
    const perfEngines = ENGINES.map((name) => engineLatencyChannels(name, engines[name].latency));

    const report = {
      gate: 'G-8', status: 'pass',
      contract: 'real-browser controlled-input correctness AND comparative event-to-commit latency through the reusable event-prefix door',
      engines: ENGINES,
      // Deterministic correctness — the hard gate, kept SEPARATE from the
      // comparative performance evidence below (canonical G-8 distinguishes the
      // two).
      correctness: {
        posture: 'deterministic; hard-gated',
        commitPosture: 'one attributable React commit per ordinary input and exactly one at the committed IME boundary; the IME boundary advances the ViewCell exactly once (=== 1, not >= 1)',
        tooth: 'the async-door regression fails the pre-paint + revision arms in every engine',
        mutationTeeth,
      },
      // Comparative latency — EVIDENCE ONLY; no wall-clock threshold. The p95
      // ratios and within-10% observations are recorded per channel, never
      // gated; the budget comparison is kept non-vacuous by the budget teeth.
      performance: {
        posture: 'evidence-only; no wall-clock threshold',
        contract: 'compiled reusable control vs an equivalent hand-written React control (useState/onChange) in the same warmed run; commit channel = TRUE event-to-commit (per-sample Profiler onRender commitTime, exactly one attributable commit asserted); settlement channel = the former input-to-flush-settlement metric, retained for comparison; pair order alternates compiled-first / hand-written-first',
        percentileConvention: PERCENTILE_CONVENTION,
        noisePolicy: NOISE_POLICY,
        ratioBudget: RATIO_BUDGET,
        budgetTeeth,
        engines: perfEngines,
      },
      // Raw per-engine fixture output (sync/async matrix + raw latency samples)
      // — the useful per-engine artifact preserved verbatim.
      results: engines,
    };
    writeReport(report);
    appendSummary(report);
    console.log(`G-8 PASS (${ENGINES.join(' + ')}) — report: ${REPORT}`);
    for (const e of perfEngines) {
      for (const ch of CHANNELS) {
        const ev = e[ch];
        const label = ch === 'commit'
          ? 'commit (true event-to-commit)'
          : 'settlement (former metric)';
        console.log(
          `  [${e.engine}] ${label} p95: compiled ${ev.compiled['p95-ms'].toFixed(3)}ms ` +
            `vs hand-written ${ev.handwritten['p95-ms'].toFixed(3)}ms ` +
            `(ratio ${ev['p95-ratio'].toFixed(3)}, within-10% ${ev['within-10pct']}) — evidence only`,
        );
      }
    }
  } catch (error) {
    writeReport({ gate: 'G-8', status: 'fail', error: error.stack || String(error) });
    throw error;
  } finally {
    tearingDown = true;
    await cleanup.cleanup();
  }
}

module.exports = {
  assertEngineResult, assertSyncMatrix, assertAsyncTooth,
  goldenResult, runMutationTeeth, runBudgetTeeth,
};

if (require.main === module) {
  main().catch((error) => {
    console.error(error.stack || String(error));
    process.exitCode = 1;
  });
}
