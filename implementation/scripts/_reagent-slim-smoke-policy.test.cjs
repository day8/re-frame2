#!/usr/bin/env node
'use strict';

/*
 * The reagent-slim client-runtime smoke stays wired and stays SLIM: its build is
 * declared, its testbed mounts the slim substrate rather than stock Reagent, and
 * PR CI runs it in a reagent_slim_bundle-gated job. A shadow-cljs.edn or
 * package.json edit does not arm that job, so those halves are pinned here, in
 * the always-run lane. Discovered by `npm run test:scripts`.
 */

const fs = require('fs');
const path = require('path');
const assert = require('assert');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const TESTBED_DIR = path.join(IMPL_ROOT, 'adapters', 'reagent-slim', 'testbed');
const CORE = path.join(TESTBED_DIR, 'adapter_testbed_reagent_slim', 'core.cljs');
const SHADOW_EDN = path.join(IMPL_ROOT, 'shadow-cljs.edn');
const PKG_JSON = path.join(IMPL_ROOT, 'package.json');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'test.yml');

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

console.log('reagent-slim smoke policy tests');

const SHADOW = fs.existsSync(SHADOW_EDN) ? read(SHADOW_EDN) : '';

it('shadow-cljs.edn declares the slim-testbed build, its init-fn and its source path', () => {
  assert.ok(/:adapters\/reagent-slim-testbed/.test(SHADOW), 'no :adapters/reagent-slim-testbed build');
  assert.ok(
    /:init-fn\s+adapter-testbed-reagent-slim\.core\/init/.test(SHADOW),
    'the slim-testbed build does not point at adapter-testbed-reagent-slim.core/init',
  );
  assert.ok(/"adapters\/reagent-slim\/testbed"/.test(SHADOW), '"adapters/reagent-slim/testbed" is on no source-paths');
});

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
  // A stock require would mean the smoke exercises stock Reagent while claiming slim.
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

const pkg = JSON.parse(read(PKG_JSON));
const scripts = pkg.scripts || {};

it('npm `test:reagent-slim:smoke` exists and runs the adapter-owned runner', () => {
  const s = scripts['test:reagent-slim:smoke'];
  assert.ok(
    /serve-and-run-reagent-slim-smoke\.cjs/.test(s),
    `test:reagent-slim:smoke does not run the slim smoke runner: ${s}`,
  );
});

const WORKFLOW_SRC = fs.existsSync(WORKFLOW) ? read(WORKFLOW) : '';

it('PR CI runs `npm run test:reagent-slim:smoke` in a reagent_slim_bundle-gated job', () => {
  assert.ok(
    /npm run test:reagent-slim:smoke/.test(WORKFLOW_SRC),
    '.github/workflows/test.yml never runs `npm run test:reagent-slim:smoke`',
  );
  assert.ok(/reagent_slim_bundle == 'true'/.test(WORKFLOW_SRC), 'no job in test.yml is gated on reagent_slim_bundle');
});

if (failed > 0) {
  console.error(`\nreagent-slim smoke policy tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\nreagent-slim smoke policy tests: all passed.');
