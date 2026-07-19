#!/usr/bin/env node
/*
 * Advanced-production proof for the mounted ViewCell override carriage.
 *
 * The source assertion is the positive control: the marker still names the
 * DEBUG-only branch containing the dynamic binding.  The release-bundle
 * assertion proves Closure removed that entire branch from the exact browser
 * artifact which mounts a compiled ui/sub ViewCell in the companion test.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui', 'viewcell.cljs');
const REACTIVE_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui',
                                  'reactive.cljc');
const ANALYZER_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui',
                                  'compiler', 'analyze.cljc');
const BUNDLE = path.join(ROOT, 'out', 'browser-test-prod-elision', 'js', 'test.js');
const SENTINEL = 'rf-ui-mounted-override-binding expects a map';

function fail(message) {
  process.stderr.write(`ui mounted production elision: FAIL\n${message}\n`);
  process.exit(1);
}

if (!fs.existsSync(BUNDLE)) {
  fail(`missing advanced release bundle: ${path.relative(ROOT, BUNDLE)}`);
}

const source = fs.readFileSync(SOURCE, 'utf8');
const occurrences = source.split(SENTINEL).length - 1;
if (occurrences !== 1) {
  fail(`expected exactly one source sentinel, found ${occurrences}`);
}

const reactiveSource = fs.readFileSync(REACTIVE_SOURCE, 'utf8');
if (!reactiveSource.includes('disconnect-provisional?')) {
  fail('provisional-disconnect source positive-control sentinel is absent');
}

const analyzerSource = fs.readFileSync(ANALYZER_SOURCE, 'utf8');
for (const expected of ['sid1-', ':expr-path']) {
  if (!analyzerSource.includes(expected)) {
    fail(`compiler source positive-control sentinel is absent: ${expected}`);
  }
}

const bundle = fs.readFileSync(BUNDLE, 'utf8');
if (bundle.includes(SENTINEL)) {
  fail('DEBUG-only override binding branch survived in the advanced bundle');
}

if (!/sid1-[A-Za-z0-9_-]+/.test(bundle)) {
  fail('compact compiler lexical site id is absent from the advanced hot path');
}

for (const forbidden of [
  'expr-path',
  'hmr-body-revision',
  'hmr-remount-generation',
  'hmr-descriptor',
  'hmr-inner',
  // rf2-vxgfnd.165: the advanced fixture roots make-cell, disconnect!, the
  // settle seam, and reconnect. Any unconditional state member OR reconnect
  // lookup therefore carries this keyword into the bundle and fails here.
  'disconnect-provisional'
]) {
  if (bundle.includes(forbidden)) {
    fail(`development site/HMR diagnostic survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-vxgfnd.75 — the tool-tier invalidation-evidence projection.
 *
 * The elision companion test requires re-frame.ui.tool.evidence into this
 * exact bundle, so these are positive proofs of erasure (the namespace is
 * present; its debug-gated projection is not), not absence-by-omission.
 * Source positive controls first: the sentinels still name the debug-only
 * accumulation (`:dropped-exact?` — the rf2-vxgfnd.74 honest-loss keyword,
 * minted only inside debug-gated folds in reactive.cljc + the projection)
 * and the projection's rejected-install diagnostic string.
 */
const TOOL_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui', 'tool',
                              'evidence.cljc');
const toolSource = fs.readFileSync(TOOL_SOURCE, 'utf8');
for (const expected of ['dropped-exact', 'invalidation-evidence projection']) {
  if (!toolSource.includes(expected)) {
    fail(`tool-evidence source positive-control sentinel is absent: ${expected}`);
  }
}

/*
 * Compiled behavioural positive control (rf2-vxgfnd.149): the advanced test
 * roots the PRIVATE state var and asserts its value is nil before and after
 * force-release!. The marker must survive in this bundle, so a mutant which
 * merely renames/minifies an unconditional atom/reset path still fails at
 * runtime; this is deliberately stronger than another bundle substring.
 */
const TOOL_PROD_TEST = path.join(ROOT, 'ui', 'test', 're_frame', 'ui',
                                'tool_evidence_elision_prod_test.cljs');
const toolProdTest = fs.readFileSync(TOOL_PROD_TEST, 'utf8');
const REGISTRY_CONTROL = 'rf-ui-tool-evidence-registry-state-absent-v1';
for (const expected of ["@#'evidence/state*", REGISTRY_CONTROL]) {
  if (!toolProdTest.includes(expected)) {
    fail(`tool-evidence compiled state-elision control is absent: ${expected}`);
  }
}
if (!bundle.includes(REGISTRY_CONTROL)) {
  fail('tool-evidence private-state assertion was omitted from advanced bundle');
}

for (const forbidden of ['dropped-exact', 'invalidation-evidence projection']) {
  if (bundle.includes(forbidden)) {
    fail(`invalidation-evidence projection survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-vxgfnd.95.6 — the DEV-ONLY re-frame.ui.tool projection tier (the five
 * frozen projections: view-manifest / mounted-views / explain-render /
 * view-dependencies / view-event-sites).
 *
 * The elision companion (tool_view_elision_prod_test) requires re-frame.ui.tool
 * into this SAME bundle and CALLS every projection, so these are positive proofs
 * of BODY-erasure: the facade vars survive (they are called) while their
 * debug-gated projection SHAPES do not. Source positive controls first — the
 * projection-shape keyword strings still name the gated bodies in source.
 */
const TOOL_FACADE_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui',
                                     'tool.cljc');
const toolFacadeSource = fs.readFileSync(TOOL_FACADE_SOURCE, 'utf8');
for (const expected of ['identity-exact', 'site-counts']) {
  if (!toolFacadeSource.includes(expected)) {
    fail(`tool-facade source positive-control sentinel is absent: ${expected}`);
  }
}

const TOOL_VIEW_PROD_TEST = path.join(ROOT, 'ui', 'test', 're_frame', 'ui',
                                      'tool_view_elision_prod_test.cljs');
const toolViewProdTest = fs.readFileSync(TOOL_VIEW_PROD_TEST, 'utf8');
const TOOL_PROJECTIONS_CONTROL = 'rf-ui-tool-projections-inert-v1';
if (!toolViewProdTest.includes(TOOL_PROJECTIONS_CONTROL)) {
  fail(`tool-facade inert-projection control is absent from its test: ${TOOL_PROJECTIONS_CONTROL}`);
}
if (!bundle.includes(TOOL_PROJECTIONS_CONTROL)) {
  fail('tool-facade inert-projection assertion was omitted from advanced bundle');
}
for (const forbidden of ['identity-exact', 'site-counts']) {
  if (bundle.includes(forbidden)) {
    fail(`re-frame.ui.tool projection shape survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-k9yuy — the custom-element hot-reload LEDGER must not reach production.
 *
 * `re-frame.ui.rules` keeps a source/cycle ledger so a hot reload can stage,
 * reconcile and evict custom-element declarations. Production can never open a
 * reload cycle (`notify-reload!` is a ^:dev/before-load hook shadow does not
 * install in a release build), so that state is pure dead weight there — yet it
 * used to ship: the atom was an unconditional `defonce` and every production
 * registration `swap-vals!`'d against it. The `reload-ledger?` compile-time gate
 * now folds it out.
 *
 * The ledger's root keys are NAMESPACE-QUALIFIED (`::sources` / `::cycles`)
 * precisely so this grep has teeth: `re-frame.ui.rules/sources` and
 * `re-frame.ui.rules/cycles` are minted by that ledger and nothing else, whereas
 * bare `sources` / `cycles` would collide across the corpus. Closure renames
 * symbols under :advanced but never rewrites string literals, and a CLJS keyword
 * carries its fully-qualified name as one — so if either appears here, the
 * ledger compiled in.
 *
 * Source positive control first: the keys must still BE namespace-qualified in
 * rules.cljc, so a rename cannot turn this into a vacuous pass. The dev-positive
 * control is `re-frame.ui.rules-cljs-test` (node, goog.DEBUG=true), which stages,
 * reconciles and commits real reload cycles through those same keys — deleting
 * the dev branch to satisfy this grep reddens there instead.
 */
const RULES_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui', 'rules.cljc');
const rulesSource = fs.readFileSync(RULES_SOURCE, 'utf8');
for (const expected of ['::sources', '::cycles', 'reload-ledger?']) {
  if (!rulesSource.includes(expected)) {
    fail(`reload-ledger source positive-control sentinel is absent: ${expected}`);
  }
}

const LEDGER_PROD_TEST = path.join(ROOT, 'ui', 'test', 're_frame', 'ui',
                                   'custom_element_reload_elision_prod_test.cljs');
const ledgerProdTest = fs.readFileSync(LEDGER_PROD_TEST, 'utf8');
const LEDGER_CONTROL = 'rf-ui-custom-element-reload-ledger-absent-v1';
for (const expected of ["@#'rules/ledger-state", LEDGER_CONTROL]) {
  if (!ledgerProdTest.includes(expected)) {
    fail(`reload-ledger causal-oracle control is absent from its test: ${expected}`);
  }
}
if (!bundle.includes(LEDGER_CONTROL)) {
  fail('reload-ledger private-state assertion was omitted from advanced bundle');
}

for (const forbidden of ['re-frame.ui.rules/sources', 're-frame.ui.rules/cycles']) {
  if (bundle.includes(forbidden)) {
    fail(`custom-element reload ledger survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-vxgfnd.143 — the conflict law must SURVIVE where the ledger does not.
 *
 * The two scans above prove the reload ledger is absent from the release. On
 * their own they would be equally satisfied by deleting the cross-source
 * declaration law along with it — which is precisely the failure the placement
 * amendment exists to prevent, and precisely how rf2-2hkfy (#6376) and
 * rf2-5pr75 (#6352) shipped. So the same bundle must also carry the production
 * enforcement pin: a deftest that registers a contradictory declaration under
 * goog.DEBUG=false and asserts it is rejected with both [build-id ns-sym]
 * anchors while the prior entry survives.
 *
 * Same oracle as LEDGER_CONTROL above — a string literal, which Closure never
 * rewrites — so DCE-ing or deleting that deftest reddens HERE rather than
 * silently shrinking the production suite's test count.
 */
const CONFLICT_CONTROL = 'rf-ui-custom-element-conflict-law-live-v1';
if (!ledgerProdTest.includes(CONFLICT_CONTROL)) {
  fail(`custom-element conflict-law production pin is absent from its test: ${CONFLICT_CONTROL}`);
}
if (!bundle.includes(CONFLICT_CONTROL)) {
  fail('custom-element conflict-law production pin was omitted from advanced bundle');
}

/*
 * rf2-kxork — G-11 owns the view-payload absence claims that G-18 used to
 * over-promise.
 *
 * The EP G-18 roster rows asserted that a one-view import retains no unused
 * sibling views, SCHEMAS, DOCS PROJECTIONS, or DEV REGISTRATION, while the
 * shipped G-18 gate (`check-ui-facade-isolation.cjs`) only ever measured
 * sibling render sentinels. `spec/conformance/S3-view-conformance-profile.md`
 * already assigns the exact production-absence rosters to G-11, so the EP rows
 * were narrowed to sibling isolation and the residual three arms land HERE —
 * in the gate that owns them — rather than being silently retired.
 *
 * Each arm is a source positive control plus a bundle absence, the pattern used
 * throughout this file. The absences are NOT vacuous: this bundle roots and
 * really RENDERS `js-hint-view` (binding_plan_advanced_elision_prod_test calls
 * `markup` on it and asserts the committed HTML), and it links
 * `re-frame.ui.runtime`, whose `view-shell-mark` is the registration marker.
 * So each literal has a live compiled carrier in this exact artifact; if the
 * dev-only material rode along, it would be here.
 */
const RUNTIME_SOURCE = path.join(ROOT, 'ui', 'src', 're_frame', 'ui',
                                 'runtime.cljs');
const runtimeSource = fs.readFileSync(RUNTIME_SOURCE, 'utf8');
const VIEW_SHELL_MARK = 'rf$view_shell';
if (!runtimeSource.includes(`(def ^:private view-shell-mark "${VIEW_SHELL_MARK}")`)) {
  fail(`dev-registration source positive control is absent: view-shell-mark "${VIEW_SHELL_MARK}"`);
}

const BINDING_PLAN_PROD_TEST = path.join(ROOT, 'ui', 'test', 're_frame', 'ui',
                                         'binding_plan_advanced_elision_prod_test.cljs');
const bindingPlanProdTest = fs.readFileSync(BINDING_PLAN_PROD_TEST, 'utf8');
const SCHEMA_DOC_SENTINEL = 'rf2-g11-schema-doc-sentinel-v1';
const VIEW_DOCSTRING_SENTINEL = 'rf2-g11-view-docstring-sentinel-v1';

// The carrier must be a REALLY RENDERED view, not merely a declared one — an
// unrendered defview could be DCE'd wholesale, which would make both absences
// below pass for the wrong reason.
if (!/\(markup js-hint-view\b/.test(bindingPlanProdTest)) {
  fail('G-11 payload sentinels lost their rendered carrier: binding_plan_advanced_elision_prod_test no longer renders js-hint-view');
}
for (const expected of [
  `{:props [:map {:doc "${SCHEMA_DOC_SENTINEL}"}]}`,
  `"${VIEW_DOCSTRING_SENTINEL} `,
]) {
  if (!bindingPlanProdTest.includes(expected)) {
    fail(`G-11 view-payload source positive control is absent: ${expected}`);
  }
}

for (const [forbidden, what] of [
  [VIEW_SHELL_MARK, 'DEV view registration (view-shell-mark)'],
  [SCHEMA_DOC_SENTINEL, 'view props-schema metadata'],
  [VIEW_DOCSTRING_SENTINEL, 'view docstring / docs projection'],
]) {
  if (bundle.includes(forbidden)) {
    fail(`${what} survived advanced output: ${forbidden}`);
  }
}

/*
 * rf2-vxgfnd.94.8 — REAL compiled mutation control. A second advanced build
 * flips a private goog-define which roots an unrelatedly named atom/reset path
 * inside re-frame.ui.tool.evidence. The same production test must turn red on
 * that exact minified artefact. This proves the runtime private-root oracle has
 * teeth beyond source strings; it is intentionally scoped to evidence rather
 * than pretending to be a general JavaScript heap analyser.
 */
for (const expected of ['elision-negative-control?', 'cacheline*']) {
  if (!toolSource.includes(expected)) {
    fail(`tool-evidence renamed-state mutation control is absent: ${expected}`);
  }
}

const MUTANT_ROOT = path.join(ROOT, 'out', 'browser-test-prod-elision-mutant');
const shadowRunner = require.resolve('shadow-cljs/cli/runner.js');
const configMerge = [
  '{:test-dir "out/browser-test-prod-elision-mutant"',
  ':devtools {:http-root "out/browser-test-prod-elision-mutant" :http-port 8024}',
  ':compiler-options {:closure-defines',
  '{goog.DEBUG false',
  're-frame.ui.tool.evidence/elision-negative-control? true}}}',
].join(' ');
const mutantBuild = spawnSync(
  process.execPath,
  [shadowRunner, 'release', 'browser-test-prod-elision',
   '--config-merge', configMerge],
  { cwd: ROOT, encoding: 'utf8', maxBuffer: 8 * 1024 * 1024 },
);
if (mutantBuild.status !== 0) {
  fail(`renamed-state mutant build failed to compile:\n${mutantBuild.error || ''}\n${mutantBuild.stdout || ''}\n${mutantBuild.stderr || ''}`);
}

const mutantMarker = 'rf-ui-tool-evidence-renamed-root-mutation-v1';
const mutantRun = spawnSync(
  process.execPath,
  [path.join(ROOT, 'scripts', 'serve-and-run-browser-tests.cjs'),
   '--root', MUTANT_ROOT, '--port', '8024'],
  {
    cwd: ROOT,
    encoding: 'utf8',
    maxBuffer: 8 * 1024 * 1024,
    env: { ...process.env, BROWSER_TEST_TIMEOUT_MS: '120000' },
  },
);
const mutantOutput = `${mutantRun.stdout || ''}\n${mutantRun.stderr || ''}`;
if (mutantRun.status === 0) {
  fail('renamed rooted evidence-state mutation was NOT rejected');
}
if (!mutantOutput.includes(mutantMarker)) {
  fail(`mutant failed for the wrong reason; missing runtime marker ${mutantMarker}:\n${mutantOutput}`);
}

process.stdout.write('ui mounted production elision: PASS\n');
