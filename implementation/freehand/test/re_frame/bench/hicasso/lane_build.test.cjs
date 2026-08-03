#!/usr/bin/env node
'use strict';
// SELF-TEST FOR THE HICASSO LANE'S BUILD DOOR — rf2-2rtt6.73.
//
//     node freehand/test/re_frame/bench/hicasso/lane_build.test.cjs
//
// `lane_build.cjs`'s teeth ARE its parser: the whole gate is "read shadow's
// output and decide". A parser that quietly stops matching turns every driver
// in the lane green again, and the failure is invisible — which is the exact
// shape of the defect being repaired, one level up. So each refusal is pinned
// here against REAL shadow-cljs output, ANSI codes and all.
//
// This is not hypothetical. The first cut of `WARNING_HEADLINE_RE` stopped at
// the first `-` and therefore captured NOTHING from
// `------ WARNING #1 - :undeclared-var ------`; the refusal still fired (the
// count comes from the summary line) but it could not name the class. An empty
// headline list is indistinguishable from a clean build to the naked eye.

const assert = require('node:assert');

const {
  judgeBuild,
  parseBuildSummaries,
  warningHeadlines,
  stripAnsi,
} = require('./lane_build.cjs');

// Real captured output, byte-for-byte, from `run.cjs` against
// walk_profile_app.cljs with M-NO-PROPS's def renamed (the mutation proof).
const WARNED_OUTPUT = [
  '[:hicasso-bench] Compiling ...',
  '[1m------ WARNING #1 - :undeclared-var --------------------------------------------[0m',
  ' File: .../walk_profile_app.cljs:324:7',
  '[33;1m Use of undeclared Var re-frame.bench.hicasso.walk-profile-app/M-NO-PROPS[0m',
  '[1m------ WARNING #2 - :undeclared-var --------------------------------------------[0m',
  ' File: .../walk_profile_app.cljs:486:41',
  '[33;1m Use of undeclared Var re-frame.bench.hicasso.walk-profile-app/M-NO-PROPS[0m',
  '[:hicasso-bench] Build completed. (186 files, 131 compiled, 2 warnings, 37.55s)',
].join('\n');

const CLEAN_OUTPUT = [
  '[:hicasso-bench] Compiling ...',
  '[:hicasso-bench] Build completed. (392 files, 391 compiled, 0 warnings, 46.71s)',
].join('\n');

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// --- the summary parser -----------------------------------------------------

test('parses a bare (slash-free) build id and its warning count', () => {
  const { completed } = parseBuildSummaries(WARNED_OUTPUT);
  assert.deepStrictEqual(completed, [{ build: ':hicasso-bench', warnings: 2 }]);
});

test('parses a clean summary as zero warnings', () => {
  const { completed } = parseBuildSummaries(CLEAN_OUTPUT);
  assert.deepStrictEqual(completed, [{ build: ':hicasso-bench', warnings: 0 }]);
});

test('a singular "1 warning" is still parsed', () => {
  const { completed } = parseBuildSummaries(
    '[:hicasso-bench] Build completed. (3 files, 1 compiled, 1 warning, 1.0s)',
  );
  assert.deepStrictEqual(completed, [{ build: ':hicasso-bench', warnings: 1 }]);
});

// --- the headline extractor (the one that was silently empty) ---------------

test('names the warning CLASS, not just the count', () => {
  assert.deepStrictEqual(warningHeadlines(WARNED_OUTPUT), [
    'WARNING #1 - :undeclared-var',
    'WARNING #2 - :undeclared-var',
  ]);
});

test('a clean build has no headlines', () => {
  assert.deepStrictEqual(warningHeadlines(CLEAN_OUTPUT), []);
});

test('stripAnsi removes the colour codes the patterns must see through', () => {
  assert.ok(!stripAnsi(WARNED_OUTPUT).includes('['));
});

// --- the four refusals ------------------------------------------------------

test('REFUSES warnings on an exit-0 build (the whole bug)', () => {
  const v = judgeBuild({ status: 0, output: WARNED_OUTPUT });
  assert.strictEqual(v.ok, false);
  assert.match(v.reason, /2 warning/);
  assert.ok(
    v.detail.some((d) => d.includes(':undeclared-var')),
    'the refusal must name the warning class, not only its count',
  );
});

test('REFUSES a non-zero exit and names the failed build', () => {
  const v = judgeBuild({
    status: 1,
    output: '[:hicasso-bench] Build failed',
  });
  assert.strictEqual(v.ok, false);
  assert.match(v.reason, /exited 1/);
  assert.ok(v.detail.some((d) => d.includes(':hicasso-bench')));
});

test('REFUSES an exit-0 build with NO parsable summary (parser drift)', () => {
  const v = judgeBuild({ status: 0, output: 'shadow-cljs - config: ...\n' });
  assert.strictEqual(v.ok, false);
  assert.match(v.reason, /NO parsable/);
});

test('REFUSES a WARNING block the summary did not account for', () => {
  const v = judgeBuild({
    status: 0,
    output: [
      '------ WARNING #1 - :undeclared-var ------------------------------------',
      ' Use of undeclared Var foo/bar',
      '[:hicasso-bench] Build completed. (3 files, 1 compiled, 0 warnings, 1.0s)',
    ].join('\n'),
  });
  assert.strictEqual(v.ok, false);
  assert.match(v.reason, /did not account for|every parsed summary reads 0/);
});

// --- and the green case, so the gate is not vacuously red -------------------

test('ACCEPTS a clean exit-0 build with a parsed zero-warning summary', () => {
  assert.deepStrictEqual(judgeBuild({ status: 0, output: CLEAN_OUTPUT }), {
    ok: true,
  });
});

let failed = 0;
for (const [name, fn] of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL  ${name}\n      ${err.message}`);
  }
}

if (failed > 0) {
  console.error(`\nlane_build.test.cjs: ${failed}/${tests.length} failed`);
  process.exit(1);
}
console.log(`lane_build.test.cjs: ${tests.length} passed`);
