#!/usr/bin/env node
/*
 * Tests for `implementation/adapters/scripts/adapter-smoke-filter.cjs` —
 * the shared adapter-smoke manifest + filter-selection logic used by BOTH
 * the orchestrator (serve-and-run-adapter-smokes.cjs) and the Playwright
 * runner (run-adapter-smokes.cjs), both colocated with the adapters under
 * implementation/adapters/scripts/.
 *
 * Regression for rf2-l72e2: the two scripts used to apply the same
 * ADAPTER_SMOKE_FILTER value to two different string spaces (build ids vs
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
  ADAPTER_SMOKES,
  ADAPTER_SMOKE_SPEC_ROOTS,
  REPO_ROOT,
  selectEntries,
  parseFilterPatterns,
  normalizeForFilter,
  entryIdentities,
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

// Select with a single filter string (parse + select), returning the
// chosen entries' build ids sorted for stable comparison.
function selectBuildIds(filter) {
  return selectEntries(parseFilterPatterns(filter))
    .map((e) => e.build)
    .sort();
}

console.log('adapter-smoke-filter selection tests (rf2-l72e2)');

const ADAPTERS = ['reagent', 'uix', 'helix'];

// ---- manifest shape ------------------------------------------------------

it('manifest declares exactly the three adapter smokes', () => {
  assert.deepStrictEqual(
    ADAPTER_SMOKES.map((e) => e.build).sort(),
    ['adapters/helix-testbed', 'adapters/reagent-testbed', 'adapters/uix-testbed'],
  );
});

it('every manifest entry carries a specPath that exists on disk', () => {
  for (const e of ADAPTER_SMOKES) {
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
// Both scripts call selectEntries over the same ADAPTER_SMOKES manifest. This
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

// rf2-n4nc2o: a filter term that appears ONLY in the absolute spec-path
// prefix (i.e. a directory the repo happens to be checked out under) must
// NOT match — selection keys on repo-stable identities (build id +
// repo-relative spec path), never the absolute filesystem prefix. The old
// code matched the absolute specPath too, so a filter substring of the
// workspace/worktree directory name over-selected EVERY entry.
//
// We model this two ways:
//   (a) directly: the candidate identities for a real entry never contain
//       the absolute spec path (the leaky string), and
//   (b) end-to-end: a filter drawn from a segment of the absolute REPO_ROOT
//       prefix selects ZERO real entries, while the supported build/path
//       shapes still select the singleton. Driving (b) off the live
//       REPO_ROOT keeps the regression faithful in any checkout location
//       (CI, a worktree, a path containing the filter term, …).

it('entryIdentities excludes the absolute spec path (only build id + repo-relative path) (rf2-n4nc2o)', () => {
  for (const e of ADAPTER_SMOKES) {
    const ids = entryIdentities(e);
    assert.strictEqual(ids.length, 2, `entry ${e.build} should expose exactly two identities`);
    // The absolute spec path, normalized, must NOT be one of them.
    const absNorm = normalizeForFilter(e.specPath);
    assert.ok(
      !ids.includes(absNorm),
      `entry ${e.build} leaks its absolute spec path as a match identity: ${absNorm}`,
    );
    // And the repo-relative spec path must never traverse out of the repo
    // (no `..`), so it can't smuggle the workspace prefix back in.
    const relSpec = path.relative(REPO_ROOT, e.specPath);
    assert.ok(
      !relSpec.split(/[\\/]/).includes('..'),
      `entry ${e.build} repo-relative spec path escapes REPO_ROOT: ${relSpec}`,
    );
  }
});

it('a filter matching only the absolute REPO_ROOT prefix selects nothing (rf2-n4nc2o)', () => {
  // A directory segment of the absolute checkout path that is NOT part of
  // any build id or repo-relative spec path. Picking the segment just
  // above the repo dir is a stable choice across checkouts.
  const segs = path.resolve(REPO_ROOT).split(/[\\/]/).filter(Boolean);
  // Prefer a segment that doesn't coincidentally appear in the stable
  // identities (e.g. avoid a hypothetical `adapters` dir).
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

  // Supported repo-stable shapes still resolve to the singleton.
  for (const name of ADAPTERS) {
    const buildId = `adapters/${name}-testbed`;
    for (const shape of [`${name}-testbed`, `${name}/testbed`]) {
      assert.deepStrictEqual(
        selectBuildIds(shape),
        [buildId],
        `shape '${shape}' should still select ${buildId}`,
      );
    }
  }
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

it('declared manifest spec set == spec.cjs files on disk under ADAPTER_SMOKE_SPEC_ROOTS', () => {
  const declared = ADAPTER_SMOKES.map((e) => path.resolve(e.specPath)).sort();
  const discovered = listSpecFiles(ADAPTER_SMOKE_SPEC_ROOTS);
  assert.deepStrictEqual(
    discovered,
    declared,
    `manifest/disk drift.\n  on disk:  ${discovered.join('\n            ')}\n  declared: ${declared.join('\n            ')}`,
  );
});

it('ADAPTER_SMOKE_SPEC_ROOTS resolve under the repo root', () => {
  for (const root of ADAPTER_SMOKE_SPEC_ROOTS) {
    assert.ok(
      path.resolve(root).startsWith(path.resolve(REPO_ROOT)),
      `SPEC_ROOT ${root} is outside REPO_ROOT ${REPO_ROOT}`,
    );
  }
});

// ---- root TESTING.md example/adapter-smoke-gate drift guard (rf2-n4nc2o) ---
// Pin the human-facing gate map to reality: every example/adapter-smoke gate
// script the root testing guide names (the `test:examples*` family — e.g.
// `test:examples-compile` — and the renamed `test:adapter-smokes` smoke
// runner) must actually exist in implementation/package.json, so a guide row
// pointing at a nonexistent command fails loud rather than sending
// contributors to a dead script. (`test:adapter-smokes` drives the three
// adapter testbed smokes only — the `examples/` tree is itself test-free.)

const ROOT_TESTING_MD = path.join(REPO_ROOT, 'TESTING.md');
const PKG_JSON = path.join(REPO_ROOT, 'implementation', 'package.json');

it('every `test:examples*` / `test:adapter-smokes` command named in root TESTING.md exists in implementation/package.json (rf2-n4nc2o)', () => {
  const doc = fs.readFileSync(ROOT_TESTING_MD, 'utf8');
  const pkg = JSON.parse(fs.readFileSync(PKG_JSON, 'utf8'));
  const scripts = new Set(Object.keys(pkg.scripts || {}));

  // The example/adapter-smoke gate lives in implementation/package.json;
  // scope the existence check to the `test:examples`/`test:examples-*` family
  // the root guide documents PLUS the renamed `test:adapter-smokes` runner, so
  // cross-package script names (e.g. tools/mcp-conformance's
  // `test:re-frame2-pair*`) don't false-positive.
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
