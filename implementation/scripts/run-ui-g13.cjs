#!/usr/bin/env node
'use strict';

// G-13 owns an isolated real-browser fixture. Correctness is exact work
// accounting (not a wall-clock budget); timing is retained only as evidence.
// The companion :advanced build proves the gate's counters, React Profiler,
// debug evidence and result channel do not ship with the ordinary view.

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const { isDeepStrictEqual } = require('util');
const { chromium } = require('playwright');
const {
  createHarnessCleanup,
  resolveServePort,
  startLocalHttpServer,
} = require('./lib/local-browser-harness.cjs');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');

const IMPL = path.resolve(__dirname, '..');
const OUT = path.join(IMPL, 'out');
const DEV = path.join(OUT, 'ui-g13');
const PROD = path.join(OUT, 'ui-g13-prod');
const MINT_CONTROL = path.join(OUT, 'ui-g13-mint-control');
const PROD_MANIFEST = path.join(PROD, 'manifest.edn');
const REPORT = path.join(OUT, 'ui-g13.json');
const TIMEOUT = 90000;
const SENTINELS = [
  'RF2_G13_COUNTER_SENTINEL',
  'RF2_G13_PROFILER_SENTINEL',
  'RF2_G13_DEBUG_EVIDENCE_SENTINEL',
  'RF2_UI_RESOURCE_LEASE_EVIDENCE_SENTINEL',
  '__RF2_G13_RESULT_SENTINEL__',
];
const LEASE_FREE_FORBIDDEN = [
  'rf.resource/ensure',
  'rf.resource/release-owner',
  'RF2_UI_RESOURCE_LEASE_EVIDENCE_SENTINEL',
  'resources/reg-resource',
];
const RESOURCE_BOOKKEEPING_FORBIDDEN = [
  'resource-order',
  'resource-by-site',
  'resource-desired',
  'resource-capture',
  'resource-reservations',
  'resource-held-order',
  'resource-lifecycle',
];
const OWNER_MINT_SENTINEL = 'RF2_RESOURCE_LEASE_OWNER_MINT_SENTINEL';
const OPTIONAL_RESOURCES_SENTINEL = 'rf.resource.internal/page-succeeded';

function fail(message) {
  throw new Error(`G-13 FAIL: ${message}`);
}

function resolveBin(modulePath) {
  return require.resolve(modulePath, { paths: [IMPL] });
}

function shadow(...args) {
  const runner = resolveBin('shadow-cljs/cli/runner.js');
  console.log(`> shadow-cljs ${args.join(' ')}`);
  const result = spawnSync(process.execPath, [runner, ...args], {
    cwd: IMPL,
    env: process.env,
    shell: false,
    stdio: 'inherit',
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

function recursiveJsBlob(dir) {
  const chunks = [];
  function walk(at) {
    for (const entry of fs.readdirSync(at, { withFileTypes: true })) {
      const p = path.join(at, entry.name);
      if (entry.isDirectory()) walk(p);
      else if (entry.isFile() && entry.name.endsWith('.js')) {
        chunks.push(fs.readFileSync(p, 'utf8'));
      }
    }
  }
  walk(dir);
  return chunks.join('\n');
}

function assertBundleElision() {
  const devBlob = recursiveJsBlob(DEV);
  for (const sentinel of SENTINELS) {
    if (!devBlob.includes(sentinel)) {
      fail(`development non-vacuity control omitted ${sentinel}`);
    }
  }
  const release = classifyReleaseBundle(PROD);
  if (release.status !== 'ok') {
    fail(`advanced companion bundle is ${release.status}, not inspectable`);
  }
  const mintControl = classifyReleaseBundle(MINT_CONTROL);
  if (mintControl.status !== 'ok') {
    fail(`advanced mint control bundle is ${mintControl.status}, not inspectable`);
  }
  const prodManifest = fs.readFileSync(PROD_MANIFEST, 'utf8');
  assertNoProductionSentinels(release.blob);
  assertLeaseFreeAdvanced(release.blob, null, mintControl.blob);
  assertOptionalResourcesManifest(prodManifest);
  return {
    devSentinelsPresent: SENTINELS,
    advancedSentinelsAbsent: SENTINELS,
    leaseFreeAdvancedBytes: Buffer.byteLength(release.blob, 'utf8'),
    ownerMintAdvancedControlBytes: Buffer.byteLength(mintControl.blob, 'utf8'),
    leaseFreeForbiddenAbsent: LEASE_FREE_FORBIDDEN,
    resourceBookkeepingAbsent: RESOURCE_BOOKKEEPING_FORBIDDEN,
    ownerMintAdvancedControlPresent: OWNER_MINT_SENTINEL,
    ownerMintSentinelAbsent: OWNER_MINT_SENTINEL,
    optionalResourcesSentinelAbsent: OPTIONAL_RESOURCES_SENTINEL,
    optionalResourcesManifestAbsent: 're_frame/resources.cljc and re_frame/resources/**',
    uiDependencyBoundary: 'day8/re-frame2 present; day8/re-frame2-resources absent',
  };
}

function assertOptionalResourcesManifest(manifest) {
  if (!manifest.includes('re_frame/core_resources.cljc')) {
    fail('advanced source manifest omitted core Resources façade positive control');
  }
  const optionalSource = manifest.match(/re_frame\/resources(?:\.cljc|\/)[^"\s]*/);
  if (optionalSource) {
    fail(`advanced source graph retained optional Resources source ${optionalSource[0]}`);
  }
}

function assertNoProductionSentinels(blob) {
  for (const sentinel of SENTINELS) {
    if (blob.includes(sentinel)) fail(`advanced companion leaked ${sentinel}`);
  }
}

function assertLeaseFreeAdvanced(blob, uiDepsOverride = null, mintControlBlob = null) {
  const reactiveSource = fs.readFileSync(
    path.join(IMPL, 'ui', 'src', 're_frame', 'ui', 'reactive.cljc'), 'utf8');
  const ownerSource = fs.readFileSync(
    path.join(IMPL, 'core', 'src', 're_frame', 'resource_lease_owner.cljc'), 'utf8');
  const featuresSource = fs.readFileSync(
    path.join(IMPL, 'core', 'src', 're_frame', 'features.cljc'), 'utf8');
  const resourcesSource = fs.readFileSync(
    path.join(IMPL, 'resources', 'src', 're_frame', 'resources.cljc'), 'utf8');
  const uiDeps = uiDepsOverride || fs.readFileSync(
    path.join(IMPL, 'ui', 'deps.edn'), 'utf8');
  const source = `${reactiveSource}\n${featuresSource}`;
  for (const token of LEASE_FREE_FORBIDDEN) {
    if (!source.includes(token)) {
      fail(`lease-free DCE positive-control source omitted ${token}`);
    }
    if (blob.includes(token)) {
      fail(`lease-free advanced companion retained ${token}`);
    }
  }
  for (const token of RESOURCE_BOOKKEEPING_FORBIDDEN) {
    if (!reactiveSource.includes(token)) {
      fail(`resource-bookkeeping positive-control source omitted ${token}`);
    }
    if (blob.includes(token)) {
      fail(`lease-free advanced companion retained resource bookkeeping ${token}`);
    }
  }
  for (const token of [
    '(ns re-frame.resource-lease-owner',
    '(defn mint!',
    OWNER_MINT_SENTINEL,
  ]) {
    if (!ownerSource.includes(token)) {
      fail(`owner-mint positive-control source omitted ${token}`);
    }
  }
  if (!mintControlBlob || !mintControlBlob.includes(OWNER_MINT_SENTINEL)) {
    fail(`advanced owner-mint control omitted sentinel ${OWNER_MINT_SENTINEL}`);
  }
  if (blob.includes(OWNER_MINT_SENTINEL)) {
    fail(`lease-free advanced companion retained owner mint sentinel ${OWNER_MINT_SENTINEL}`);
  }
  if (!resourcesSource.includes(OPTIONAL_RESOURCES_SENTINEL)) {
    fail(`optional Resources positive-control source omitted ${OPTIONAL_RESOURCES_SENTINEL}`);
  }
  if (blob.includes(OPTIONAL_RESOURCES_SENTINEL)) {
    fail(`lease-free advanced companion retained optional Resources sentinel ${OPTIONAL_RESOURCES_SENTINEL}`);
  }
  if (!/day8\/re-frame2\s+\{/.test(uiDeps)) {
    fail('UI dependency positive control omitted day8/re-frame2');
  }
  if (/day8\/re-frame2-resources\s+\{/.test(uiDeps)) {
    fail('UI artefact dependency boundary retained day8/re-frame2-resources');
  }
}

function expectedProjection() {
  return {
    enrolled: 8,
    advances: 8,
    'revision-delta': 8,
    'hot-renders': 8,
    'hot-renders-by-index': [1, 1, 1, 1, 1, 1, 1, 1],
    'cold-renders': 0,
    'root-commits': 1,
    'hot-base': 8,
    'stable-parent': 8,
    'cold-leaf': 0,
    // rf2-vxgfnd.210 — the named candidate-work axis. `port-fan-out` is the
    // observation port's total DELIVERED fan-out for the drain (C*Q=64),
    // summed over ALL V live cells; `fan-out-cells` is the number of cells
    // that received any fold (exactly C=8). Both are functions of C and Q,
    // independent of V.
    'port-fan-out': 64,
    'fan-out-cells': 8,
  };
}

function sameJson(a, b) {
  return isDeepStrictEqual(a, b);
}

function assertDevResult(result) {
  if (!result || result.gate !== 'G-13' || result.status !== 'pass') {
    fail(`invalid development result: ${JSON.stringify(result)}`);
  }
  if (!sameJson(result.sizes, [100, 500])) fail('fixture sizes are not [100,500]');
  if (result['queued-writes'] !== 8 || result['affected-viewcells'] !== 8) {
    fail('Q/C cardinalities are not 8/8');
  }
  if (result['fixed-shell-idle-cells'] !== 2) {
    fail('fixed HMR shell cardinality is not exactly two ownership-empty cells');
  }
  if (result['timing-posture'] !== 'evidence-only; no threshold') {
    fail('timing evidence grew a threshold posture');
  }
  const expected = expectedProjection();
  const projections = [];
  for (const size of result.results || []) {
    if (![100, 500].includes(size.v)) fail(`unexpected V=${size.v}`);
    if (!sameJson(size.cold.projection, expected)) {
      fail(`cold projection drift at V=${size.v}: ${JSON.stringify(size.cold.projection)}`);
    }
    if (size.cold['fixed-shell-idle-cells'] !== 2) {
      fail(`cold sample at V=${size.v} lost the two ownership-empty HMR shells`);
    }
    if (!Array.isArray(size.samples) || size.samples.length !== 9) {
      fail(`V=${size.v} did not retain exactly nine warm samples`);
    }
    if (!Array.isArray(size.warm['raw-ms']) || size.warm['raw-ms'].length !== 9) {
      fail(`V=${size.v} raw timing evidence is not the odd nine-sample set`);
    }
    if (!Number.isFinite(size.cold['elapsed-ms']) ||
        !Number.isFinite(size.warm['p50-ms']) ||
        !Number.isFinite(size.warm['p95-ms'])) {
      fail(`V=${size.v} timing evidence is incomplete`);
    }
    for (const sample of size.samples) {
      if (!sameJson(sample.projection, expected)) {
        fail(`warm projection drift at V=${size.v}/${sample.label}`);
      }
      if (sample['fixed-shell-idle-cells'] !== 2) {
        fail(`warm sample at V=${size.v}/${sample.label} lost the empty HMR shells`);
      }
    }
    projections.push(size.cold.projection);
  }
  if (projections.length !== 2 || !sameJson(projections[0], projections[1])) {
    fail('count projection is not V-independent');
  }
}

function expectMutationFailure(label, thunk) {
  try {
    thunk();
  } catch (_) {
    return label;
  }
  fail(`mutation tooth was green: ${label}`);
}

function assertMutationTeeth(result) {
  const mutations = [
    ['per-write flushing', (r) => { r.results[0].cold.projection['revision-delta'] = 64; }],
    ['V-wide enrollment', (r) => { r.results[0].cold.projection.enrolled = 100; }],
    ['stable-parent fan-out', (r) => { r.results[0].cold.projection['cold-leaf'] = 92; }],
    ['multiple root commits', (r) => { r.results[0].cold.projection['root-commits'] = 8; }],
    ['same-total uneven hot distribution', (r) => {
      r.results[0].cold.projection['hot-renders-by-index'] = [2, 0, 1, 1, 1, 1, 1, 1];
    }],
    // rf2-vxgfnd.210 — the candidate-work axis teeth. A V-wide port fan-out
    // (the observation port delivering to V owners instead of C) inflates the
    // summed occurrence total; a fan-out that reaches any non-affected cell
    // grows `fan-out-cells` past C. Both must be rejected.
    ['V-wide port fan-out', (r) => { r.results[0].cold.projection['port-fan-out'] = 6400; }],
    ['fan-out reached a non-affected cell', (r) => {
      r.results[0].cold.projection['fan-out-cells'] = 100;
    }],
  ];
  const red = mutations.map(([label, mutate]) => {
    const changed = JSON.parse(JSON.stringify(result));
    mutate(changed);
    return expectMutationFailure(label, () => assertDevResult(changed));
  });
  const release = classifyReleaseBundle(PROD);
  const mintControl = classifyReleaseBundle(MINT_CONTROL);
  const prodManifest = fs.readFileSync(PROD_MANIFEST, 'utf8');
  red.push(expectMutationFailure('production instrumentation leak', () => {
    assertNoProductionSentinels(`${release.blob}\n${SENTINELS[0]}`);
  }));
  red.push(expectMutationFailure('lease-free resource reachability leak', () => {
    assertLeaseFreeAdvanced(`${release.blob}\n${LEASE_FREE_FORBIDDEN[0]}`, null, mintControl.blob);
  }));
  red.push(expectMutationFailure('lease-free resource bookkeeping leak', () => {
    assertLeaseFreeAdvanced(`${release.blob}\n${RESOURCE_BOOKKEEPING_FORBIDDEN[2]}`, null, mintControl.blob);
  }));
  red.push(expectMutationFailure('lease-free neutral owner mint reachability', () => {
    assertLeaseFreeAdvanced(`${release.blob}\n${OWNER_MINT_SENTINEL}`, null, mintControl.blob);
  }));
  red.push(expectMutationFailure('owner mint advanced-control non-vacuity', () => {
    assertLeaseFreeAdvanced(
      release.blob,
      null,
      mintControl.blob.split(OWNER_MINT_SENTINEL).join('owner_control_removed'),
    );
  }));
  red.push(expectMutationFailure('optional Resources runtime reachability', () => {
    assertLeaseFreeAdvanced(`${release.blob}\n${OPTIONAL_RESOURCES_SENTINEL}`, null, mintControl.blob);
  }));
  const uiDeps = fs.readFileSync(path.join(IMPL, 'ui', 'deps.edn'), 'utf8');
  red.push(expectMutationFailure('optional Resources dependency leak', () => {
    assertLeaseFreeAdvanced(
      release.blob,
      `${uiDeps}\nday8/re-frame2-resources {:local/root "../resources"}`,
      mintControl.blob,
    );
  }));
  red.push(expectMutationFailure('optional Resources source-graph leak', () => {
    assertOptionalResourcesManifest(
      `${prodManifest}\n"re_frame/resources/internal/runtime.cljs"`,
    );
  }));
  return red;
}

function writeReport(report) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(REPORT, `${JSON.stringify(report, null, 2)}\n`, 'utf8');
}

function appendSummary(report) {
  const target = process.env.GITHUB_STEP_SUMMARY;
  if (!target || report.status !== 'pass') return;
  const lines = [
    '### re-frame.ui G-13 push economics',
    '',
    '| V | cold ms | warm p50 ms | warm p95 ms | exact projection |',
    '|---:|---:|---:|---:|:---:|',
  ];
  for (const row of report.development.results) {
    lines.push(`| ${row.v} | ${row.cold['elapsed-ms'].toFixed(3)} | ` +
      `${row.warm['p50-ms'].toFixed(3)} | ${row.warm['p95-ms'].toFixed(3)} | yes |`);
  }
  lines.push('', 'Timing is evidence-only; G-13 has no wall-clock threshold.', '');
  fs.appendFileSync(target, `${lines.join('\n')}\n`, 'utf8');
}

async function main() {
  const cleanup = createHarnessCleanup();
  cleanup.installSignalHandlers();
  let browser;
  let tearingDown = false;
  try {
    shadow('compile', 'ui-g13');
    shadow('release', 'ui-g13-prod', 'ui-g13-mint-control');
    writePage(DEV);
    writePage(PROD);
    const bundles = assertBundleElision();

    const devPort = await resolveServePort(Number(process.env.UI_G13_PORT) || 8061);
    const prodPort = await resolveServePort(Number(process.env.UI_G13_PROD_PORT) || 8062);
    const httpServerBin = resolveBin('http-server/bin/http-server');
    const devServer = await startLocalHttpServer({
      cleanup, httpServerBin, root: DEV, port: devPort, cwd: IMPL,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!devServer.ready) fail('development server did not prove owned readiness');
    const prodServer = await startLocalHttpServer({
      cleanup, httpServerBin, root: PROD, port: prodPort, cwd: IMPL,
      suppressExitDiagnostic: () => tearingDown,
    });
    if (!prodServer.ready) fail('advanced server did not prove owned readiness');

    browser = await chromium.launch({ headless: true });
    const dev = await browser.newPage();
    const pageErrors = [];
    dev.on('pageerror', (e) => pageErrors.push(e.stack || String(e)));
    await dev.goto(`http://127.0.0.1:${devPort}/index.html`);
    await dev.waitForFunction(
      () => globalThis.__RF2_G13_RESULT_SENTINEL__ || globalThis.__RF2_G13_ERROR__,
      null,
      { timeout: TIMEOUT },
    );
    const devState = await dev.evaluate(() => ({
      result: globalThis.__RF2_G13_RESULT_SENTINEL__ || null,
      error: globalThis.__RF2_G13_ERROR__ || null,
    }));
    if (devState.error) fail(devState.error);
    if (pageErrors.length) fail(`development page errors:\n${pageErrors.join('\n')}`);
    assertDevResult(devState.result);
    const mutationTeeth = assertMutationTeeth(devState.result);

    const prod = await browser.newPage();
    const prodErrors = [];
    prod.on('pageerror', (e) => prodErrors.push(e.stack || String(e)));
    await prod.goto(`http://127.0.0.1:${prodPort}/index.html`);
    await prod.waitForSelector('[data-g13-ready="true"]', { timeout: TIMEOUT });
    const prodState = await prod.evaluate(() => ({
      rows: document.querySelectorAll('[data-g13-kind]').length,
      hot: document.querySelectorAll('[data-g13-kind="hot"]').length,
      cold: document.querySelectorAll('[data-g13-kind="cold"]').length,
      firstHot: document.querySelector('[data-g13-kind="hot"]')?.textContent,
      lastCold: document.querySelector('[data-g13-index="99"]')?.textContent,
      resultGlobal: typeof globalThis.__RF2_G13_RESULT_SENTINEL__,
    }));
    if (prodErrors.length) fail(`advanced page errors:\n${prodErrors.join('\n')}`);
    if (!sameJson(prodState, {
      rows: 100, hot: 8, cold: 92, firstHot: '0', lastCold: 'cold-99',
      resultGlobal: 'undefined',
    })) {
      fail(`advanced DOM result drift: ${JSON.stringify(prodState)}`);
    }

    const report = {
      gate: 'G-13',
      status: 'pass',
      correctness: 'exact counts; one post-drain root commit',
      timing: 'evidence-only; no threshold',
      mutationTeeth,
      development: devState.result,
      advanced: { bundles, dom: prodState },
    };
    writeReport(report);
    appendSummary(report);
    console.log(`G-13 PASS — report: ${REPORT}`);
  } catch (error) {
    writeReport({ gate: 'G-13', status: 'fail', error: error.stack || String(error) });
    throw error;
  } finally {
    tearingDown = true;
    if (browser) await browser.close();
    await cleanup.cleanup();
  }
}

main().catch((error) => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
