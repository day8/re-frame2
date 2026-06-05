#!/usr/bin/env node
/*
 * Tests for `check-examples-compile.cjs` — the example-build COMPILE gate
 * (rf2-0vav5.1 + rf2-cn6kc.1).
 *
 * The gate derives its compile list from `shadow-cljs.edn`'s `:examples/*`
 * build ids, so a newly-declared example build is swept automatically. The
 * TEETH live here: these tests pin the enumeration so the derivation can't
 * silently under-count (which would let a build ship uncompiled-but-green,
 * the exact regression class the gate exists to prevent). Specifically:
 *
 *   - the parser recovers the previously-uncovered builds (login-uix,
 *     dashboard-uix, login-helix, process-monitor-helix) AND the covered
 *     counter trio — so the gate sweeps the whole standalone example set;
 *   - a NEWLY-declared `:examples/*` build is picked up (proven by feeding
 *     the parser a synthetic edn with an extra build — the enumeration
 *     grows, which is exactly why the live gate would compile it and fail
 *     RED if it were broken);
 *   - a build REMOVED from the compiled set is detected (a hardcoded subset
 *     would miss it — proven by feeding an edn missing a known build);
 *   - line comments and mid-line `:examples/...` tokens are NOT mistaken
 *     for build defs (no false coverage / no parser crash);
 *   - the parse over the REAL shadow-cljs.edn is non-vacuous.
 *
 * Standalone node-runnable suite — no external test framework, mirroring
 * `_examples-filter.test.cjs` / `dev-testbed.test.cjs`. Wired into
 * package.json via `test:script-policy`.
 */

'use strict';

const assert = require('assert');

const {
  readShadowEdn,
  stripEdnComments,
  enumerateExampleBuilds,
  parseBuildSummaries,
  buildsWithWarnings,
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

console.log('check-examples-compile enumeration tests (rf2-0vav5.1 + rf2-cn6kc.1)');

// --- Real-file enumeration: non-vacuous + covers the gap builds -----------

const realEdn = readShadowEdn();
const realBuilds = enumerateExampleBuilds(realEdn);

it('enumeration over the real shadow-cljs.edn is non-vacuous', () => {
  assert.ok(
    realBuilds.length >= 10,
    `expected the full example set (>=10), got ${realBuilds.length}: ` +
      realBuilds.join(', '),
  );
});

it('the four previously-UNCOVERED standalone builds are swept (the gap)', () => {
  for (const b of [
    'examples/login-uix',
    'examples/dashboard-uix',
    'examples/login-helix',
    'examples/process-monitor-helix',
  ]) {
    assert.ok(
      realBuilds.includes(b),
      `${b} (rf2-0vav5.1 / rf2-cn6kc.1 gap build) is NOT in the gate's ` +
        `compile set — the gate would not compile it, so the regression ` +
        `it exists to catch would still ship green.`,
    );
  }
});

it('the already-covered counter trio is also swept (no exclusions)', () => {
  for (const b of [
    'examples/counter',
    'examples/counter-uix',
    'examples/counter-helix',
  ]) {
    assert.ok(realBuilds.includes(b), `${b} missing from the example set`);
  }
});

it('enumeration is sorted + de-duplicated', () => {
  const sorted = [...realBuilds].sort();
  assert.deepStrictEqual(realBuilds, sorted, 'build list must be sorted');
  assert.strictEqual(
    new Set(realBuilds).size,
    realBuilds.length,
    'build list must be de-duplicated',
  );
});

it('every recovered build is an examples/ coord (no other prefixes leak in)', () => {
  for (const b of realBuilds) {
    assert.ok(
      b.startsWith('examples/'),
      `${b} is not an examples/ build — :testbeds/* and :story-static/* ` +
        `must NOT be swept by this gate`,
    );
  }
});

// --- Teeth on synthetic edn: ADD a build => the gate grows to cover it ----

it('a NEWLY-declared example build is picked up automatically (auto-cover)', () => {
  const base = enumerateExampleBuilds(realEdn);
  const withExtra =
    realEdn +
    '\n  :examples/brand-new-demo\n  {:target :browser\n   :output-dir "out/examples/brand-new-demo"\n   :modules {:main {:init-fn brand-new-demo.core/run}}}\n';
  const grown = enumerateExampleBuilds(withExtra);
  assert.ok(
    grown.includes('examples/brand-new-demo'),
    'a freshly-declared :examples/* build must appear in the compile set ' +
      'so the gate compiles (and would fail RED on) it',
  );
  assert.strictEqual(
    grown.length,
    base.length + 1,
    'adding exactly one example build must grow the set by exactly one',
  );
});

// --- Teeth on synthetic edn: REMOVE a build => enumeration shrinks --------
// A hardcoded subset would still "cover" a deleted build; deriving from the
// file means a removed declaration is no longer enumerated. This pins that
// the enumeration tracks the file (so the gate never compiles a phantom).

it('a REMOVED example declaration drops out of the compile set', () => {
  const base = enumerateExampleBuilds(realEdn);
  assert.ok(base.includes('examples/login-uix'), 'precondition: login-uix present');
  // Remove the login-uix build def header from the source.
  const without = realEdn.replace(/^\s*:examples\/login-uix\s*\{/m, '  :placeholder\n  {');
  const shrunk = enumerateExampleBuilds(without);
  assert.ok(
    !shrunk.includes('examples/login-uix'),
    'a removed :examples/* declaration must no longer be enumerated',
  );
  assert.strictEqual(
    shrunk.length,
    base.length - 1,
    'removing exactly one example build must shrink the set by exactly one',
  );
});

// --- Parser robustness: comments + mid-line tokens are not false builds ---

it('a commented-out build id is NOT counted (no false coverage)', () => {
  const edn =
    '  :examples/real {:target :browser}\n' +
    '  ;; :examples/commented {:target :browser}  removed build note\n';
  const builds = enumerateExampleBuilds(edn);
  assert.deepStrictEqual(builds, ['examples/real']);
});

it('a mid-line / prose :examples/... token is NOT counted as a build', () => {
  // The removed-build note in shadow-cljs.edn references :examples/xray-rhs-smoke
  // in prose; such mentions must never be enumerated as live builds.
  const edn =
    '  :examples/real {:target :browser}\n' +
    '  ;; see :examples/xray-rhs-smoke for the removed variant\n' +
    '   :modules {:main {:init-fn foo/run}} ;; trailing :examples/nope {\n';
  const builds = enumerateExampleBuilds(edn);
  assert.deepStrictEqual(builds, ['examples/real']);
});

it('stripEdnComments removes ;-comments but preserves code', () => {
  assert.strictEqual(
    stripEdnComments('  :examples/x {} ; :examples/y {}'),
    '  :examples/x {} ',
  );
});

// --- Warning-detection teeth ----------------------------------------------
// `shadow-cljs compile` exits 0 even when a build emits warnings, so the
// gate parses the per-build summary lines and fails on warnings>0. These
// pin that parsing — the second half of the teeth (a typo'd init-fn /
// undeclared var must turn the gate RED, not ship green as "1 warnings").

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
  const { completed, failed } = parseBuildSummaries(CLEAN_OUTPUT);
  assert.deepStrictEqual(
    completed,
    [
      { build: ':examples/login-uix', warnings: 0 },
      { build: ':examples/login-helix', warnings: 0 },
    ],
  );
  assert.deepStrictEqual(failed, []);
});

it('a clean compile has NO builds-with-warnings (gate stays green)', () => {
  assert.deepStrictEqual(buildsWithWarnings(CLEAN_OUTPUT), []);
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

if (failed > 0) {
  console.error(`\n${failed} test(s) failed.`);
  process.exit(1);
} else {
  console.log('\nAll check-examples-compile enumeration tests passed.');
}
