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
const {
  createHarnessCleanup,
  resolveServePort,
  startLocalHttpServer,
} = require('./lib/local-browser-harness.cjs');
const { classifyReleaseBundle } = require('./lib/read-release-bundle.cjs');
const {
  productionDepsMap,
  mapHasSymbolKey,
  mapGetKeyword,
  isMap,
  isSymbol,
} = require('./lib/edn.cjs');
// The SAME binding G-12 Arm 2 uses (rf2-5e3ic, PR #6332). Both gates read the
// same `implementation/ui/deps.edn` and ask the same question — "did the core
// coordinate resolve to the root this artefact configures?" — so they share one
// expected root and one path comparator rather than answering it twice. That
// module is require-cheap (fs/path/child_process only; `main` runs solely under
// `require.main === module`).
const {
  isConfiguredLocalRoot,
  requiredCoordinate: CORE_COORDINATE,
  requiredCoordinateLocalRoot: CORE_LOCAL_ROOT,
} = require('./check-ui-adapter-isolation.cjs');
const {
  validateWarmSamples,
  summarizeWarm,
  assertColdIsFirstDrain,
  assertColdOrderControl,
  buildSummary,
} = require('./lib/g13-timing-evidence.cjs');

const IMPL = path.resolve(__dirname, '..');
const UI_DIR = path.join(IMPL, 'ui');
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
  // rf2-vxgfnd.250 — the production-erased port candidate-inspection witness
  // (re-frame.substrate.observation). Present in the dev bundle (its owning
  // branch is reachable under goog.DEBUG=true), DCE'd from the :advanced
  // companion under goog.DEBUG=false — proving the witness is never production
  // bookkeeping.
  'RF2_G13_PORT_CANDIDATE_SENTINEL',
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
    uiDependencyBoundary:
      `production :deps: ${CORE_COORDINATE} resolved to its configured local root ` +
      `${CORE_LOCAL_ROOT}; day8/re-frame2-resources absent`,
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
  // The dependency-boundary proof is about what production `re-frame.ui`
  // pulls onto the classpath — NOT what a `:test` alias adds. The executable
  // Guide-07 server-data fixture legitimately declares day8/re-frame2-resources
  // under `:test` (it drives the real ensure/reply path), which never reaches
  // the measured shadow builds. Scope the two checks to the production `:deps`.
  //
  // Read the real top-level `:deps` map with an EDN reader and inspect its
  // actual keys — never a text slice. A balanced-brace byte scan false-greens
  // whenever a `}` hides in a comment or string, or a `:deps` hides in a `#_`
  // reader-discarded form; the parser sees EDN structure, so those variants
  // cannot slip a forbidden dependency past the boundary.
  const uiProdDeps = productionDepsMap(uiDeps, fail);
  assertCoreCoordinateBound(uiProdDeps);
  if (mapHasSymbolKey(uiProdDeps, 'day8/re-frame2-resources')) {
    fail('UI artefact dependency boundary retained day8/re-frame2-resources');
  }
}

// POSITIVE CONTROL, BOUND TO THE CONFIGURED ROOT (rf2-han0r) — the G-13 twin of
// the G-12 Arm-2 binding shipped in rf2-5e3ic. Both gates read this same
// `ui/deps.edn` asking the same question, so they are bound the same way.
//
// The structural EDN parse above is genuine — the reader sees real map
// structure, never a byte slice — and that is exactly what made this gap easy
// to miss, because the weakness sat one level UP, at the VALUE. The control
// asked only `mapHasSymbolKey(deps, 'day8/re-frame2')`: KEY PRESENCE. The
// coordinate it names was never inspected, so `{:local/root "../nowhere"}`,
// `{:mvn/version "0.0.0-BOGUS"}` and a `{:git/url ...}` coordinate all satisfied
// it. A control that passes whatever the coordinate says cannot fail, and a
// positive control that cannot fail is not evidence that the artefact under
// measurement is the one wired into the graph.
//
// Bind it to the root `ui/deps.edn` configures: `day8/re-frame2` must be a
// `{:local/root ...}` coordinate whose root, resolved relative to the artefact
// dir, IS `implementation/core`. Rejecting the Maven and git forms is
// deliberate, matching rf2-5e3ic: re-declaring core as either is a change of
// coordinate KIND, and must be made intentionally here rather than absorbed by
// a permissive check.
//
// `CORE_LOCAL_ROOT` and `isConfiguredLocalRoot` are G-12's, imported above. That
// comparator carries the hard-won cross-platform detail — the gate runs on
// Windows locally and POSIX in CI, so both sides are realpath-resolved where
// they exist (junctions/symlinks), separators unified, and drive-lettered paths
// case-folded while POSIX stays case-SENSITIVE.
function declaredCoreLocalRoot(depsMap) {
  const entry = depsMap.entries.find(([k]) => isSymbol(k, CORE_COORDINATE));
  if (!entry) return null; // coordinate absent entirely
  const coordinate = entry[1];
  if (!isMap(coordinate)) return null; // not a coordinate map at all
  const localRoot = mapGetKeyword(coordinate, 'local/root');
  // Absent for `:mvn/version` / `:git/url` coordinates; non-string values
  // (a symbol, a vector, nil) are not a root either.
  if (localRoot === null || typeof localRoot !== 'object') return null;
  if (localRoot.edn !== 'string') return null;
  return localRoot.value;
}

// Join a declared `:local/root` onto the artefact dir. The ONE place this gate
// diverges from rf2-5e3ic, and only because the two gates read different data:
// G-12 receives an already-canonicalized ABSOLUTE root from tools.deps, whereas
// `ui/deps.edn` declares a RELATIVE one (`"../core"`), so G-13 must perform the
// join itself. `path.resolve` is bound to the HOST — on Windows it would rewrite
// a POSIX `/repo/...` root to `C:\repo\...` — so pick the resolver from the
// SHAPE of the path rather than from `process.platform`. On the two hosts the
// gate actually runs on this is identical to native `path.resolve` (Windows dir
// -> win32, POSIX dir -> posix); it additionally lets the self-test drive both
// path shapes on either host, which is what makes the cross-platform teeth real
// rather than a re-run of whichever host happens to be executing.
function resolveDeclaredRoot(uiDir, declared) {
  const impl = /^[A-Za-z]:[\\/]|^\\\\/.test(uiDir) ? path.win32 : path.posix;
  return impl.resolve(uiDir, declared);
}

// Does production `:deps` resolve the core coordinate to `expectedRoot`?
// `expectedRoot`/`uiDir` are injectable so the self-test can drive Windows- and
// POSIX-shaped fixtures on either host.
function coreCoordinateResolvesTo(depsMap, expectedRoot = CORE_LOCAL_ROOT, uiDir = UI_DIR) {
  const declared = declaredCoreLocalRoot(depsMap);
  if (declared === null || declared.trim() === '') return false;
  // `:local/root` is relative to the artefact dir (an absolute root resolves to
  // itself, and still has to name the configured directory to pass).
  return isConfiguredLocalRoot(resolveDeclaredRoot(uiDir, declared), expectedRoot);
}

function assertCoreCoordinateBound(depsMap, expectedRoot = CORE_LOCAL_ROOT, uiDir = UI_DIR) {
  if (coreCoordinateResolvesTo(depsMap, expectedRoot, uiDir)) return;
  const declared = declaredCoreLocalRoot(depsMap);
  fail(
    `UI dependency positive control: production :deps does not resolve ${CORE_COORDINATE} to its ` +
      `configured local root ${expectedRoot} (declared :local/root ` +
      `${declared === null ? '<absent — not a {:local/root ...} coordinate>' : JSON.stringify(declared)}` +
      `, resolved against ${uiDir}). The coordinate must be present AND point at the core artefact ` +
      'this gate measures — a key-presence check accepted a bogus root, an :mvn/version, or a ' +
      ':git/url, none of which describe the graph the measured builds compile.',
  );
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
    // rf2-vxgfnd.250 — the production-erased port candidate-inspection witness.
    // The compiled-view commit reconciler probes/reads/current?-checks f(C)
    // candidates per drain (C=8): 8 probes, 8 reads, 16 current?-checks — all
    // functions of C, provably INDEPENDENT of V (identical at V=100 and V=500).
    // A source mutant that scans all V mounted cells inflates these; the
    // V-independence + expected-projection checks turn G-13 red.
    'port-candidate-inspections': { probe: 8, read: 8, 'current?': 16 },
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
  // rf2-vxgfnd.212 — prove the workload roster is EXACTLY one independently
  // executed V=100 then one V=500 (each row's V stamped by the mounted fixture,
  // not synthesized here) BEFORE any projection is compared. A duplicate,
  // missing, extra, or reordered size — e.g. a V=500 row silently replaced by a
  // deep copy of the V=100 row — is rejected here; equal-count checks alone
  // accept it because both rows are individually in the allowed set.
  const roster = (result.results || []).map((row) => row.v);
  if (!sameJson(roster, [100, 500])) {
    fail(`workload roster is not exactly one V=100 then one V=500: ${JSON.stringify(roster)}`);
  }
  // rf2-6k4cm — the CAUSAL cold-first order control. The fixture ran the timing
  // and correctness cycles in BOTH orders on fresh frames and reported each timed
  // cycle's OWN pre-hot witness. Because the witness rides the timed dispatch (not
  // sample! entry), the orders diverge — timing-first sees the mount seed 0 and
  // passes assertColdIsFirstDrain; correctness-first sees queued-writes and fails
  // it. This asserts the divergence, so the earlier per-size cold-first checks can
  // no longer pass vacuously: a witness that stayed 0 in both orders (captured
  // before the timed dispatch) is rejected here.
  assertColdOrderControl(result['cold-first-order-control'], result['queued-writes']);
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
    // rf2-52isf — the cold timed span must be the FIRST post-mount drain. A
    // cold `timing-pre-hot` of 0 (mount seed :hot; +queued-writes per drain)
    // proves the timer ran before any correctness dispatch or O(V) audit; a
    // nonzero value means the reported "cold" span is a warmed second dispatch.
    assertColdIsFirstDrain(size.cold, `V=${size.v} cold timing evidence`);
    if (!Array.isArray(size.samples) || size.samples.length !== 9) {
      fail(`V=${size.v} did not retain exactly nine warm samples`);
    }
    // The warm dispatch-to-commit evidence must be exactly nine finite
    // samples; empty, malformed, non-finite, or wrong-count arrays fail here
    // (the p50/p95 are the runner's nearest-rank derivation, not browser
    // output — see attachWarmSummary).
    validateWarmSamples(size.warm['raw-ms'], `V=${size.v} warm timing evidence`);
    if (!Number.isFinite(size.cold['elapsed-ms'])) {
      fail(`V=${size.v} cold timing evidence is incomplete`);
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
    // rf2-vxgfnd.250 — the production-side candidate-scan witness teeth. A source
    // mutant that scans all V mounted cells at invalidation — even one that
    // DELIVERS to only C, leaving every delivered count (port-fan-out, renders,
    // commits) identical — routes its extra inspections through the port's
    // probe / read / current? entries and inflates the witness past f(C). Each
    // inspection axis is a separate tooth; a V-scaled count must be rejected.
    ['V-wide port probe scan', (r) => {
      r.results[0].cold.projection['port-candidate-inspections'].probe = 100;
    }],
    ['V-wide port read scan', (r) => {
      r.results[0].cold.projection['port-candidate-inspections'].read = 100;
    }],
    ['V-wide port current? scan', (r) => {
      r.results[0].cold.projection['port-candidate-inspections']['current?'] = 500;
    }],
    // rf2-52isf — the cold-order tooth. If the correctness cycle (or any drain)
    // runs before the cold timer, the mount seed :hot=0 has already advanced and
    // the cold `timing-pre-hot` is nonzero. The reported span would be a warmed
    // second dispatch mislabelled cold; the order control must reject it.
    ['correctness ran before the cold timer', (r) => {
      r.results[0].cold['timing-pre-hot'] = 8;
    }],
    // rf2-6k4cm — the CAUSAL order-control teeth. Unlike the forged-field tooth
    // above (which only asserts the assertion rejects a nonzero value), these pin
    // the SOURCE-order evidence the fixture actually produced. If the pre-hot
    // witness were captured before the timed dispatch (the non-causal regression),
    // the correctness-first witness would collapse to the mount seed and the gate
    // must go red; timing-first must stay the mount seed; the control cannot vanish.
    ['order control correctness-first collapsed to the mount seed (non-causal witness)', (r) => {
      r['cold-first-order-control']['correctness-first'] = 0;
    }],
    ['order control timing-first advanced off the mount seed', (r) => {
      r['cold-first-order-control']['timing-first'] = 8;
    }],
    ['order control absent', (r) => { delete r['cold-first-order-control']; }],
    // rf2-vxgfnd.212 — workload-roster teeth. Each must fail validation, proving
    // the exact one-to-one V=100/V=500 roster before projections are compared.
    // 'duplicate V=100' is the bead's exact false-green: the V=500 row silently
    // replaced by a deep copy of the V=100 row.
    ['duplicate V=100 (V=500 row copied from V=100)', (r) => { r.results[1].v = 100; }],
    ['duplicate V=500', (r) => { r.results[0].v = 500; }],
    ['missing V=500', (r) => { r.results = [r.results[0]]; }],
    ['missing V=100', (r) => { r.results = [r.results[1]]; }],
    ['extra size', (r) => {
      const extra = JSON.parse(JSON.stringify(r.results[0]));
      extra.v = 250;
      r.results.push(extra);
    }],
    ['swapped roster order', (r) => { r.results.reverse(); }],
    ['forged metadata-only V=500 row', (r) => { r.results[1] = { v: 500 }; }],
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
    // Plant the forbidden dep INSIDE the production `:deps` map (not a test
    // alias) — the boundary proof is scoped to production deps, so the tooth
    // must inject the leak where the rule actually looks.
    assertLeaseFreeAdvanced(
      release.blob,
      uiDeps.replace(/(:deps\s*\{)/,
        '$1day8/re-frame2-resources {:local/root "../resources"} '),
      mintControl.blob,
    );
  }));
  // rf2-han0r TEETH — the positive control must be able to FAIL. Each of these
  // rewrites the core coordinate's VALUE while leaving the `day8/re-frame2` KEY
  // exactly where it is, so every one of them was green under the presence-only
  // check: the gate confirmed the coordinate was mentioned, never that it named
  // the artefact being measured.
  for (const [label, coordinate] of [
    ['core coordinate re-pointed at a non-existent root', '{:local/root "../nowhere"}'],
    ['core coordinate re-pointed at a sibling artefact', '{:local/root "../resources"}'],
    ['core coordinate downgraded to a maven version', '{:mvn/version "0.0.0-BOGUS"}'],
    ['core coordinate swapped to a git url', '{:git/url "https://example.invalid/x.git" :git/sha "abc1234"}'],
  ]) {
    red.push(expectMutationFailure(label, () => {
      assertLeaseFreeAdvanced(
        release.blob,
        uiDeps.replace('day8/re-frame2 {:local/root "../core"}', `day8/re-frame2 ${coordinate}`),
        mintControl.blob,
      );
    }));
  }
  // The coordinate absent altogether — the shape the presence check DID catch,
  // pinned here so binding the value did not cost the original tooth.
  red.push(expectMutationFailure('core coordinate absent from production :deps', () => {
    assertLeaseFreeAdvanced(
      release.blob,
      uiDeps.replace('day8/re-frame2 {:local/root "../core"}', ''),
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

// Derive the labelled p50/p95 from the raw warm samples (the browser emits
// only the raw array, so there is ONE quantile convention, applied here) and
// fold them back onto each size so the JSON artifact carries raw + p50 + p95.
function attachWarmSummary(result) {
  for (const size of result.results || []) {
    size.warm = summarizeWarm(size.warm['raw-ms'], `V=${size.v} warm timing evidence`);
  }
  return result;
}

function appendSummary(report) {
  const target = process.env.GITHUB_STEP_SUMMARY;
  if (!target || report.status !== 'pass') return;
  fs.appendFileSync(target, buildSummary(report.development.results), 'utf8');
}

async function main() {
  // Required here, not at module load, so the boundary predicate above can be
  // require()'d by `_ui-deps-edn-boundary.test.cjs` — a node-only suite that
  // must pin the REAL gate rule rather than a hand-copied restatement of it —
  // without dragging Playwright into that suite. `chromium` is used only below.
  const { chromium } = require('playwright');
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
    // Fold the nearest-rank p50/p95 onto each size so the JSON artifact and
    // step summary carry raw + p50 + p95 (single stated quantile convention).
    attachWarmSummary(devState.result);

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

module.exports = {
  assertDevResult,
  attachWarmSummary,
  appendSummary,
  expectedProjection,
  // The bound dependency-boundary rule, exported so `_ui-deps-edn-boundary.test.cjs`
  // pins THIS predicate rather than a hand-copied restatement that can drift.
  declaredCoreLocalRoot,
  coreCoordinateResolvesTo,
  assertCoreCoordinateBound,
  CORE_COORDINATE,
  CORE_LOCAL_ROOT,
  UI_DIR,
};

// CLI entry-point (skipped when require()'d by the test suite).
if (require.main === module) {
  main().catch((error) => {
    console.error(error.stack || String(error));
    process.exitCode = 1;
  });
}
