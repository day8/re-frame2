#!/usr/bin/env node
'use strict';

/*
 * Occasional Causa browser feature/load gate.
 *
 * This is intentionally not default CI. It compiles the deterministic
 * Causa-relevant testbeds, stages them under one static root, then runs a
 * high-value matrix slice plus the 20-event/load re-check from
 * tools/causa/spec/017-Test-Coverage-Matrix.md.
 */

const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const { isVerboseTests } = require('./lib/browser-test-report.cjs');
const {
  createHarnessCleanup,
  spawnHarnessProcess,
  waitForHttpReady,
} = require('./lib/local-browser-harness.cjs');
const {
  SCENARIOS,
  STAGED_SURFACES,
} = require('../../tools/causa/testbeds/feature_matrix/scenarios.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'causa-feature-gate');
const ARTIFACT_ROOT = path.join(IMPL_ROOT, 'out', 'causa-feature-gate-artifacts');
const PORT = Number(process.env.CAUSA_FEATURE_GATE_PORT || 8037);
const BASE_URL = process.env.CAUSA_FEATURE_GATE_BASE_URL || `http://127.0.0.1:${PORT}`;
const TIMEOUT_MS = Number(process.env.CAUSA_FEATURE_GATE_TIMEOUT_MS || 45000);
const READY_TIMEOUT_MS = 30000;
const VERBOSE_TESTS = isVerboseTests();

// rf2-wa3oo: PR-smoke vs nightly-full split. With `--smoke` (or
// RF2_GATE_SMOKE=1) the gate runs only the scenarios tagged
// `smoke: true` and compiles only the testbed surfaces those scenarios
// actually load — cutting the 13-surface / 14-scenario nightly sweep
// down to the 2-surface high-signal subset on the PR critical path.
// The full sweep keeps running nightly in expensive-tests.yml. The CLI
// flag is the cross-platform entry point (no cross-env dependency); the
// env var stays supported for harness composition.
const SMOKE =
  process.env.RF2_GATE_SMOKE === '1' || process.argv.includes('--smoke');

// Map a scenario's served URL (e.g. "/counter/" or
// "/testbeds/deliberate-throw/") back to the STAGED_SURFACES entry that
// serves it, so smoke mode compiles only what the smoke scenarios need.
function surfaceForScenario(scenario) {
  const urlPath = String(scenario.url || '').replace(/^\/+|\/+$/g, '');
  return STAGED_SURFACES.find((surface) => surface.servedPath === urlPath) || null;
}

const ACTIVE_SCENARIOS = SMOKE
  ? SCENARIOS.filter((scenario) => scenario.smoke === true)
  : SCENARIOS;

const ACTIVE_SURFACES = SMOKE
  ? (() => {
      const needed = new Set();
      for (const scenario of ACTIVE_SCENARIOS) {
        const surface = surfaceForScenario(scenario);
        if (surface) needed.add(surface);
      }
      return STAGED_SURFACES.filter((surface) => needed.has(surface));
    })()
  : STAGED_SURFACES;
const { chromium } = require(require.resolve('playwright', {
  paths: [IMPL_ROOT],
}));
const HTTP_SERVER_BIN = require.resolve('http-server/bin/http-server', {
  paths: [IMPL_ROOT],
});
const cleanup = createHarnessCleanup();
cleanup.installSignalHandlers();

function relPath(parts) {
  return path.join(REPO_ROOT, ...parts);
}

function implPath(parts) {
  return path.join(IMPL_ROOT, ...parts);
}

function copyDirRecursive(src, dest) {
  fs.mkdirSync(dest, { recursive: true });
  for (const entry of fs.readdirSync(src, { withFileTypes: true })) {
    const s = path.join(src, entry.name);
    const d = path.join(dest, entry.name);
    if (entry.isDirectory()) {
      copyDirRecursive(s, d);
    } else if (entry.isFile()) {
      fs.copyFileSync(s, d);
    }
  }
}

function cleanAndStageRoot() {
  fs.rmSync(OUT_ROOT, { recursive: true, force: true });
  fs.rmSync(ARTIFACT_ROOT, { recursive: true, force: true });
  fs.mkdirSync(OUT_ROOT, { recursive: true });
  fs.mkdirSync(ARTIFACT_ROOT, { recursive: true });
}

function compileSurfaces() {
  const builds = [...new Set(ACTIVE_SURFACES.map((surface) => surface.build))];
  const isWin = process.platform === 'win32';
  const cmd = isWin ? 'npx.cmd' : 'npx';
  const args = ['shadow-cljs', 'compile', ...builds];
  const result = spawnSync(cmd, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
    shell: isWin,
  });
  if (result.status !== 0) {
    console.error(`> ${cmd} ${args.join(' ')}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

// rf2-jzqs9 — shared per-substrate stylesheets + favicon + OG assets
// (rf2-nfg15 / rf2-3zibv). Example index.html files reference these via
// relative paths like `_shared/css/reagent.css`, so any staged surface
// whose HTML lives under `examples/` gets the `_shared/` tree mirrored
// alongside its main.js + index.html. Mirrors the equivalent
// `stageShared` step in examples/scripts/serve-and-run-examples-tests.cjs
// (rf2-sivlu — without this the counter index.html 404s on its
// stylesheet under the Causa gate, the inline layout breaks, and the
// `source coordinates and launch-mode availability` scenario fails on
// `Host app controls are not laid out to the left of Causa`).
const SHARED_SRC = relPath(['examples', '_shared']);

function stageSharedIfReferenced(surface, outDir) {
  const htmlSrc = relPath(surface.html);
  if (!htmlSrc.startsWith(relPath(['examples']) + path.sep)) return;
  if (!fs.existsSync(SHARED_SRC)) return;
  copyDirRecursive(SHARED_SRC, path.join(outDir, '_shared'));
}

function stageSurfaces() {
  for (const surface of ACTIVE_SURFACES) {
    const bundleSrc = implPath(surface.bundleDir);
    const htmlSrc = relPath(surface.html);
    const outDir = path.join(OUT_ROOT, surface.servedPath);
    if (!fs.existsSync(bundleSrc)) {
      throw new Error(`Bundle source missing for ${surface.build}: ${bundleSrc}`);
    }
    if (!fs.existsSync(htmlSrc)) {
      throw new Error(`HTML source missing for ${surface.build}: ${htmlSrc}`);
    }
    copyDirRecursive(bundleSrc, outDir);
    fs.copyFileSync(htmlSrc, path.join(outDir, 'index.html'));
    stageSharedIfReferenced(surface, outDir);

    for (const extra of surface.extraFiles || []) {
      const src = relPath(extra.src);
      const dest = path.join(outDir, ...extra.dest);
      if (!fs.existsSync(src)) {
        throw new Error(`Static asset missing for ${surface.build}: ${src}`);
      }
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      fs.copyFileSync(src, dest);
    }
  }
}

function safeFileStem(s) {
  return String(s).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'scenario';
}

function trimLines(lines, max = 80) {
  return lines.slice(Math.max(0, lines.length - max));
}

function scenarioDiagnostics(state) {
  const keys = [
    'sourceClicks',
    'launchModes',
    'launchLoad',
    'schemaRecovery',
    'multiFrame',
  ];
  const out = {};
  for (const key of keys) {
    if (state[key] != null) out[key] = state[key];
  }
  return Object.keys(out).length === 0 ? null : out;
}

async function readCausaState(page) {
  return page.evaluate(() => {
    function text(testId) {
      const el = document.querySelector(`[data-testid="${testId}"]`);
      return el ? (el.textContent || '').trim() : null;
    }
    function count(selector) {
      return document.querySelectorAll(selector).length;
    }
    function activePanel() {
      const active = Array.from(document.querySelectorAll('[data-testid^="rf-causa-sidebar-item-"]'))
        .find((el) => (el.textContent || '').includes('◉'));
      return active ? active.getAttribute('data-testid') : null;
    }
    function traceEvents() {
      const cljs = window.cljs && window.cljs.core;
      const bus = window.day8 &&
        window.day8.re_frame2_causa &&
        window.day8.re_frame2_causa.trace_bus;
      if (!cljs || !bus || typeof bus.buffer !== 'function') {
        return { ok: false, reason: 'trace bus unavailable', events: [] };
      }
      const events = [];
      let s = cljs.seq(bus.buffer());
      while (s) {
        events.push(cljs.pr_str(cljs.first(s)));
        s = cljs.next(s);
      }
      return { ok: true, events };
    }
    function epochCount() {
      const cljs = window.cljs && window.cljs.core;
      const rf = window.re_frame && window.re_frame.core;
      if (!cljs || !rf || typeof rf.epoch_history !== 'function') return null;
      const kw = cljs.keyword.call ? cljs.keyword.call(null, 'rf/default') : cljs.keyword('rf/default');
      let n = 0;
      let s = cljs.seq(rf.epoch_history(kw));
      while (s) {
        n += 1;
        s = cljs.next(s);
      }
      return n;
    }
    const root = document.getElementById('rf-causa-root');
    const trace = traceEvents();
    return {
      url: location.href,
      shellMounted: count('[data-testid="rf-causa-shell"]') > 0,
      rootDisplay: root ? getComputedStyle(root).display : 'missing',
      activePanel: activePanel(),
      selectedDispatchRows: count('[data-testid^="rf-causa-cascade-row-"]'),
      traceCounts: text('rf-causa-trace-counts'),
      traceCount: trace.events.length,
      lastTraceEvents: trace.events.slice(-20),
      traceReadError: trace.ok ? null : trace.reason,
      epochCount: epochCount(),
      suppressedSensitive: text('rf-causa-redacted-indicator'),
      visibleTraceRows: count('[data-testid^="rf-causa-trace-row-"]'),
    };
  });
}

async function collectFailureDiagnostics(page, scenario, state, browserState) {
  const screenshotPath = path.join(ARTIFACT_ROOT, `${safeFileStem(scenario.name)}.png`);
  try {
    await page.screenshot({ path: screenshotPath, fullPage: true });
  } catch (err) {
    browserState.screenshotError = err.message;
  }

  let causaState = null;
  try {
    causaState = await readCausaState(page);
  } catch (err) {
    causaState = { error: err.message };
  }

  return {
    scenario: {
      name: scenario.name,
      url: scenario.url,
      fullUrl: page.url(),
      panels: scenario.panels || [],
      gate: 'causa-feature-matrix',
      load: Boolean(scenario.load),
    },
    browser: {
      console: trimLines(browserState.console),
      pageErrors: trimLines(browserState.pageErrors),
      requestFailures: trimLines(browserState.requestFailures),
      screenshotPath,
      screenshotError: browserState.screenshotError || null,
    },
    causa: causaState,
    loadStats: state.loadStats || null,
    scenarioState: scenarioDiagnostics(state),
  };
}

function formatDiagnostics(diag) {
  const lines = [];
  lines.push('--- Causa feature gate failure diagnostics ---');
  lines.push(`scenario: ${diag.scenario.name}`);
  lines.push(`gate: ${diag.scenario.gate}`);
  lines.push(`url: ${diag.scenario.fullUrl || diag.scenario.url}`);
  lines.push(`panels: ${(diag.scenario.panels || []).join(', ') || '(none)'}`);
  lines.push(`load-check: ${diag.scenario.load ? 'yes' : 'no'}`);
  lines.push(`screenshot: ${diag.browser.screenshotPath}`);
  if (diag.browser.screenshotError) lines.push(`screenshot-error: ${diag.browser.screenshotError}`);
  lines.push('');
  lines.push('browser console/page/network:');
  for (const line of diag.browser.console) lines.push(`  ${line}`);
  for (const line of diag.browser.pageErrors) lines.push(`  [pageerror] ${line}`);
  for (const line of diag.browser.requestFailures) lines.push(`  [requestfailed] ${line}`);
  if (diag.browser.console.length === 0 &&
      diag.browser.pageErrors.length === 0 &&
      diag.browser.requestFailures.length === 0) {
    lines.push('  (none captured)');
  }
  lines.push('');
  lines.push('causa state:');
  lines.push(`  mounted=${diag.causa && diag.causa.shellMounted} visible=${diag.causa && diag.causa.rootDisplay}`);
  lines.push(`  activePanel=${diag.causa && diag.causa.activePanel}`);
  lines.push(`  selectedDispatchRows=${diag.causa && diag.causa.selectedDispatchRows}`);
  lines.push(`  epochCount=${diag.causa && diag.causa.epochCount}`);
  lines.push(`  traceCount=${diag.causa && diag.causa.traceCount}`);
  lines.push(`  traceCounts=${diag.causa && diag.causa.traceCounts}`);
  lines.push(`  visibleTraceRows=${diag.causa && diag.causa.visibleTraceRows}`);
  lines.push(`  suppressedSensitive=${diag.causa && diag.causa.suppressedSensitive}`);
  if (diag.causa && diag.causa.traceReadError) lines.push(`  traceReadError=${diag.causa.traceReadError}`);
  if (diag.loadStats) {
    lines.push('');
    lines.push('load stats:');
    for (const [k, v] of Object.entries(diag.loadStats)) {
      lines.push(`  ${k}=${v}`);
    }
  }
  if (diag.scenarioState) {
    lines.push('');
    lines.push('scenario-specific state:');
    lines.push(JSON.stringify(diag.scenarioState, null, 2)
      .split('\n')
      .map((line) => `  ${line}`)
      .join('\n'));
  }
  lines.push('');
  lines.push('last 20 trace events:');
  const last = (diag.causa && diag.causa.lastTraceEvents) || [];
  if (last.length === 0) {
    lines.push('  (none)');
  } else {
    for (const event of last) lines.push(`  ${event}`);
  }
  lines.push('----------------------------------------------');
  return lines.join('\n');
}

async function runScenarios() {
  const browser = await chromium.launch({ headless: true });
  cleanup.addCleanup(async () => {
    try {
      await browser.close();
    } catch (_) {}
  });
  const results = [];
  let anyFailed = false;

  try {
    for (const scenario of ACTIVE_SCENARIOS) {
      const label = scenario.name;
      const detailLines = [];
      const browserState = {
        console: [],
        pageErrors: [],
        requestFailures: [],
      };
      const state = {};
      const context = await browser.newContext();
      const page = await context.newPage();

      page.on('console', (msg) => {
        browserState.console.push(`[browser:${msg.type()}] ${msg.text()}`);
      });
      page.on('pageerror', (err) => {
        browserState.pageErrors.push(err.stack || err.message);
      });
      page.on('requestfailed', (request) => {
        const failure = request.failure();
        browserState.requestFailures.push(`${request.method()} ${request.url()} ${failure ? failure.errorText : ''}`);
      });

      const fullUrl = scenario.url.startsWith('http') ? scenario.url : BASE_URL + scenario.url;
      let passed = false;
      let failure = null;
      let diagnostics = null;
      detailLines.push(`scenario=${label}`);
      detailLines.push(`url=${fullUrl}`);
      detailLines.push(`coveredRows=${(scenario.coveredRows || []).join(' | ')}`);

      try {
        await page.goto(fullUrl, { waitUntil: 'load', timeout: TIMEOUT_MS });
        await Promise.race([
          scenario.run(page, state, { browserState, scenario }),
          new Promise((_, reject) => {
            setTimeout(() => reject(new Error(`Scenario timed out after ${TIMEOUT_MS}ms`)), TIMEOUT_MS);
          }),
        ]);
        passed = true;
      } catch (err) {
        failure = err;
        anyFailed = true;
        diagnostics = await collectFailureDiagnostics(page, scenario, state, browserState);
      } finally {
        await context.close();
      }

      if (!passed) {
        console.error(`FAIL ${label}: ${failure.message}`);
        if (failure.stack) console.error(failure.stack);
        console.error(formatDiagnostics(diagnostics));
      } else if (VERBOSE_TESTS) {
        for (const line of detailLines) console.log(line);
        if (state.loadStats) console.log(`loadStats=${JSON.stringify(state.loadStats)}`);
        for (const line of trimLines(browserState.console, 40)) console.log(line);
        console.log(`PASS ${label}`);
      }

      results.push({
        label,
        passed,
        coveredRows: scenario.coveredRows || [],
        load: Boolean(scenario.load),
      });
    }
  } finally {
    await browser.close();
  }

  const passedCount = results.filter((r) => r.passed).length;
  const failedCount = results.length - passedCount;
  const coveredRows = [...new Set(results.flatMap((r) => r.coveredRows))].sort();
  if (anyFailed) {
    console.log('\n=== Causa feature gate summary ===');
    for (const result of results) {
      console.log(`${result.passed ? 'PASS' : 'FAIL'} ${result.label}`);
    }
  }
  console.log(
    `Ran ${results.length} Causa feature scenarios (${SMOKE ? 'PR-smoke tier' : 'nightly-full sweep'}). ` +
      `${failedCount} failures. Covered ${coveredRows.length} matrix rows.`,
  );
  return anyFailed ? 1 : 0;
}

async function main() {
  // Fail loud rather than silently passing a zero-scenario smoke if a
  // future scenario rename drops the `smoke: true` tag (rf2-wa3oo).
  if (SMOKE && ACTIVE_SCENARIOS.length === 0) {
    throw new Error(
      'RF2_GATE_SMOKE=1 but no scenarios are tagged `smoke: true` in ' +
        'tools/causa/testbeds/feature_matrix/scenarios.cjs. The PR-smoke ' +
        'tier must keep at least one high-signal scenario.',
    );
  }
  console.log(
    `Causa feature gate — ${SMOKE ? 'PR-smoke tier' : 'nightly-full sweep'}: ` +
      `${ACTIVE_SCENARIOS.length} scenario(s) over ${ACTIVE_SURFACES.length} surface(s).`,
  );
  cleanAndStageRoot();
  compileSurfaces();
  stageSurfaces();

  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-p', String(PORT), '-s', '-c-1'], {
    cwd: IMPL_ROOT,
    stdio: ['ignore', 'inherit', 'inherit'],
  }));

  let serverDown = false;
  server.on('exit', (code, signal) => {
    serverDown = true;
    if (code !== 0 && code !== null) {
      console.error(`http-server exited unexpectedly (code=${code}, signal=${signal}).`);
    }
  });

  const ready = await waitForHttpReady(PORT, Date.now() + READY_TIMEOUT_MS, {
    isAborted: () => serverDown,
  });
  if (!ready || serverDown) {
    throw new Error(`http-server did not become reachable on :${PORT} within ${READY_TIMEOUT_MS}ms.`);
  }

  return await runScenarios();
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
