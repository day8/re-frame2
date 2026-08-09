#!/usr/bin/env node
'use strict';

/*
 * THE HICASSO CONTROLLED-INPUT GATE — invariant I15 in three real engines
 * (rf2-hic-016).
 *
 *   node implementation/scripts/serve-and-run-hicasso-controlled-testbed.cjs
 *   npm run test:hicasso-controlled          (from implementation/)
 *
 * Compiles the `:hicasso/testbed` build, serves it, and runs
 * `implementation/hicasso/testbed/spec.cjs` once per engine in Chromium,
 * Firefox and WebKit.
 *
 * ## Why this is not an entry in the adapter-smoke manifest
 *
 * That runner is ONE engine over N specs; this surface is ONE spec over N
 * engines, and the engines are the contract rather than a detail of how it
 * is run. Widening the shared runner would have put three browser launches
 * behind every adapter smoke to serve one caller. The reagent-slim and
 * tenant-switcher testbeds set the precedent for a surface that carries its
 * own orchestrator, and `run-ui-g8.cjs` sets it for a correctness gate that
 * drives more than one engine.
 *
 * ## The three engines, and why each is mandatory
 *
 * Chromium is where every controlled-input claim in this repo was
 * previously witnessed — `run-ui-g8.cjs` adds WebKit for re-frame.ui, and
 * `bench/hicasso/ime_run.cjs` states its own scope as Chromium-only because
 * `Input.imeSetComposition` is a CDP method. Nothing had ever driven
 * Hicasso's element-path converge outside Chromium, and the mechanism is
 * made of exactly the things engines differ on: caret and selection
 * restoration, composition event carriage, and the order in which a
 * discrete event's work is flushed. Firefox is the third because it is the
 * one major engine the repo had never launched at all.
 *
 * ## Two verdicts, kept apart
 *
 * REQUIRED rows are the spec's own assertions; a throw in any engine fails
 * the gate. RECORDED rows are conduct the spec measures without demanding
 * — and they are not a way of not asserting, because this runner COMPARES
 * them across engines and fails on a divergence that no entry in
 * `NARROWINGS` names, with its engines and its reason. An engine that
 * quietly starts behaving differently is therefore a red gate rather than a
 * silently-updated record.
 *
 * The comparator and the check floor both carry mutation teeth that run
 * before any browser launches: a gate whose own verdict logic cannot fail
 * is worse than a gate that is red.
 */

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const playwright = require('playwright');
const {
  createHarnessCleanup,
  resolveServePort,
  startLocalHttpServer,
} = require('./lib/local-browser-harness.cjs');
const { enforcePolicy, DEFAULT_OUT_ROOT } = require('./_path-policy.cjs');

const IMPL_ROOT = path.resolve(__dirname, '..');
const BUILD_ID = 'hicasso/testbed';
const ROOT = enforcePolicy(
  'HICASSO_TESTBED_ROOT',
  path.join(IMPL_ROOT, 'out', 'hicasso-testbed'),
  { allowedRoots: [DEFAULT_OUT_ROOT] },
);
const HTML_SRC = path.join(IMPL_ROOT, 'hicasso', 'testbed', 'index.html');
const SPEC = require(path.join(IMPL_ROOT, 'hicasso', 'testbed', 'spec.cjs'));

const DEFAULT_PORT = 8065;
const READY_TIMEOUT_MS = 30000;
const SPEC_TIMEOUT_MS = parseInt(process.env.HICASSO_TESTBED_SPEC_TIMEOUT_MS || '90000', 10);
// The navigation's own ceiling, named so a CI log cannot read it as the
// spec budget above. `'commit'` rather than `'load'`: the page's own script
// is an un-optimized dev bundle that mounts the app, and everything after
// the navigation auto-waits on the execution context anyway.
const NAV_WAIT_UNTIL = 'commit';
const NAV_TIMEOUT_MS = 60000;

// Chromium, Firefox and WebKit. `HICASSO_TESTBED_ENGINES` narrows the set
// for local iteration; the floor below is prorated so a partial run stays
// honest about being one.
const ALL_ENGINES = ['chromium', 'firefox', 'webkit'];
const ONLY = (process.env.HICASSO_TESTBED_ENGINES || '').trim();
const ENGINES = ONLY
  ? ALL_ENGINES.filter((e) => ONLY.split(',').map((s) => s.trim()).includes(e))
  : ALL_ENGINES;

// The check floor (the discipline `ime_run.cjs` carries): a run that
// asserted almost nothing must not exit 0. A full engine banks 55 checks;
// 50 refuses a section that silently stopped running — the smallest of the
// nine is 6 rows — while leaving room for a row to be retired.
const MIN_CHECKS_PER_ENGINE = 50;

// ---------------------------------------------------------------------------
// Cross-engine narrowings — every RECORDED row that is allowed to differ,
// with the engines it differs on and why. An entry here is a claim about
// the world; an unlisted divergence is a red gate.
// ---------------------------------------------------------------------------

const NARROWINGS = [
  // Populated only by a divergence this gate actually measured. An empty
  // list is the honest starting state: nothing is excused in advance.
];

function narrowingFor(row) {
  return NARROWINGS.find((n) => n.row === row) || null;
}

/**
 * Compare the RECORDED rows across engines. Returns a list of problems —
 * empty when every row either agrees everywhere or is covered by a
 * narrowing that names the engines it saw.
 */
function divergenceReport(perEngine, narrowings = NARROWINGS) {
  const problems = [];
  const engines = Object.keys(perEngine);
  if (engines.length < 2) return problems;
  const rows = new Set();
  for (const e of engines) for (const r of Object.keys(perEngine[e])) rows.add(r);

  for (const row of rows) {
    const byValue = new Map();
    for (const e of engines) {
      const key = JSON.stringify(perEngine[e][row]);
      if (!byValue.has(key)) byValue.set(key, []);
      byValue.get(key).push(e);
    }
    if (byValue.size === 1) continue;
    const narrowing = narrowings.find((n) => n.row === row);
    const groups = [...byValue.entries()]
      .map(([value, es]) => `${es.join('+')}: ${value}`)
      .join('  |  ');
    if (!narrowing) {
      problems.push(
        `RECORDED row "${row}" diverges across engines and no NARROWINGS ` +
        `entry names it — ${groups}. Either the runtime should agree here ` +
        `(fix it) or the divergence is real (add a narrowing with its ` +
        `engines and reason, and carry it to the hic-005 table).`);
      continue;
    }
    const seen = engines.filter((e) => JSON.stringify(perEngine[e][row]) !== JSON.stringify(perEngine[engines[0]][row]));
    const unexpected = seen.filter((e) => !narrowing.engines.includes(e));
    if (unexpected.length > 0) {
      problems.push(
        `RECORDED row "${row}" has a narrowing for ${narrowing.engines.join('+')}, ` +
        `but ${unexpected.join('+')} also diverged — ${groups}.`);
    }
  }
  return problems;
}

// ---------------------------------------------------------------------------
// Mutation teeth — the gate's own verdict logic, proven able to fail before
// a single browser is launched.
// ---------------------------------------------------------------------------

function runMutationTeeth() {
  const teeth = [];
  const bite = (name, fn) => {
    if (!fn()) throw new Error(`MUTATION TOOTH DID NOT BITE: ${name}`);
    teeth.push(name);
  };

  bite('agreement across engines raises nothing', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 1 } }, webkit: { row: { a: 1 } },
    }, []).length === 0);

  bite('an unnarrowed divergence is a problem', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } }, webkit: { row: { a: 1 } },
    }, []).length === 1);

  bite('a narrowing that names the engine excuses it', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } },
    }, [{ row: 'row', engines: ['firefox'], why: 'tooth' }]).length === 0);

  bite('a narrowing that names the WRONG engine does not', () =>
    divergenceReport({
      chromium: { row: { a: 1 } }, firefox: { row: { a: 2 } },
    }, [{ row: 'row', engines: ['webkit'], why: 'tooth' }]).length === 1);

  bite('a single engine cannot diverge from itself', () =>
    divergenceReport({ chromium: { row: { a: 1 } } }, []).length === 0);

  bite('the check floor refuses a run that asserted almost nothing', () =>
    belowFloor({ checks: 3 }) === true && belowFloor({ checks: MIN_CHECKS_PER_ENGINE }) === false);

  return teeth;
}

function belowFloor(result) {
  return result.checks < MIN_CHECKS_PER_ENGINE;
}

// ---------------------------------------------------------------------------
// Build and serve
// ---------------------------------------------------------------------------

function compile() {
  const runner = require.resolve('shadow-cljs/cli/runner.js', { paths: [IMPL_ROOT] });
  console.log(`> shadow-cljs compile ${BUILD_ID}`);
  const result = spawnSync(process.execPath, [runner, 'compile', BUILD_ID], {
    cwd: IMPL_ROOT, env: process.env, shell: false, stdio: 'inherit',
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(`shadow-cljs compile ${BUILD_ID} exited ${result.status}`);
  }
}

function stageHtml() {
  fs.mkdirSync(ROOT, { recursive: true });
  fs.copyFileSync(HTML_SRC, path.join(ROOT, 'index.html'));
}

// ---------------------------------------------------------------------------
// One engine
// ---------------------------------------------------------------------------

async function driveEngine(engine, baseUrl) {
  const browserType = playwright[engine];
  if (!browserType) throw new Error(`unknown engine ${engine}`);
  const browser = await browserType.launch({ headless: true });
  // Page errors live in a DEDICATED array and flip the verdict, even when
  // every assertion otherwise passed.
  const pageErrors = [];
  const lines = [];
  const log = (s) => lines.push(s);
  let passed = false;
  let result = null;
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    page.on('console', (msg) => log(`[${engine}:${msg.type()}] ${msg.text()}`));
    page.on('pageerror', (err) => {
      pageErrors.push(err);
      log(`[${engine}:pageerror] ${err.message}`);
      if (err.stack) log(err.stack);
    });
    // The spec's page-side vocabulary, in place before the app's own script
    // runs so nothing races the mount.
    await page.addInitScript(SPEC.pageHelpers);

    const url = SPEC.url.startsWith('http') ? SPEC.url : baseUrl + SPEC.url;
    log(`\n=== ${SPEC.name} — ${engine} ===`);
    try {
      await page.goto(url, { waitUntil: NAV_WAIT_UNTIL, timeout: NAV_TIMEOUT_MS });
    } catch (err) {
      throw new Error(
        `[${engine}] NAVIGATION FAILED — the page.goto ceiling fired ` +
        `(waitUntil: '${NAV_WAIT_UNTIL}', timeout: ${NAV_TIMEOUT_MS}ms), which ` +
        `is NOT the ${SPEC_TIMEOUT_MS}ms spec budget that would have followed ` +
        `it. No witness ran, so nothing about the runtime has been observed. ` +
        `An unbuilt bundle or a dead server fails here at any size. ` +
        `Underlying: ${err.message}`);
    }
    // The app must be up before the first witness touches a field.
    await page.waitForSelector('[data-testid="hicasso-controlled-testbed"]',
      { timeout: SPEC_TIMEOUT_MS });

    result = await withTimeout(SPEC.run(page, { engine }), SPEC_TIMEOUT_MS,
      `${SPEC.name} (${engine})`);

    if (pageErrors.length > 0) {
      throw new Error(
        `[${engine}] page emitted ${pageErrors.length} uncaught error(s); ` +
        `first: ${pageErrors[0].message}`);
    }
    if (belowFloor(result)) {
      throw new Error(
        `[${engine}] banked only ${result.checks} checks, floor is ` +
        `${MIN_CHECKS_PER_ENGINE} — a section stopped running.`);
    }
    passed = true;
  } catch (err) {
    log(`FAIL ${SPEC.name} (${engine}): ${err && err.message ? err.message : err}`);
    if (err && err.stack) log(err.stack);
  } finally {
    await browser.close();
  }
  if (!passed) for (const ln of lines) console.log(ln);
  console.log(passed
    ? `PASS  ${engine} — ${result.checks} checks`
    : `FAIL  ${engine}`);
  return { passed, result };
}

function withTimeout(promise, ms, label) {
  let timer;
  return Promise.race([
    promise.finally(() => clearTimeout(timer)),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${label} exceeded ${ms}ms`)), ms);
    }),
  ]);
}

// ---------------------------------------------------------------------------

async function main() {
  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();
  let tearingDown = false;

  if (ENGINES.length === 0) {
    console.error(`HICASSO_TESTBED_ENGINES=${ONLY} selects no engine; ` +
      `known: ${ALL_ENGINES.join(', ')}`);
    return 1;
  }

  try {
    const teeth = runMutationTeeth();
    console.log(`> verdict logic teeth bit: ${teeth.length}`);

    compile();
    stageHtml();

    const port = await resolveServePort(
      Number(process.env.HICASSO_TESTBED_PORT) || DEFAULT_PORT,
      { onFallback: (p, f) => console.log(`> port ${p} busy, serving on ${f}`) },
    );
    const httpServerBin = require.resolve('http-server/bin/http-server', { paths: [IMPL_ROOT] });
    const server = await startLocalHttpServer({
      cleanup,
      httpServerBin,
      root: ROOT,
      port,
      cwd: IMPL_ROOT,
      readyTimeoutMs: READY_TIMEOUT_MS,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!server.ready) {
      console.error('the testbed server did not prove owned readiness');
      return 1;
    }
    const baseUrl = `http://127.0.0.1:${port}`;

    const recorded = {};
    let failures = 0;
    for (const engine of ENGINES) {
      const { passed, result } = await driveEngine(engine, baseUrl);
      if (!passed) failures += 1;
      else recorded[engine] = result.recorded;
    }
    if (failures > 0) {
      console.error(`\n${failures} of ${ENGINES.length} engine(s) failed.`);
      return 1;
    }

    console.log('\n--- RECORDED conduct (measured, not required) ---');
    for (const engine of ENGINES) {
      console.log(`  [${engine}] ${JSON.stringify(recorded[engine])}`);
    }
    const problems = divergenceReport(recorded);
    if (problems.length > 0) {
      console.error('\nCROSS-ENGINE DIVERGENCE:');
      for (const p of problems) console.error(`  - ${p}`);
      return 1;
    }
    for (const n of NARROWINGS) {
      console.log(`  narrowing: ${n.row} on ${n.engines.join('+')} — ${n.why}`);
    }
    console.log(`\nHICASSO CONTROLLED-INPUT PASS (${ENGINES.join(' + ')})`);
    return 0;
  } finally {
    tearingDown = true;
    await cleanup.cleanup();
  }
}

module.exports = { divergenceReport, runMutationTeeth, belowFloor, NARROWINGS };

if (require.main === module) {
  main().then((code) => process.exit(code)).catch((error) => {
    console.error(error.stack || String(error));
    process.exit(1);
  });
}
