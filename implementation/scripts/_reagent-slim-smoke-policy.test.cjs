#!/usr/bin/env node
'use strict';

/*
 * Gate-arming policy test for the reagent-slim CLIENT-RUNTIME smoke
 * (rf2-xsgu8a).
 *
 * The acceptance criterion this file enforces: edits to the slim smoke
 * manifest/path must ARM the intended CI gate and cannot silently drift
 * back to Reagent/UIx/Helix-only coverage. These checks have teeth — each
 * fails RED if a future edit unwires the slim smoke, points it at the
 * wrong substrate, deletes a required file, or removes the npm gate.
 *
 * Surfaces pinned:
 *   1. The slim testbed source files exist on disk (core + index.html +
 *      smoke).
 *   2. The shadow-cljs build `:adapters/reagent-slim-testbed` is declared
 *      with the slim testbed init-fn AND its source path is on the
 *      :node/:dev source-paths.
 *   3. The slim testbed core actually mounts on the SLIM substrate
 *      (reagent2.* + re-frame.adapter.reagent-slim), never stock Reagent —
 *      so the smoke can never silently regress to exercising stock
 *      coverage while still "compiling".
 *   4. The smoke scenario asserts the slim example's dataflow: initial
 *      value 5, click-driven inc AND dec.
 *   5. npm `test:reagent-slim:smoke` exists and runs the adapter-owned
 *      runner; `test:script-policy` runs THIS policy test.
 *   6. The slim smoke is a DISTINCT surface from the shared adapter-smoke
 *      manifest: its driver file is not named
 *      spec.cjs/*.spec.cjs (so the shared adapter-smoke spec-walker never
 *      discovers it and its reconcile guard stays green) AND it is not an
 *      entry in implementation/adapters/scripts/adapter-smoke-filter.cjs
 *      (whose Reagent/UIx/Helix set is its own surface). This is the
 *      two-way drift guard.
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * check-reagent-slim-boundary.test.cjs. Wired into package.json via
 * `test:script-policy`.
 */

const fs = require('fs');
const path = require('path');
const assert = require('assert');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const TESTBED_DIR = path.join(IMPL_ROOT, 'adapters', 'reagent-slim', 'testbed');
const CORE = path.join(TESTBED_DIR, 'adapter_testbed_reagent_slim', 'core.cljs');
const INDEX_HTML = path.join(TESTBED_DIR, 'index.html');
const SMOKE = path.join(TESTBED_DIR, 'smoke.cjs');
const RUNNER = path.join(IMPL_ROOT, 'scripts', 'serve-and-run-reagent-slim-smoke.cjs');
const SHADOW_EDN = path.join(IMPL_ROOT, 'shadow-cljs.edn');
const PKG_JSON = path.join(IMPL_ROOT, 'package.json');
const ADAPTER_SMOKE_FILTER = path.join(IMPL_ROOT, 'adapters', 'scripts', 'adapter-smoke-filter.cjs');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'test.yml');
const CHANGED_SURFACES = path.join(
  REPO_ROOT,
  '.github',
  'scripts',
  'report-changed-surfaces.sh',
);

let failed = 0;
function it(label, fn) {
  try {
    fn();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${(err && err.message) || err}`);
  }
}

function read(p) {
  return fs.readFileSync(p, 'utf8');
}

console.log('reagent-slim smoke policy tests (rf2-xsgu8a)');

// ---- 1) testbed source files exist ---------------------------------------

it('slim testbed core.cljs exists', () => {
  assert.ok(fs.existsSync(CORE), `missing slim testbed core: ${CORE}`);
});
it('slim testbed index.html exists', () => {
  assert.ok(fs.existsSync(INDEX_HTML), `missing slim testbed index.html: ${INDEX_HTML}`);
});
it('slim smoke scenario exists', () => {
  assert.ok(fs.existsSync(SMOKE), `missing slim smoke: ${SMOKE}`);
});
it('adapter-owned slim smoke runner exists', () => {
  assert.ok(fs.existsSync(RUNNER), `missing slim smoke runner: ${RUNNER}`);
});

// ---- 2) shadow-cljs build + source path armed ----------------------------

const SHADOW = fs.existsSync(SHADOW_EDN) ? read(SHADOW_EDN) : '';

it('shadow-cljs.edn declares the :adapters/reagent-slim-testbed build', () => {
  assert.ok(
    /:adapters\/reagent-slim-testbed/.test(SHADOW),
    'shadow-cljs.edn has no :adapters/reagent-slim-testbed build — the slim ' +
      'smoke gate is unarmed',
  );
});

it('the slim-testbed build uses the slim testbed init-fn', () => {
  assert.ok(
    /:init-fn\s+adapter-testbed-reagent-slim\.core\/init/.test(SHADOW),
    'the :adapters/reagent-slim-testbed build does not point at ' +
      'adapter-testbed-reagent-slim.core/init',
  );
});

it('the slim testbed source path is on a shadow-cljs source-paths vector', () => {
  assert.ok(
    /"adapters\/reagent-slim\/testbed"/.test(SHADOW),
    'shadow-cljs.edn does not add "adapters/reagent-slim/testbed" to ' +
      'source-paths — the slim testbed core will not resolve',
  );
});

// ---- 3) the testbed mounts on the SLIM substrate (teeth: no stock drift) --

const CORE_SRC = fs.existsSync(CORE) ? read(CORE) : '';

it('slim testbed core requires the SLIM substrate, not stock Reagent', () => {
  assert.ok(
    /reagent2\.dom\.client/.test(CORE_SRC),
    'slim testbed core must mount through reagent2.dom.client (the slim ' +
      'substrate), not stock reagent.dom.client',
  );
  assert.ok(
    /re-frame\.adapter\.reagent-slim/.test(CORE_SRC),
    'slim testbed core must install re-frame.adapter.reagent-slim',
  );
  // Hard guard against silent regression to the stock substrate: a bare
  // `reagent.*` (not `reagent2.*`) require or the stock adapter would mean
  // the smoke is exercising stock Reagent while claiming to cover slim.
  assert.ok(
    !/\[reagent\.(core|dom|ratom)/.test(CORE_SRC),
    'slim testbed core requires a stock reagent.* namespace — it would ' +
      'exercise stock Reagent, not the slim substrate',
  );
  assert.ok(
    !/re-frame\.adapter\.reagent\s/.test(CORE_SRC) &&
      !/re-frame\.adapter\.reagent\]/.test(CORE_SRC),
    'slim testbed core installs the stock re-frame.adapter.reagent adapter',
  );
});

it('slim testbed core is idiomatic re-frame2 (app-db + events + subs)', () => {
  // EP-0018 Z: `reg-event` is the one-form event-registration API; the
  // per-kind `reg-event-db`/`-fx`/`-ctx` wrappers were removed (throwing
  // stubs). Match `(reg-event <id> ...)` — the trailing `\s` requires the
  // bare one-form symbol and is NOT satisfied by a `reg-event-db` (etc.)
  // wrapper name slipping back in.
  assert.ok(/reg-event\s/.test(CORE_SRC), 'no reg-event (events) in slim testbed core');
  assert.ok(/reg-sub/.test(CORE_SRC), 'no reg-sub (subs) in slim testbed core');
  assert.ok(/reg-view/.test(CORE_SRC), 'no reg-view in slim testbed core');
  // No raw atoms threaded through views — the value flows through app-db.
  assert.ok(
    !/\(r\/atom|reagent\.core\/atom|reagent2\.core\/atom/.test(CORE_SRC),
    'slim testbed core threads a raw atom through the view — must use ' +
      'app-db + subs',
  );
});

// ---- 4) the smoke asserts the slim dataflow (init 5, inc + dec) ----------

const SMOKE_SRC = fs.existsSync(SMOKE) ? read(SMOKE) : '';

it('the smoke asserts the slim dataflow: initial value 5, inc and dec', () => {
  assert.ok(/rf-adapter-counter/.test(SMOKE_SRC), 'smoke does not read the counter element');
  assert.ok(/'5'/.test(SMOKE_SRC), 'smoke does not assert the initial counter value 5');
  assert.ok(/rf-adapter-inc/.test(SMOKE_SRC), 'smoke does not drive the inc button');
  assert.ok(/rf-adapter-dec/.test(SMOKE_SRC), 'smoke does not drive the dec button');
});

it('the smoke is shaped { name, url, run } like the adapter spec.cjs', () => {
  const mod = require(SMOKE);
  assert.strictEqual(typeof mod.run, 'function', 'smoke must export run()');
  assert.strictEqual(typeof mod.url, 'string', 'smoke must export a url string');
  assert.ok(mod.name, 'smoke must export a name');
});

// ---- 5) npm gates wired ---------------------------------------------------

const pkg = JSON.parse(read(PKG_JSON));
const scripts = pkg.scripts || {};

it('npm `test:reagent-slim:smoke` exists and runs the adapter-owned runner', () => {
  const s = scripts['test:reagent-slim:smoke'];
  assert.ok(s, 'package.json has no test:reagent-slim:smoke script — the gate is unarmed');
  assert.ok(
    /serve-and-run-reagent-slim-smoke\.cjs/.test(s),
    `test:reagent-slim:smoke does not run the slim smoke runner: ${s}`,
  );
});

it('`test:script-policy` runs THIS policy test', () => {
  const s = scripts['test:script-policy'] || '';
  assert.ok(
    /_reagent-slim-smoke-policy\.test\.cjs/.test(s),
    'test:script-policy does not run _reagent-slim-smoke-policy.test.cjs — ' +
      'the slim smoke wiring is unguarded',
  );
});

// ---- 5b) PR CI ACTUALLY RUNS the smoke gate (rf2-5v0dg7) ------------------
// Teeth beyond "the npm script exists": the workflow must EXECUTE it, and a
// reagent-slim surface change must ARM the job that runs it. Without these,
// the smoke can silently stop running in PR CI (the exact rf2-5v0dg7 bug).

const WORKFLOW_SRC = fs.existsSync(WORKFLOW) ? read(WORKFLOW) : '';
const CHANGED_SURFACES_SRC = fs.existsSync(CHANGED_SURFACES) ? read(CHANGED_SURFACES) : '';

it('PR CI workflow EXECUTES `npm run test:reagent-slim:smoke`', () => {
  assert.ok(WORKFLOW_SRC, `missing workflow: ${WORKFLOW}`);
  assert.ok(
    /npm run test:reagent-slim:smoke/.test(WORKFLOW_SRC),
    '.github/workflows/test.yml never runs `npm run test:reagent-slim:smoke` — ' +
      'the slim client-runtime smoke is UNARMED in PR CI (it exists as an npm ' +
      'script but no job executes it).',
  );
});

it('the slim smoke runs in a reagent_slim_bundle-gated job', () => {
  // The job that runs the smoke must be guarded by the reagent_slim_bundle
  // surface so it fires for reagent-slim adapter/testbed/runner changes
  // (and is skipped, not absent, on unrelated PRs). The smoke step lives in
  // the cljs-reagent-slim-bundle-isolation job, whose `if:` is gated on
  // detect_changed_surfaces.outputs.reagent_slim_bundle.
  assert.ok(
    /reagent_slim_bundle == 'true'/.test(WORKFLOW_SRC),
    'no job in test.yml is gated on reagent_slim_bundle — the smoke gate ' +
      'cannot be armed by reagent-slim surface detection',
  );
});

it('changed-surface detection ARMS reagent_slim_bundle for the smoke RUNNER', () => {
  // The smoke runner under implementation/scripts/ must route to
  // reagent_slim_bundle; otherwise editing the runner (or its policy test)
  // falls into the generic implementation/scripts/* case that never fires
  // the gate — a false-green hole (mirrors the xray-feature-gate launcher
  // case). Assert a dedicated case names the runner AND sets the surface.
  assert.ok(CHANGED_SURFACES_SRC, `missing changed-surfaces script: ${CHANGED_SURFACES}`);
  assert.ok(
    /serve-and-run-reagent-slim-smoke\.cjs/.test(CHANGED_SURFACES_SRC),
    'report-changed-surfaces.sh has no case naming ' +
      'serve-and-run-reagent-slim-smoke.cjs — editing the smoke runner would ' +
      'not arm the slim smoke gate (false-green hole)',
  );
  // The runner case must set reagent_slim_bundle=true. Extract the case
  // block (from the case-pattern line naming the runner to its closing
  // `;;`) and confirm the surface is armed within THAT block — robust to
  // however long the explanatory comment grows.
  const idx = CHANGED_SURFACES_SRC.indexOf('serve-and-run-reagent-slim-smoke.cjs');
  const end = CHANGED_SURFACES_SRC.indexOf(';;', idx);
  assert.ok(end > idx, 'could not find the end (`;;`) of the slim smoke runner case');
  const caseBlock = CHANGED_SURFACES_SRC.slice(idx, end);
  assert.ok(
    /reagent_slim_bundle=true/.test(caseBlock),
    'the serve-and-run-reagent-slim-smoke.cjs case does not set ' +
      'reagent_slim_bundle=true',
  );
});

// ---- 6) distinct-surface / two-way drift guard ---------------------------

it('the slim smoke driver is NOT named spec.cjs/*.spec.cjs (shared walker ignores it)', () => {
  const base = path.basename(SMOKE);
  assert.ok(
    base !== 'spec.cjs' && !base.endsWith('.spec.cjs'),
    `the slim smoke driver is named ${base} — the shared adapter-smoke ` +
      `spec-walker (run-adapter-smokes.cjs / _adapter-smoke-filter.test.cjs) ` +
      `would discover it under implementation/adapters/ and fail its ` +
      `manifest-vs-disk reconcile. Keep it as smoke.cjs (a dedicated, ` +
      `adapter-owned gate).`,
  );
});

it('the shared adapter-smoke manifest stays Reagent/UIx/Helix-only (no slim entry)', () => {
  // The slim smoke is the slim adapter's OWN gate; it must NOT be folded
  // into the shared three-adapter adapter-smoke manifest. This pins both
  // directions of the drift: the shared set does not silently grow a slim
  // entry, and the slim gate does not silently vanish into it.
  const { ADAPTER_SMOKES } = require(ADAPTER_SMOKE_FILTER);
  const builds = ADAPTER_SMOKES.map((e) => e.build).sort();
  assert.deepStrictEqual(
    builds,
    ['adapters/helix-testbed', 'adapters/reagent-testbed', 'adapters/uix-testbed'],
    'the shared adapter-smoke manifest drifted from the ' +
      'Reagent/UIx/Helix set; the slim client-runtime smoke is a dedicated ' +
      'adapter-owned gate (test:reagent-slim:smoke), not a shared-manifest entry',
  );
});

if (failed > 0) {
  console.error(`\nreagent-slim smoke policy tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\nreagent-slim smoke policy tests: all passed.');
