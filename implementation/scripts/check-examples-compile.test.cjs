#!/usr/bin/env node
/*
 * Tests for `check-examples-compile.cjs`, the standalone-build compile gate. Its
 * compile list is derived from shadow-cljs.edn's `:examples/*` and `:testbeds/*`
 * build ids, and each of those builds makes its own warnings fatal, so these pin
 * the three places it could go green without judging a build: an enumeration that
 * under-counts, a build that does not set `:warnings-as-errors`, and summary
 * parsing that misses a build. Discovered by `npm run test:scripts`.
 */

'use strict';

const assert = require('assert');

const {
  readShadowEdn,
  enumerateCompiledBuilds,
  prefixesBelowFloor,
  buildsMissingWarningsAsErrors,
  parseBuildSummaries,
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

// The flag is the gate's teeth: shadow-cljs fails a flagged build on a warning,
// and compiles an unflagged one's warnings green.

it('every swept build in the real shadow-cljs.edn sets :warnings-as-errors true', () => {
  assert.deepStrictEqual(buildsMissingWarningsAsErrors(realEdn), []);
});

it('a swept build without the flag is named, whatever its neighbours or comments say', () => {
  const edn = [
    ' :builds',
    ' {:node-test',
    '  {:target :node-test',
    '   :compiler-options {:warnings-as-errors true}}',
    '  :examples/flagged',
    '  {:target :browser',
    '   :compiler-options {:warnings-as-errors true}}',
    '  ;; :warnings-as-errors true lives in a comment, which proves nothing',
    '  :examples/commented',
    '  {:target :browser',
    '   :modules {:main {:init-fn a/run}}}',
    '  :testbeds/bare',
    '  {:target :browser}',
    '  :testbeds/disabled',
    '  {:target :browser',
    '   :compiler-options {:warnings-as-errors false}}}',
  ].join('\n');
  assert.deepStrictEqual(buildsMissingWarningsAsErrors(edn), [
    'examples/commented',
    'testbeds/bare',
    'testbeds/disabled',
  ]);
});

const CLEAN_OUTPUT = [
  '[:examples/login-uix] Compiling ...',
  '[:examples/login-uix] Build completed. (188 files, 187 compiled, 0 warnings, 5.10s)',
  '[:examples/login-helix] Build completed. (196 files, 195 compiled, 0 warnings, 5.47s)',
].join('\n');

it('parseBuildSummaries reads the completed build ids in output order', () => {
  assert.deepStrictEqual(parseBuildSummaries(CLEAN_OUTPUT), {
    completed: [':examples/login-uix', ':examples/login-helix'],
  });
});

// A clean exit is not proof every requested build was compiled: a missing,
// duplicate or unexpected summary must fail the gate.

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

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll check-examples-compile enumeration tests passed.');
}
