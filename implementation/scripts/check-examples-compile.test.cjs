#!/usr/bin/env node
/*
 * Tests for `check-examples-compile.cjs`, the standalone-build compile gate. Its
 * compile list is derived from shadow-cljs.edn's `:examples/*` and `:testbeds/*`
 * build ids, and shadow-cljs exits 0 on warnings, so these pin the two places it
 * could go green without compiling: an enumeration that under-counts, and summary
 * parsing that misses a warning or a build. Discovered by `npm run test:scripts`.
 */

'use strict';

const assert = require('assert');

const {
  readShadowEdn,
  enumerateCompiledBuilds,
  prefixesBelowFloor,
  parseBuildSummaries,
  buildsWithWarnings,
  reconcileRequestedBuilds,
} = require('./check-examples-compile.cjs');

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

console.log(
  'check-examples-compile enumeration tests',
);

const realEdn = readShadowEdn();
const realBuilds = enumerateCompiledBuilds(realEdn);

it('enumeration over the real shadow-cljs.edn is non-vacuous under EVERY swept prefix', () => {
  assert.deepStrictEqual(
    prefixesBelowFloor(realBuilds),
    [],
    `every swept prefix must clear its floor; got ${realBuilds.length} ` +
      `build(s) total: ${realBuilds.join(', ')}`,
  );
});

it('the per-prefix floor has TEETH: a prefix that stops matching is caught', () => {
  // A single TOTAL floor is satisfiable by the examples alone, so the testbeds arm
  // could stop matching unseen: an examples-only roster must starve `testbeds`.
  const examplesOnly = realBuilds.filter((b) => b.startsWith('examples/'));
  const starved = prefixesBelowFloor(examplesOnly);
  assert.deepStrictEqual(
    starved.map((s) => s.prefix),
    ['testbeds'],
    `an examples-only roster must starve exactly the testbeds prefix, got: ` +
      JSON.stringify(starved),
  );
});

it('a :story-static/* declaration is NOT swept (prefix roster is closed)', () => {
  const edn =
    '  :examples/real {:target :browser}\n' +
    '  :testbeds/real {:target :browser}\n' +
    '  :story-static/counter-with-stories {:target :browser}\n';
  assert.deepStrictEqual(enumerateCompiledBuilds(edn), [
    'examples/real',
    'testbeds/real',
  ]);
});

it('a mid-line / prose :examples/... token is NOT counted as a build', () => {
  const edn =
    '  :examples/real {:target :browser}\n' +
    '  ;; see :examples/xray-rhs-smoke for the removed variant\n' +
    '   :modules {:main {:init-fn foo/run}} ;; trailing :examples/nope {\n';
  const builds = enumerateCompiledBuilds(edn);
  assert.deepStrictEqual(builds, ['examples/real']);
});

// `shadow-cljs compile` exits 0 on warnings, so the gate fails on the per-build
// summary lines' warning counts.

const CLEAN_OUTPUT = [
  '[:examples/login-uix] Compiling ...',
  '[:examples/login-uix] Build completed. (188 files, 187 compiled, 0 warnings, 5.10s)',
  '[:examples/login-helix] Build completed. (196 files, 195 compiled, 0 warnings, 5.47s)',
].join('\n');

const WARNED_OUTPUT = [
  '[:examples/login-helix] Compiling ...',
  '------ WARNING #1 - :undeclared-var --------------',
  ' Use of undeclared Var login-helix.core/this-symbol-does-not-exist',
  '[:examples/login-helix] Build completed. (196 files, 1 compiled, 1 warnings, 5.47s)',
  '[:examples/login-uix] Build completed. (188 files, 0 compiled, 0 warnings, 5.10s)',
].join('\n');

const FAILED_OUTPUT = [
  '[:examples/login-uix] Compiling ...',
  '[:examples/login-uix] Build failed.',
  'The required namespace "login-uix.missing" is not available.',
].join('\n');

it('parseBuildSummaries reads per-build warning counts', () => {
  assert.deepStrictEqual(parseBuildSummaries(CLEAN_OUTPUT), {
    completed: [
      { build: ':examples/login-uix', warnings: 0 },
      { build: ':examples/login-helix', warnings: 0 },
    ],
    failed: [],
  });
});

it('a warning (typo\'d var) IS detected so the gate fails RED', () => {
  const warned = buildsWithWarnings(WARNED_OUTPUT);
  assert.deepStrictEqual(warned, [
    { build: ':examples/login-helix', warnings: 1 },
  ]);
});

it('a hard "Build failed" is surfaced via parseBuildSummaries.failed', () => {
  const { failed } = parseBuildSummaries(FAILED_OUTPUT);
  assert.deepStrictEqual(failed, [':examples/login-uix']);
});

// A clean exit with zero parsed warning rows is not proof every build was
// analysed: a missing, duplicate or unexpected summary, or a WARNING marker no
// parsed row accounts for, must fail the gate.

it('reconcile is clean when every requested build has exactly one summary', () => {
  const problems = reconcileRequestedBuilds(
    ['examples/login-uix', 'examples/login-helix'],
    CLEAN_OUTPUT,
  );
  assert.deepStrictEqual(problems, [], `expected no problems, got: ${problems}`);
});

it('a requested build with NO parsable summary is a coverage FAILURE (false-green closed)', () => {
  const problems = reconcileRequestedBuilds(
    ['examples/login-uix', 'examples/login-helix', 'examples/dashboard-uix'],
    CLEAN_OUTPUT,
  );
  assert.ok(
    problems.length === 1 && problems[0].includes(':examples/dashboard-uix') && /NO parsable/.test(problems[0]),
    `expected one missing-summary problem for dashboard-uix, got: ${problems}`,
  );
});

it('an UNPARSEABLE warning summary (singular "1 warning") FAILS the gate', () => {
  // A drift to the singular `1 warning` leaves the summary unparseable and the
  // WARNING marker orphaned: both teeth must fire, or the warning vanishes.
  const drifted = [
    '[:examples/login-helix] Compiling ...',
    '------ WARNING #1 - :undeclared-var --------------',
    ' Use of undeclared Var login-helix.core/typo',
    '[:examples/login-helix] Build completed. (196 files, 1 compiled, 1 warning, 5.47s)',
  ].join('\n');
  const problems = reconcileRequestedBuilds(['examples/login-helix'], drifted);
  assert.ok(
    problems.some((p) => /NO parsable/.test(p)),
    `expected a missing-summary problem, got: ${problems}`,
  );
  assert.ok(
    problems.some((p) => /WARNING marker/.test(p)),
    `expected an orphan-WARNING-marker problem, got: ${problems}`,
  );
});

it('a DUPLICATE completed summary for one build is a coverage FAILURE', () => {
  const dup = [
    '[:examples/login-uix] Build completed. (188 files, 187 compiled, 0 warnings, 5.10s)',
    '[:examples/login-uix] Build completed. (188 files, 0 compiled, 0 warnings, 0.10s)',
  ].join('\n');
  const problems = reconcileRequestedBuilds(['examples/login-uix'], dup);
  assert.ok(
    problems.length === 1 && /2 "Build completed." summaries/.test(problems[0]),
    `expected one duplicate-summary problem, got: ${problems}`,
  );
});

it('an UNEXPECTED completed summary (not requested) is a coverage FAILURE', () => {
  const problems = reconcileRequestedBuilds(['examples/login-uix'], CLEAN_OUTPUT);
  assert.ok(
    problems.some(
      (p) => p.includes(':examples/login-helix') && /NOT in the requested set/.test(p),
    ),
    `expected an unexpected-summary problem for login-helix, got: ${problems}`,
  );
});

it('reconcile stays clean when a parsable warning row accounts for the WARNING marker (the orphan check does not double-report a real warning)', () => {
  const problems = reconcileRequestedBuilds(
    ['examples/login-helix', 'examples/login-uix'],
    WARNED_OUTPUT,
  );
  assert.deepStrictEqual(problems, [], `expected no coverage problems, got: ${problems}`);
});

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll check-examples-compile enumeration tests passed.');
}
