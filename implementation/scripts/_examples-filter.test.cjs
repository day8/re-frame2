#!/usr/bin/env node
/*
 * Tests for `examples/scripts/examples-filter.cjs` — the shared
 * example-set manifest + filter-selection logic used by BOTH the
 * orchestrator (serve-and-run-examples-tests.cjs) and the Playwright
 * runner (run-examples-tests.cjs).
 *
 * Regression for rf2-l72e2: the two scripts used to apply the same
 * EXAMPLES_FILTER value to two different string spaces (build ids vs
 * absolute spec paths), so a build-id-shaped filter like
 * `reagent-testbed` staged a surface in the orchestrator but matched
 * ZERO specs in the runner. These tests pin that:
 *
 *   - build-id-shaped and path-shaped filters select the SAME singleton,
 *   - the broad CI filter `adapters/` selects all three,
 *   - selection is shared (so orchestrator compile/stage set == runner
 *     spec set by construction), and
 *   - the declared manifest matches the spec.cjs files on disk.
 *
 * Standalone node-runnable suite — no external test framework. Wire into
 * package.json via `test:script-policy`.
 */

'use strict';

const path = require('path');
const fs = require('fs');
const assert = require('assert');

const {
  EXAMPLES,
  SPEC_ROOTS,
  REPO_ROOT,
  selectEntries,
  parseFilterPatterns,
  normalizeForFilter,
} = require('../../examples/scripts/examples-filter.cjs');

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

// Select with a single filter string (parse + select), returning the
// chosen entries' build ids sorted for stable comparison.
function selectBuildIds(filter) {
  return selectEntries(parseFilterPatterns(filter))
    .map((e) => e.build)
    .sort();
}

console.log('examples-filter selection tests (rf2-l72e2)');

const ADAPTERS = ['reagent', 'uix', 'helix'];

// ---- manifest shape ------------------------------------------------------

it('manifest declares exactly the three adapter smokes', () => {
  assert.deepStrictEqual(
    EXAMPLES.map((e) => e.build).sort(),
    ['adapters/helix-testbed', 'adapters/reagent-testbed', 'adapters/uix-testbed'],
  );
});

it('every manifest entry carries a specPath that exists on disk', () => {
  for (const e of EXAMPLES) {
    assert.ok(e.specPath, `entry ${e.build} has no specPath`);
    assert.ok(
      fs.existsSync(e.specPath),
      `specPath for ${e.build} does not exist: ${e.specPath}`,
    );
  }
});

// ---- broad + empty filters ----------------------------------------------

it('empty filter selects all three (full sweep)', () => {
  assert.deepStrictEqual(selectBuildIds(''), [
    'adapters/helix-testbed',
    'adapters/reagent-testbed',
    'adapters/uix-testbed',
  ]);
});

it('broad CI filter `adapters/` selects all three', () => {
  assert.deepStrictEqual(selectBuildIds('adapters/'), [
    'adapters/helix-testbed',
    'adapters/reagent-testbed',
    'adapters/uix-testbed',
  ]);
});

it('broad filter `adapters` (no slash) selects all three', () => {
  assert.strictEqual(selectBuildIds('adapters').length, 3);
});

// ---- the rf2-l72e2 core: build-id vs path form equivalence --------------

for (const name of ADAPTERS) {
  const buildId = `adapters/${name}-testbed`;

  // The four supported singleton shapes that should ALL select exactly
  // the one adapter: two build-id forms and two spec-path forms.
  const shapes = [
    `adapters/${name}-testbed`, // full build id
    `${name}-testbed`,          // bare build-id segment (the rf2-l72e2 repro)
    `adapters/${name}/testbed`, // path form
    `${name}/testbed`,          // bare path segments
  ];

  for (const shape of shapes) {
    it(`filter '${shape}' selects exactly ${buildId}`, () => {
      assert.deepStrictEqual(selectBuildIds(shape), [buildId]);
    });
  }

  it(`all supported singleton shapes for ${name} select the identical set`, () => {
    const sets = shapes.map(selectBuildIds);
    for (const s of sets) {
      assert.deepStrictEqual(s, [buildId]);
    }
  });
}

// ---- orchestrator/runner equivalence is structural ----------------------
// Both scripts call selectEntries over the same EXAMPLES manifest. This
// test pins that, for every supported filter shape, the set the
// orchestrator stages (build ids) and the set the runner runs (specPaths)
// are one-to-one — i.e. no shape stages a surface the runner then can't
// find (the rf2-l72e2 failure mode).

it('selected set maps one-to-one between build ids and specPaths for all shapes', () => {
  const allShapes = [
    '',
    'adapters/',
    'adapters',
    ...ADAPTERS.flatMap((n) => [
      `adapters/${n}-testbed`,
      `${n}-testbed`,
      `adapters/${n}/testbed`,
      `${n}/testbed`,
    ]),
  ];
  for (const shape of allShapes) {
    const selected = selectEntries(parseFilterPatterns(shape));
    // Orchestrator side: the build ids it compiles/stages.
    const builds = selected.map((e) => e.build);
    // Runner side: the spec paths it executes.
    const specs = selected.map((e) => e.specPath);
    assert.strictEqual(
      builds.length,
      specs.length,
      `shape '${shape}': build count != spec count`,
    );
    for (const s of specs) {
      assert.ok(
        fs.existsSync(s),
        `shape '${shape}': runner would run a non-existent spec ${s}`,
      );
    }
  }
});

// ---- comma-separated OR-match -------------------------------------------

it('comma-separated filter OR-matches multiple shapes', () => {
  assert.deepStrictEqual(selectBuildIds('reagent-testbed,helix/testbed'), [
    'adapters/helix-testbed',
    'adapters/reagent-testbed',
  ]);
});

// ---- substring-trap protection preserved --------------------------------

it('an unrelated substring selects nothing (no over-selection)', () => {
  assert.deepStrictEqual(selectBuildIds('does-not-exist'), []);
});

it('normalizeForFilter collapses _, \\ and / to a single -', () => {
  assert.strictEqual(normalizeForFilter('a_b'), 'a-b');
  assert.strictEqual(normalizeForFilter('a\\b'), 'a-b');
  assert.strictEqual(normalizeForFilter('a/b'), 'a-b');
  assert.strictEqual(normalizeForFilter('adapters/reagent/testbed'), 'adapters-reagent-testbed');
  assert.strictEqual(normalizeForFilter('adapters/reagent-testbed'), 'adapters-reagent-testbed');
});

// ---- manifest vs on-disk reconciliation ---------------------------------
// Mirror the runner's reconciliation guard so drift is caught at the fast
// JS-harness tier too (not only at Playwright-run time).

function isSpecFile(name) {
  return name === 'spec.cjs' || name.endsWith('.spec.cjs');
}
function listSpecFiles(roots) {
  const out = [];
  for (const root of roots) {
    if (!fs.existsSync(root)) continue;
    const stack = [root];
    while (stack.length > 0) {
      const dir = stack.pop();
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
          if (entry.name === 'node_modules') continue;
          stack.push(full);
        } else if (entry.isFile() && isSpecFile(entry.name)) {
          out.push(path.resolve(full));
        }
      }
    }
  }
  return out.sort();
}

it('declared manifest spec set == spec.cjs files on disk under SPEC_ROOTS', () => {
  const declared = EXAMPLES.map((e) => path.resolve(e.specPath)).sort();
  const discovered = listSpecFiles(SPEC_ROOTS);
  assert.deepStrictEqual(
    discovered,
    declared,
    `manifest/disk drift.\n  on disk:  ${discovered.join('\n            ')}\n  declared: ${declared.join('\n            ')}`,
  );
});

it('SPEC_ROOTS resolve under the repo root', () => {
  for (const root of SPEC_ROOTS) {
    assert.ok(
      path.resolve(root).startsWith(path.resolve(REPO_ROOT)),
      `SPEC_ROOT ${root} is outside REPO_ROOT ${REPO_ROOT}`,
    );
  }
});

if (failed > 0) {
  console.error(`\nexamples-filter tests: ${failed} failed.`);
  process.exit(1);
}
console.log('\nexamples-filter tests: all passed.');
