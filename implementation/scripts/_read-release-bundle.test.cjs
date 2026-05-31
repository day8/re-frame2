#!/usr/bin/env node
/*
 * Tests for `lib/read-release-bundle.cjs` (rf2-ynjts.15 coverage pass).
 *
 * This module is the shared release-bundle reader + grep primitives for
 * the six check-* scanners (bundle-isolation, reagent-slim, uix-helix,
 * schemas-bundle, perf-bundle, elision). It had no dedicated unit test;
 * its contracts are subtle and load-bearing, and a regression in any of
 * them produces a SILENT false-GREEN in a production-elision / bundle-
 * isolation gate — the worst failure mode. The behaviours pinned here:
 *
 *   - readReleaseBlob / listReleaseJsFiles return `null` (NOT ''/[]) for
 *     a missing dir. Scanners branch on `== null` to print an actionable
 *     "did you run shadow-cljs release?" message and exit 1; an empty
 *     string would make every `countSubstring(blob, sentinel) === 0`
 *     sentinel check pass vacuously against a non-existent bundle.
 *   - the reader returns ONLY top-level *.js (rf2-z9a06 trap): a stale
 *     `cljs-runtime/` dev-source subdir from a prior `shadow-cljs
 *     compile` must NOT be walked, or dev-only sentinels trip false FAILs.
 *   - countMatches resets a caller-supplied /g RegExp's `lastIndex` so
 *     the count is independent of prior use (a /g literal carries match
 *     state across calls).
 *   - countMatches returns 0 for a null blob (so a missing bundle is
 *     safe to count against without a guard at every call site).
 *   - countSubstring treats the needle literally (escapeRe), so a
 *     sentinel containing regex metachars (`.`, `(`, `$`, …) counts only
 *     real occurrences.
 *
 * Standalone node-runnable suite (no external framework), matching the
 * sibling _*.test.cjs convention. Hermetic: each filesystem fixture is
 * built in a fresh os.tmpdir() mkdtemp dir and torn down.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const {
  readReleaseBlob,
  listReleaseJsFiles,
  escapeRe,
  countMatches,
  countSubstring,
} = require('./lib/read-release-bundle.cjs');

const tests = [];
const cleanups = [];

function test(name, fn) {
  tests.push({ name, fn });
}

// Build a fresh hermetic release-bundle fixture. `files` maps a relative
// path (POSIX-style separators) to file contents; intermediate dirs are
// created as needed. Returns the absolute bundle-dir path; the dir is
// registered for teardown.
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
  // A path under tmpdir that we never create.
  return path.join(os.tmpdir(), `rf2-rrb-absent-${process.pid}-${Math.random().toString(36).slice(2)}`);
}

// ----- listReleaseJsFiles ----------------------------------------------------

test('listReleaseJsFiles returns null (not []) for a missing dir', () => {
  assert.equal(listReleaseJsFiles(missingDir()), null);
});

test('listReleaseJsFiles returns absolute paths to the top-level *.js files', () => {
  const dir = makeBundleDir({
    'main.js': 'a();',
    'cljs_base.js': 'b();',
  });
  const files = listReleaseJsFiles(dir);
  assert.equal(Array.isArray(files), true);
  assert.equal(files.length, 2);
  for (const f of files) {
    assert.equal(path.isAbsolute(f), true);
    assert.equal(f.endsWith('.js'), true);
  }
  // Set-equality on basenames (readdir order is not contractually sorted).
  assert.deepEqual(
    files.map((f) => path.basename(f)).sort(),
    ['cljs_base.js', 'main.js'],
  );
});

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

test('listReleaseJsFiles returns [] for an empty dir (present-but-no-js)', () => {
  const dir = makeBundleDir({ 'manifest.edn': '{}' });
  assert.deepEqual(listReleaseJsFiles(dir), []);
});

// ----- readReleaseBlob -------------------------------------------------------

test('readReleaseBlob returns null (NOT "") for a missing dir — guards false-GREEN', () => {
  // The load-bearing contract: scanners do `if (blob == null)` to exit 1
  // with an actionable message. An empty string would silently pass every
  // sentinel-absence check against a non-existent bundle.
  assert.equal(readReleaseBlob(missingDir()), null);
});

test('readReleaseBlob concatenates only the top-level *.js (rf2-z9a06: no cljs-runtime recursion)', () => {
  const dir = makeBundleDir({
    'main.js': 'RELEASE_TOKEN();',
    'cljs-runtime/dev.js': 'DEV_ONLY_SENTINEL();',
  });
  const blob = readReleaseBlob(dir);
  assert.equal(blob.includes('RELEASE_TOKEN'), true);
  assert.equal(
    blob.includes('DEV_ONLY_SENTINEL'),
    false,
    'stale cljs-runtime dev source must not appear in the release blob',
  );
});

test('readReleaseBlob joins multiple files with a newline separator', () => {
  const dir = makeBundleDir({ 'a.js': 'AAA', 'b.js': 'BBB' });
  const blob = readReleaseBlob(dir);
  // Both fragments present and newline-separated (order-independent).
  assert.equal(blob.includes('AAA'), true);
  assert.equal(blob.includes('BBB'), true);
  assert.equal(blob.includes('\n'), true);
  assert.equal(blob.split('\n').length, 2);
});

test('readReleaseBlob returns "" for a present dir with no *.js (distinct from missing)', () => {
  const dir = makeBundleDir({ 'manifest.edn': '{}' });
  // Present-but-empty is a real, non-null state: the dir exists, the
  // release simply emitted no JS. Distinct from the null missing-dir case.
  assert.equal(readReleaseBlob(dir), '');
});

// ----- escapeRe --------------------------------------------------------------

test('escapeRe escapes every RegExp metacharacter so the source is matched literally', () => {
  const raw = 'a.b*c+d?e^f$g{h}i(j)k|l[m]n\\o';
  const escaped = escapeRe(raw);
  // A RegExp built from the escaped form matches the raw string verbatim
  // and matches it as a whole (no metachar got through as an operator).
  const re = new RegExp(`^${escaped}$`);
  assert.equal(re.test(raw), true);
});

test('escapeRe leaves a metachar-free string unchanged', () => {
  assert.equal(escapeRe('reagent.dom'), 'reagent\\.dom');
  assert.equal(escapeRe('plainToken'), 'plainToken');
});

// ----- countMatches ----------------------------------------------------------

test('countMatches counts global matches in the blob', () => {
  assert.equal(countMatches('xax-xax-xax', /xax/g), 3);
});

test('countMatches returns 0 for a null blob (missing-bundle safe)', () => {
  assert.equal(countMatches(null, /anything/g), 0);
});

test('countMatches returns 0 when the pattern is absent', () => {
  assert.equal(countMatches('hello world', /zzz/g), 0);
});

test('countMatches resets a /g RegExp lastIndex so the count is call-independent', () => {
  // A /g literal carries `lastIndex` across calls. Without the defensive
  // reset, a second count of the same regex object would under-count
  // (or miss matches entirely). Pin: repeated calls give the same count.
  const re = /ab/g;
  const blob = 'ab ab ab';
  assert.equal(countMatches(blob, re), 3);
  // Mutate lastIndex as a live .exec()/.test() consumer would, then recount.
  re.lastIndex = 5;
  assert.equal(countMatches(blob, re), 3, 'recount must ignore stale lastIndex');
  // And a fresh count after a prior count is stable.
  assert.equal(countMatches(blob, re), 3);
});

// ----- countSubstring --------------------------------------------------------

test('countSubstring counts literal occurrences of the needle', () => {
  assert.equal(countSubstring('foo.bar.foo', 'foo'), 2);
});

test('countSubstring treats regex metacharacters in the needle literally', () => {
  // A sentinel like "reagent.dom" must count only the literal "reagent.dom",
  // NOT "reagentXdom" (which a `.`-as-wildcard would also match).
  const blob = 'reagent.dom reagentXdom reagent.dom';
  assert.equal(countSubstring(blob, 'reagent.dom'), 2);

  // A needle with parens/dollar metachars counts only the literal text.
  const blob2 = 'cljs.core$truth_(x) cljs.core$truth_(y)';
  assert.equal(countSubstring(blob2, 'cljs.core$truth_('), 2);
});

test('countSubstring returns 0 for a null blob (missing-bundle safe)', () => {
  assert.equal(countSubstring(null, 'sentinel'), 0);
});

test('countSubstring returns 0 when the needle is absent', () => {
  assert.equal(countSubstring('no match here', 'reagent'), 0);
});

// ----- integration: the scanner contract end-to-end -------------------------

test('a sentinel-absence scan over a real fixture: absent => 0, present => count', () => {
  // Mirrors how check-bundle-isolation drives the helpers: read the blob,
  // then countSubstring(blob, sentinel) === 0 means "sentinel absent".
  const dir = makeBundleDir({
    'main.js': 'function clean(){return 1;}',
    'cljs-runtime/leak.js': 'reagent.dom.render();', // stale dev, must be ignored
  });
  const blob = readReleaseBlob(dir);
  assert.notEqual(blob, null);
  // The release artefact is clean; the dev-only leak in cljs-runtime/ is
  // (correctly) invisible, so the gate sees 0 and would PASS.
  assert.equal(countSubstring(blob, 'reagent.dom.render'), 0);
  // A sentinel actually present in the release blob is counted.
  assert.equal(countSubstring(blob, 'function clean'), 1);
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
