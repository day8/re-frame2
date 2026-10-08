#!/usr/bin/env node
/*
 * Tests for `lib/read-release-bundle.cjs`, the release-bundle reader and grep
 * primitives behind the bundle-isolation and production-elision gates. Each
 * contract here fails toward a silent false-GREEN if it regresses: a missing or
 * empty bundle must not read as a clean one, a stale cljs-runtime/ dev tree must
 * not be scanned, and counts must be literal and call-independent.
 * Hermetic: each fixture is a fresh os.tmpdir() mkdtemp dir, torn down after.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const {
  listReleaseJsFiles,
  classifyReleaseBundle,
  countMatches,
  countSubstring,
} = require('./lib/read-release-bundle.cjs');

const tests = [];
const cleanups = [];

function test(name, fn) {
  tests.push({ name, fn });
}

// `files` maps a POSIX-style relative path to contents; torn down after the run.
function makeBundleDir(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-rrb-'));
  cleanups.push(() => fs.rmSync(dir, { recursive: true, force: true }));
  for (const [rel, contents] of Object.entries(files)) {
    const abs = path.join(dir, ...rel.split('/'));
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, contents, 'utf8');
  }
  return dir;
}

function missingDir() {
  return path.join(os.tmpdir(), `rf2-rrb-absent-${process.pid}-${Math.random().toString(36).slice(2)}`);
}

// ----- listReleaseJsFiles ----------------------------------------------------

test('listReleaseJsFiles excludes non-.js files and subdirectories (rf2-z9a06 trap)', () => {
  const dir = makeBundleDir({
    'main.js': 'release();',
    'manifest.edn': '{}',
    'index.html': '<html>',
    // A stale dev-compile subdir: must NOT be walked.
    'cljs-runtime/re_frame.core.js': 'DEV_ONLY_SENTINEL();',
  });
  const files = listReleaseJsFiles(dir);
  assert.deepEqual(
    files.map((f) => path.basename(f)).sort(),
    ['main.js'],
  );
});

// ----- classifyReleaseBundle (non-vacuous floor) -----------------------------

test('classifyReleaseBundle reports status "missing" for a missing dir', () => {
  const c = classifyReleaseBundle(missingDir());
  assert.deepEqual(c, { status: 'missing', files: null, blob: null });
});

test('classifyReleaseBundle reports status "empty" for a present dir with no *.js', () => {
  // A gate branching only on a missing dir would pass this through vacuously.
  const dir = makeBundleDir({ 'manifest.edn': '{}' });
  const c = classifyReleaseBundle(dir);
  assert.deepEqual(c, { status: 'empty', files: [], blob: '' });
});

test('classifyReleaseBundle reports status "empty" when every top-level *.js is zero-byte', () => {
  const dir = makeBundleDir({ 'main.js': '', 'cljs_base.js': '' });
  const c = classifyReleaseBundle(dir);
  assert.equal(c.status, 'empty');
});

test('classifyReleaseBundle reports status "ok" for a real bundle (non-empty top-level *.js)', () => {
  const dir = makeBundleDir({ 'main.js': 'RELEASE_TOKEN();' });
  const c = classifyReleaseBundle(dir);
  assert.deepEqual(c, { status: 'ok', files: [path.join(dir, 'main.js')], blob: 'RELEASE_TOKEN();' });
});

test('classifyReleaseBundle ignores stale cljs-runtime dev sources when deciding ok/empty (rf2-z9a06)', () => {
  const dir = makeBundleDir({
    'main.js': '',
    'cljs-runtime/re_frame.core.js': 'DEV_ONLY_CONTENT();',
  });
  const c = classifyReleaseBundle(dir);
  assert.equal(c.status, 'empty', 'stale dev sources must not rescue an empty release artefact');
});

// ----- countMatches ----------------------------------------------------------

test('countMatches counts global matches in the blob', () => {
  assert.equal(countMatches('xax-xax-xax', /xax/g), 3);
});

test('countMatches returns 0 for a null blob (missing-bundle safe)', () => {
  assert.equal(countMatches(null, /anything/g), 0);
});

test('countMatches resets a /g RegExp lastIndex so the count is call-independent', () => {
  const re = /ab/g;
  re.lastIndex = 5; // as a live .exec()/.test() consumer leaves it
  assert.equal(countMatches('ab ab ab', re), 3, 'the count must ignore a stale lastIndex');
});

// ----- countSubstring --------------------------------------------------------

test('countSubstring treats regex metacharacters in the needle literally', () => {
  const blob = 'reagent.dom reagentXdom reagent.dom';
  assert.equal(countSubstring(blob, 'reagent.dom'), 2);

  const blob2 = 'cljs.core$truth_(x) cljs.core$truth_(y)';
  assert.equal(countSubstring(blob2, 'cljs.core$truth_('), 2);
});

test('countSubstring returns 0 for a null blob (missing-bundle safe)', () => {
  assert.equal(countSubstring(null, 'sentinel'), 0);
});

// ----- runner ----------------------------------------------------------------

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

for (const fn of cleanups) {
  try {
    fn();
  } catch (_) {
    /* best-effort teardown */
  }
}

if (failed > 0) {
  console.error(`read-release-bundle tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`read-release-bundle tests: ${tests.length} passed.`);
