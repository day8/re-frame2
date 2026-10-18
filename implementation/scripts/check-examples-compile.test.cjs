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
  reconcileRequestedBuilds,
} = require('./check-examples-compile.cjs');
const { EdnReadError } = require('./lib/edn.cjs');

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

// The flag is the gate's teeth: shadow-cljs fails a flagged build on a warning,
// and compiles an unflagged one's warnings green.

it('every swept build in the real shadow-cljs.edn sets :warnings-as-errors true', () => {
  assert.deepStrictEqual(buildsMissingWarningsAsErrors(realEdn), []);
});

// The roster is the key set of the `:builds` map, so it is a fact about the
// map, not about how the file lays it out.

const CONVENTIONAL_LAYOUT = [
  '{:source-paths ["src"]',
  ' :builds',
  ' {:node-test',
  '  {:target :node-test}',
  '  :examples/counter',
  '  {:target :browser',
  '   :compiler-options {:warnings-as-errors true}}',
  '  :testbeds/deep-machine',
  '  {:target :browser',
  '   :compiler-options {:warnings-as-errors true}}}}',
].join('\n');

it('reformatting the same :builds map preserves the roster', () => {
  const oneLine =
    '{:source-paths ["src"] :builds {:examples/counter {:target :browser, ' +
    ':compiler-options {:warnings-as-errors true}} :node-test {:target :node-test} ' +
    ':testbeds/deep-machine {:target :browser :compiler-options {:warnings-as-errors true}}}}';
  const keyMidLine = [
    '{:source-paths ["src"]',
    ' :builds {:examples/counter {:target :browser',
    '                             :compiler-options {:warnings-as-errors true}}',
    '          :node-test {:target :node-test} :testbeds/deep-machine',
    '          {:target :browser :compiler-options {:warnings-as-errors true}}}}',
  ].join('\n');
  const expected = ['examples/counter', 'testbeds/deep-machine'];
  assert.deepStrictEqual(enumerateCompiledBuilds(CONVENTIONAL_LAYOUT), expected);
  assert.deepStrictEqual(enumerateCompiledBuilds(oneLine), expected);
  assert.deepStrictEqual(enumerateCompiledBuilds(keyMidLine), expected);
});

it('nested lookalikes, strings, comments and #_-discarded declarations are not builds', () => {
  const edn = [
    '{:dev-http {8000 {:roots ["out/examples/fake"]}}',
    ' :target-defaults {:examples/top-level-lookalike {:target :browser}}',
    ' :builds',
    ' {:examples/real',
    '  {:target :browser',
    '   :modules {:examples/nested-lookalike {:init-fn a/run}}',
    '   :compiler-options {:warnings-as-errors true}}',
    '  ;; :examples/commented {:target :browser}',
    '  :node-test {:target :node-test :doc ":examples/in-a-string {"}',
    '  #_:examples/discarded #_{:target :browser}',
    '  #_ #_ :testbeds/chain-discarded {:target :browser}',
    '  :testbeds/real {:target :browser :compiler-options {:warnings-as-errors true}}}}',
  ].join('\n');
  assert.deepStrictEqual(enumerateCompiledBuilds(edn), ['examples/real', 'testbeds/real']);
  assert.deepStrictEqual(buildsMissingWarningsAsErrors(edn), []);
});

it('a form the reader does not support, or no :builds map, fails explicitly', () => {
  for (const edn of [
    '{:builds {:examples/x {:target #shadow/env "TARGET"}}}',
    '{:builds [:examples/x {:target :browser}]}',
    '{:source-paths ["src"]}',
    '{:builds {:examples/x [:target :browser]}}',
  ]) {
    assert.throws(() => enumerateCompiledBuilds(edn), EdnReadError, edn);
  }
});

it('a swept build without the flag is named, whatever its neighbours or comments say', () => {
  const edn = [
    '{:builds',
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
    '   :compiler-options {:warnings-as-errors false}}',
    '  :testbeds/flag-outside-compiler-options',
    '  {:target :browser',
    '   :modules {:main {:warnings-as-errors true}}}}}',
  ].join('\n');
  assert.deepStrictEqual(buildsMissingWarningsAsErrors(edn), [
    'examples/commented',
    'testbeds/bare',
    'testbeds/disabled',
    'testbeds/flag-outside-compiler-options',
  ]);
});

const CLEAN_OUTPUT = [
  '[:examples/login-uix] Compiling ...',
  '[:examples/login-uix] Build completed. (188 files, 187 compiled, 0 warnings, 5.10s)',
  '[:examples/login-helix] Build completed. (196 files, 195 compiled, 0 warnings, 5.47s)',
].join('\n');

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
