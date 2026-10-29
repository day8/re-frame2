#!/usr/bin/env node
/*
 * Tests for `implementation/adapters/scripts/adapter-smoke-filter.cjs`, the
 * adapter-smoke set and the ADAPTER_SMOKE_FILTER selection the orchestrator
 * and the Playwright runner share: a filter selects the smokes whose build id
 * contains it, and the set must match the spec files on disk.
 * Discovered by `npm run test:scripts`.
 */

'use strict';

const path = require('path');
const fs = require('fs');
const assert = require('assert');

const {
  ADAPTER_SMOKES,
  ADAPTER_SMOKE_SPEC_ROOTS,
  REPO_ROOT,
  selectEntries,
  parseFilterPatterns,
  listSpecFiles,
  reconcile,
} = require('../adapters/scripts/adapter-smoke-filter.cjs');

let failed = 0;

function it(label, f) {
  try {
    f();
    console.log(`  PASS  ${label}`);
  } catch (err) {
    failed++;
    console.error(`  FAIL  ${label}`);
    console.error(`        ${err.message || err}`);
  }
}

function selectBuildIds(filter) {
  return selectEntries(parseFilterPatterns(filter))
    .map((e) => e.build)
    .sort();
}

console.log('adapter-smoke-filter selection tests');

// An empty filter is the full sweep (nightly + rigorous-local), and CI's
// `adapters/` selects both smokes; a name or build-id fragment selects one
// adapter; commas OR-match; a pattern no build id contains selects nothing.
it('a filter selects the smokes whose build id contains any of its comma-separated patterns', () => {
  assert.deepStrictEqual(
    ['', 'adapters/', 'reagent', 'adapters/uix-testbed', ' reagent , uix ', 'reagent/testbed']
      .map(selectBuildIds),
    [
      ['adapters/reagent-testbed', 'adapters/uix-testbed'],
      ['adapters/reagent-testbed', 'adapters/uix-testbed'],
      ['adapters/reagent-testbed'],
      ['adapters/uix-testbed'],
      ['adapters/reagent-testbed', 'adapters/uix-testbed'],
      [],
    ],
  );
});

// ---- set vs on-disk reconciliation: the same functions run-adapter-smokes.cjs calls

it('reconcile partitions declared-vs-discovered into missing + undeclared', () => {
  const a = path.resolve(REPO_ROOT, 'x', 'a.spec.cjs');
  const b = path.resolve(REPO_ROOT, 'x', 'b.spec.cjs');
  const c = path.resolve(REPO_ROOT, 'x', 'c.spec.cjs');
  // declared {a,b}, on disk {b,c}: a is missing, c is undeclared.
  assert.deepStrictEqual(reconcile([a, b], [b, c]), { missing: [a], undeclared: [c] });
  // Both sides are resolved first, so mixed absolute/relative inputs reconcile clean.
  assert.deepStrictEqual(reconcile([a, b], [b, path.relative(process.cwd(), a)]), { missing: [], undeclared: [] });
});

it('the real listSpecFiles + reconcile agree with the set on the live repo', () => {
  const discovered = listSpecFiles(ADAPTER_SMOKE_SPEC_ROOTS);
  assert.deepStrictEqual(
    discovered,
    ADAPTER_SMOKES.map((e) => path.resolve(e.specPath)).sort(),
    `set/disk drift.\n  on disk:  ${discovered.join('\n            ')}`,
  );
});

// Every example/adapter-smoke gate script root TESTING.md names must exist.

const ROOT_TESTING_MD = path.join(REPO_ROOT, 'TESTING.md');
const PKG_JSON = path.join(REPO_ROOT, 'implementation', 'package.json');

it('every `test:examples*` / `test:adapter-smokes` command named in root TESTING.md exists in implementation/package.json', () => {
  const doc = fs.readFileSync(ROOT_TESTING_MD, 'utf8');
  const pkg = JSON.parse(fs.readFileSync(PKG_JSON, 'utf8'));
  const scripts = new Set(Object.keys(pkg.scripts || {}));

  // Scoped to this package's families, so other packages' script names don't false-positive.
  const named = new Set();
  const re = /test:(?:examples[A-Za-z0-9:_-]*|adapter-smokes)/g;
  let m;
  while ((m = re.exec(doc)) !== null) {
    named.add(m[0]);
  }

  const missing = [...named].filter((name) => !scripts.has(name)).sort();
  assert.deepStrictEqual(
    missing,
    [],
    `root TESTING.md names example-gate scripts absent from implementation/package.json: ${missing.join(', ')}`,
  );
});

if (failed > 0) {
  console.error(`\nadapter-smoke-filter tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\nadapter-smoke-filter tests: all passed.');
