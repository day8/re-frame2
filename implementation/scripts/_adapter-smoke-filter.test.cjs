#!/usr/bin/env node
/*
 * Tests for `implementation/adapters/scripts/adapter-smoke-filter.cjs`, the
 * adapter-smoke manifest and the ADAPTER_SMOKE_FILTER selection shared by the
 * orchestrator (which matches build ids) and the Playwright runner (which matches
 * spec paths): a build-id-shaped and a path-shaped filter must select the same
 * singleton, and the manifest must match the spec files on disk.
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
  normalizeForFilter,
  entryIdentities,
  isSpecFile,
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

it('empty filter selects both (full sweep — nightly + rigorous-local)', () => {
  assert.deepStrictEqual(selectBuildIds(''), [
    'adapters/reagent-testbed',
    'adapters/uix-testbed',
  ]);
});

// The adapter-testbed-smokes CI job's filter.
it('broad CI filter `adapters/` selects exactly the two adapter smokes', () => {
  assert.deepStrictEqual(selectBuildIds('adapters/'), [
    'adapters/reagent-testbed',
    'adapters/uix-testbed',
  ]);
});

it('build-id-shaped and path-shaped singleton filters select exactly their one adapter', () => {
  assert.deepStrictEqual(selectBuildIds('adapters/reagent-testbed'), ['adapters/reagent-testbed']);
  assert.deepStrictEqual(selectBuildIds('uix/testbed'), ['adapters/uix-testbed']);
});

it('comma-separated filter OR-matches multiple shapes', () => {
  assert.deepStrictEqual(selectBuildIds('reagent-testbed,uix/testbed'), [
    'adapters/reagent-testbed',
    'adapters/uix-testbed',
  ]);
});

// Selection keys on build ids and repo-relative spec paths, never the absolute
// path, or a filter naming a segment of the checkout directory selects every entry.
it('a filter matching only the absolute REPO_ROOT prefix selects nothing (rf2-n4nc2o)', () => {
  const segs = path.resolve(REPO_ROOT).split(/[\\/]/).filter(Boolean);
  const identityBlob = ADAPTER_SMOKES.flatMap(entryIdentities).join('|');
  const leakyTerms = segs.filter(
    (s) => s.length >= 2 && !identityBlob.includes(normalizeForFilter(s)),
  );
  assert.ok(
    leakyTerms.length > 0,
    'expected at least one REPO_ROOT path segment absent from stable identities',
  );
  for (const term of leakyTerms) {
    assert.deepStrictEqual(
      selectEntries(parseFilterPatterns(term)).map((e) => e.build),
      [],
      `filter '${term}' (an absolute REPO_ROOT prefix segment) over-selected`,
    );
  }
});

it('normalizeForFilter collapses _, \\ and / to a single -', () => {
  assert.deepStrictEqual(['a_b', 'a\\b', 'a/b'].map(normalizeForFilter), ['a-b', 'a-b', 'a-b']);
});

// ---- manifest vs on-disk reconciliation: the same functions run-adapter-smokes.cjs calls

it('isSpecFile accepts spec.cjs and *.spec.cjs and rejects everything else (rf2-qf45gu)', () => {
  assert.deepStrictEqual(
    ['spec.cjs', 'reagent.spec.cjs', 'spec.js', 'specXcjs'].map(isSpecFile),
    [true, true, false, false],
  );
});

it('reconcile partitions declared-vs-discovered into missing + undeclared (rf2-qf45gu)', () => {
  const a = path.resolve(REPO_ROOT, 'x', 'a.spec.cjs');
  const b = path.resolve(REPO_ROOT, 'x', 'b.spec.cjs');
  const c = path.resolve(REPO_ROOT, 'x', 'c.spec.cjs');
  // declared {a,b}, on disk {b,c}: a is missing, c is undeclared.
  assert.deepStrictEqual(reconcile([a, b], [b, c]), { missing: [a], undeclared: [c] });
  // Both sides are resolved first, so mixed absolute/relative inputs reconcile clean.
  assert.deepStrictEqual(reconcile([a, b], [b, path.relative(process.cwd(), a)]), { missing: [], undeclared: [] });
});

it('the real listSpecFiles + reconcile agree with the manifest on the live repo (rf2-qf45gu)', () => {
  const discovered = listSpecFiles(ADAPTER_SMOKE_SPEC_ROOTS);
  assert.deepStrictEqual(
    discovered,
    ADAPTER_SMOKES.map((e) => path.resolve(e.specPath)).sort(),
    `manifest/disk drift.\n  on disk:  ${discovered.join('\n            ')}`,
  );
});

// Every example/adapter-smoke gate script root TESTING.md names must exist.

const ROOT_TESTING_MD = path.join(REPO_ROOT, 'TESTING.md');
const PKG_JSON = path.join(REPO_ROOT, 'implementation', 'package.json');

it('every `test:examples*` / `test:adapter-smokes` command named in root TESTING.md exists in implementation/package.json (rf2-n4nc2o)', () => {
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
