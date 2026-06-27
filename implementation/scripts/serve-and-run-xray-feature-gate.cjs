#!/usr/bin/env node
'use strict';

/*
 * Occasional Xray browser feature/load gate.
 *
 * This is intentionally not default CI. It compiles the deterministic
 * Xray-relevant testbeds, stages them under one static root, then runs a
 * high-value matrix slice plus the 20-event/load re-check from
 * tools/xray/spec/017-Test-Coverage-Matrix.md.
 */

const { spawnSync } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const { isVerboseTests } = require('./lib/browser-test-report.cjs');
const {
  TOKEN_FILE_BASENAME,
  createHarnessCleanup,
  probeTargetFromBaseUrl,
  resolveServePort,
  spawnHarnessProcess,
  waitForHttpReady,
  waitForOwnedHttpReady,
} = require('./lib/local-browser-harness.cjs');
const {
  SCENARIOS,
  STAGED_SURFACES,
} = require('../../tools/xray/testbeds/feature_matrix/scenarios.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const IMPL_ROOT = path.join(REPO_ROOT, 'implementation');
const OUT_ROOT = path.join(IMPL_ROOT, 'out', 'xray-feature-gate');
const ARTIFACT_ROOT = path.join(IMPL_ROOT, 'out', 'xray-feature-gate-artifacts');
// Preferred port; the gate falls back to an OS-chosen free port when this
// is busy (rf2-84gzw). The resolved port is threaded into BASE_URL at
// runtime, so a fixed override here no longer pins the run to a possibly
// foreign listener. XRAY_FEATURE_GATE_BASE_URL still lets a caller point
// at an external server entirely (in which case ownership-token
// verification is skipped — see main()).
const PREFERRED_PORT = Number(process.env.XRAY_FEATURE_GATE_PORT || 8037);
const EXTERNAL_BASE_URL = process.env.XRAY_FEATURE_GATE_BASE_URL || null;
const TIMEOUT_MS = Number(process.env.XRAY_FEATURE_GATE_TIMEOUT_MS || 45000);
const READY_TIMEOUT_MS = 30000;
const VERBOSE_TESTS = isVerboseTests();

// Per-run ownership token (rf2-84gzw / rf2-gkf9). Published as
// `${OUT_ROOT}/.rf-harness-token` before http-server is spawned (OUT_ROOT
// is a staging dir this script owns — cleanAndStageRoot() recreates it),
// then verified by waitForOwnedHttpReady before any Playwright scenario
// runs. Defeats a stale/foreign listener on the chosen port serving a
// different (possibly stale-but-compatible → FALSE-GREEN) asset tree.
const TOKEN_PATH = path.join(OUT_ROOT, TOKEN_FILE_BASENAME);

function writeOwnershipToken() {
  if (!fs.existsSync(OUT_ROOT)) return null;
  const token = crypto.randomBytes(16).toString('hex');
  fs.writeFileSync(TOKEN_PATH, token, 'utf8');
  return token;
}

function removeOwnershipToken() {
  try {
    if (fs.existsSync(TOKEN_PATH)) fs.unlinkSync(TOKEN_PATH);
  } catch (_) {
    // best-effort cleanup; never let teardown bookkeeping fail the run.
  }
}

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

// Map a scenario's served URL (e.g. "/counter/",
// "/testbeds/deliberate-throw/", or
// "/testbeds/panel-gallery/#/stories") back to the STAGED_SURFACES
// entry that serves it, so smoke mode compiles only what the smoke
// scenarios need. The matcher strips the hash fragment (rf2-azfct
// added the first scenario whose URL carries a `#/...` hash route)
// and any query string before comparing against `servedPath`, which
// is hash-/query-free by construction.
function surfaceForScenario(scenario) {
  const raw = String(scenario.url || '');
  // Drop the hash + query first; some scenarios route via a
  // hash-router (`#/stories`) where the path before the hash is the
  // staged surface.
  const pathOnly = raw.split('#')[0].split('?')[0];
  const urlPath = pathOnly.replace(/^\/+|\/+$/g, '');
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
// Resolve shadow-cljs's own JS entry-point so the compile step spawns it
// shell-free under THIS node binary (`process.execPath`) — never `npx`/
// `npx.cmd` under a shell (rf2-wn4o1). Mirrors the http-server resolution
// just above and the shell-free spawn the server launch already uses.
// Sidesteps the Windows command-hijack accident class (rf2-33vvc) and the
// `.cmd`-under-no-shell `EINVAL` from the CVE-2024-27980 mitigation.
const SHADOW_CLJS_RUNNER = require.resolve('shadow-cljs/cli/runner.js', {
  paths: [IMPL_ROOT],
});
const cleanup = createHarnessCleanup();
cleanup.addCleanup(removeOwnershipToken);
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
  // Spawn shadow-cljs shell-free under this node binary (rf2-wn4o1): the
  // resolved absolute `.js` runner is the only thing the OS interprets,
  // so a workspace-local `npx.cmd` can no longer hijack the compile and
  // there is no `shell:true` warning/quoting class.
  const args = [SHADOW_CLJS_RUNNER, 'compile', ...builds];
  const result = spawnSync(process.execPath, args, {
    cwd: IMPL_ROOT,
    stdio: 'inherit',
  });
  if (result.status !== 0) {
    console.error(`> shadow-cljs compile ${builds.join(' ')}`);
    throw new Error(`shadow-cljs compile failed (exit ${result.status})`);
  }
}

// rf2-jzqs9 — shared per-substrate stylesheets + favicon + OG assets
// (rf2-nfg15 / rf2-3zibv). Example index.html files reference these via
// relative paths like `_shared/css/reagent.css`, so any staged surface
// whose HTML lives under `examples/` gets the `_shared/` tree mirrored
// alongside its main.js + index.html. Mirrors the equivalent
// `stageShared` step in implementation/adapters/scripts/serve-and-run-adapter-smokes.cjs
// (rf2-sivlu — without this the counter index.html 404s on its
// stylesheet under the Xray gate, the inline layout breaks, and the
// `source coordinates and launch-mode availability` scenario fails on
// `Host app controls are not laid out to the left of Xray`).
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

async function readXrayState(page) {
  return page.evaluate(() => {
    function text(testId) {
      const el = document.querySelector(`[data-testid="${testId}"]`);
      return el ? (el.textContent || '').trim() : null;
    }
    function count(selector) {
      return document.querySelectorAll(selector).length;
    }
    function activePanel() {
      const active = Array.from(document.querySelectorAll('[data-testid^="rf-xray-sidebar-item-"]'))
        .find((el) => (el.textContent || '').includes('◉'));
      return active ? active.getAttribute('data-testid') : null;
    }
    function traceEvents() {
      const cljs = window.cljs && window.cljs.core;
      const bus = window.day8 &&
        window.day8.re_frame2_xray &&
        window.day8.re_frame2_xray.trace_collector;
      if (!cljs || !bus || typeof bus.buffer_for_test !== 'function') {
        return { ok: false, reason: 'trace bus unavailable', events: [] };
      }
      const events = [];
      let s = cljs.seq(bus.buffer_for_test());
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
    const root = document.getElementById('rf-xray-root');
    const trace = traceEvents();
    return {
      url: location.href,
      shellMounted: count('[data-testid="rf-xray-shell"]') > 0,
      rootDisplay: root ? getComputedStyle(root).display : 'missing',
      activePanel: activePanel(),
      selectedDispatchRows: count('[data-testid^="rf-xray-cascade-row-"]'),
      traceCounts: text('rf-xray-trace-counts'),
      traceCount: trace.events.length,
      lastTraceEvents: trace.events.slice(-20),
      traceReadError: trace.ok ? null : trace.reason,
      epochCount: epochCount(),
      suppressedSensitive: text('rf-xray-redacted-indicator'),
      visibleTraceRows: count('[data-testid^="rf-xray-trace-row-"]'),
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

  let xrayState = null;
  try {
    xrayState = await readXrayState(page);
  } catch (err) {
    xrayState = { error: err.message };
  }

  return {
    scenario: {
      name: scenario.name,
      url: scenario.url,
      fullUrl: page.url(),
      panels: scenario.panels || [],
      gate: 'xray-feature-matrix',
      load: Boolean(scenario.load),
    },
    browser: {
      console: trimLines(browserState.console),
      pageErrors: trimLines(browserState.pageErrors),
      requestFailures: trimLines(browserState.requestFailures),
      screenshotPath,
      screenshotError: browserState.screenshotError || null,
    },
    xray: xrayState,
    loadStats: state.loadStats || null,
    scenarioState: scenarioDiagnostics(state),
  };
}

function formatDiagnostics(diag) {
  const lines = [];
  lines.push('--- Xray feature gate failure diagnostics ---');
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
  lines.push('xray state:');
  lines.push(`  mounted=${diag.xray && diag.xray.shellMounted} visible=${diag.xray && diag.xray.rootDisplay}`);
  lines.push(`  activePanel=${diag.xray && diag.xray.activePanel}`);
  lines.push(`  selectedDispatchRows=${diag.xray && diag.xray.selectedDispatchRows}`);
  lines.push(`  epochCount=${diag.xray && diag.xray.epochCount}`);
  lines.push(`  traceCount=${diag.xray && diag.xray.traceCount}`);
  lines.push(`  traceCounts=${diag.xray && diag.xray.traceCounts}`);
  lines.push(`  visibleTraceRows=${diag.xray && diag.xray.visibleTraceRows}`);
  lines.push(`  suppressedSensitive=${diag.xray && diag.xray.suppressedSensitive}`);
  if (diag.xray && diag.xray.traceReadError) lines.push(`  traceReadError=${diag.xray.traceReadError}`);
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
  const last = (diag.xray && diag.xray.lastTraceEvents) || [];
  if (last.length === 0) {
    lines.push('  (none)');
  } else {
    for (const event of last) lines.push(`  ${event}`);
  }
  lines.push('----------------------------------------------');
  return lines.join('\n');
}

async function runScenarios(baseUrl) {
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

      const fullUrl = scenario.url.startsWith('http') ? scenario.url : baseUrl + scenario.url;
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
        // rf2-mwx08: an uncaught browser/runtime exception is fatal even
        // when the scenario's visible assertions all passed. pageErrors
        // are captured by the `pageerror` listener above (line ~430) and
        // were previously diagnostic-only — a green verdict could ship
        // while Chromium observed an uncaught exception. Console noise
        // stays diagnostic-only (this gate never treated it as fatal);
        // only `pageerror` flips the verdict. Mirrors the rf2-wf5al fix
        // for the examples/scripts Story play runner.
        if (browserState.pageErrors.length > 0) {
          throw new Error(
            `Scenario emitted ${browserState.pageErrors.length} uncaught ` +
              `browser pageerror(s) — failing the gate (rf2-mwx08). ` +
              `First: ${browserState.pageErrors[0]}`,
          );
        }
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
    console.log('\n=== Xray feature gate summary ===');
    for (const result of results) {
      console.log(`${result.passed ? 'PASS' : 'FAIL'} ${result.label}`);
    }
  }
  console.log(
    `Ran ${results.length} Xray feature scenarios (${SMOKE ? 'PR-smoke tier' : 'nightly-full sweep'}). ` +
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
        'tools/xray/testbeds/feature_matrix/scenarios.cjs. The PR-smoke ' +
        'tier must keep at least one high-signal scenario.',
    );
  }
  console.log(
    `Xray feature gate — ${SMOKE ? 'PR-smoke tier' : 'nightly-full sweep'}: ` +
      `${ACTIVE_SCENARIOS.length} scenario(s) over ${ACTIVE_SURFACES.length} surface(s).`,
  );
  cleanAndStageRoot();
  compileSurfaces();
  stageSurfaces();

  // Escape hatch: a caller may point the gate at a server it manages
  // itself (XRAY_FEATURE_GATE_BASE_URL). We cannot publish/verify an
  // ownership token there, so we only probe for reachability — the
  // caller owns the asset tree it points us at. The default path below
  // is the hardened one.
  if (EXTERNAL_BASE_URL) {
    // rf2-rcepku — probe the URL the caller actually gave us, not just a
    // port against loopback `/`. The previous form extracted only a port
    // and called waitForHttpReady(port) which defaults to host
    // 127.0.0.1 + path `/`, so a base URL with a non-loopback host, an
    // https scheme, or a meaningful base path could be reported
    // unreachable while valid — or (worse) pass readiness against an
    // UNRELATED local listener on the same port before Playwright then
    // navigated to the real external URL (a false-green). Derive the
    // host/port/path from the parsed URL and probe those.
    const target = probeTargetFromBaseUrl(EXTERNAL_BASE_URL, {
      envName: 'XRAY_FEATURE_GATE_BASE_URL',
    });
    console.log(
      `Using externally-managed server at ${EXTERNAL_BASE_URL} ` +
        `(probing ${target.protocol}//${target.host}:${target.port}${target.path}).`,
    );
    const reachable = await waitForHttpReady(target.port, Date.now() + READY_TIMEOUT_MS, {
      host: target.host,
      path: target.path,
      protocol: target.protocol,
    });
    if (!reachable) {
      throw new Error(`External server at ${EXTERNAL_BASE_URL} did not become reachable within ${READY_TIMEOUT_MS}ms.`);
    }
    return await runScenarios(EXTERNAL_BASE_URL.replace(/\/+$/, ''));
  }

  // Publish the per-run ownership token BEFORE spawning http-server so
  // the sentinel is visible the moment the server starts serving
  // OUT_ROOT (rf2-84gzw). Cleaned up unconditionally via the registered
  // cleanup fn.
  const token = writeOwnershipToken();
  if (!token) {
    throw new Error(`Staging root missing: ${OUT_ROOT}. Did staging run?`);
  }

  // Resolve the port: prefer XRAY_FEATURE_GATE_PORT (default 8037) when
  // free, else fall back to an OS-chosen free port (rf2-84gzw). This
  // stops the gate from either failing to bind or — worse — silently
  // running against a stale/foreign listener already on the fixed port.
  const port = await resolveServePort(PREFERRED_PORT, {
    onFallback: (preferred, fallback) => {
      console.warn(
        `Preferred port ${preferred} is busy; falling back to free port ${fallback}. ` +
          `Set XRAY_FEATURE_GATE_PORT to pin a specific port.`,
      );
    },
  });
  const baseUrl = `http://127.0.0.1:${port}`;
  console.log(`Serving ${OUT_ROOT} on ${baseUrl}`);

  // Bind 127.0.0.1 (not http-server's 0.0.0.0 default): the ownership-
  // token readiness probe and the headless browser only ever hit
  // loopback, so the staged Xray bundle must not be exposed on non-
  // loopback interfaces during the gate run (rf2-utvst; matches
  // serve-and-run-adapter-smokes.cjs).
  const server = cleanup.trackProcess(spawnHarnessProcess(process.execPath, [HTTP_SERVER_BIN, OUT_ROOT, '-a', '127.0.0.1', '-p', String(port), '-s', '-c-1'], {
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

  // Readiness WITH ownership-token verification: refuse to run scenarios
  // against any server on `port` that does not serve this run's token
  // (rf2-84gzw).
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
        `A server is reachable on :${port}, but its /${TOKEN_FILE_BASENAME} ` +
          `does not match this run's ownership token. Refusing to run the Xray ` +
          `feature gate against a server this harness did not launch ` +
          `(got "${ready.got}", expected "${token}"). ` +
          `Set XRAY_FEATURE_GATE_PORT to pin a different port if this is intentional.`,
      );
    }
    if (ready.reason === 'token-never-served') {
      throw new Error(
        `http-server on :${port} became reachable but never served ` +
          `/${TOKEN_FILE_BASENAME} within ${READY_TIMEOUT_MS}ms. ` +
          `Staging root may be inconsistent.`,
      );
    }
    throw new Error(`http-server did not become reachable on :${port} within ${READY_TIMEOUT_MS}ms.`);
  }

  return await runScenarios(baseUrl);
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
