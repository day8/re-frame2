#!/usr/bin/env node
/*
 * Tests for `lib/read-release-bundle.cjs`, the release-bundle reader and grep
 * primitives behind the bundle-isolation and production-elision gates. Each
 * contract here fails toward a silent false-GREEN if it regresses: a missing or
 * empty bundle must not read as a clean one, a stale cljs-runtime/ dev tree must
 * not be scanned, and counts must be literal.
 * Hermetic: each fixture is a fresh os.tmpdir() mkdtemp dir, torn down after.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const { classifyReleaseBundle, readBuildManifest, countSubstring } = require('./lib/read-release-bundle.cjs');

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

// ----- readBuildManifest -----------------------------------------------------
// A graph claim reads "absent" off this list, so every shape the reader does not
// understand must refuse rather than return a short list.

test('readBuildManifest concatenates every module\'s :sources, in order', () => {
  const dir = makeBundleDir({
    'manifest.edn': '[{:module-id :a, :sources ["goog/base.js" "re_frame/core.cljc"], :depends-on nil}\n' +
      ' {:module-id :b, :sources ["node_modules/elkjs/lib/elk.bundled.js"], :depends-on #{:a}}]',
  });
  const m = readBuildManifest(dir);
  assert.equal(m.status, 'ok');
  assert.deepEqual(m.sources, ['goog/base.js', 're_frame/core.cljc', 'node_modules/elkjs/lib/elk.bundled.js']);
});

test('readBuildManifest reports "missing" for a bundle with no manifest', () => {
  assert.equal(readBuildManifest(makeBundleDir({ 'main.js': 'x();' })).status, 'missing');
  assert.equal(readBuildManifest(missingDir()).status, 'missing');
});

test('readBuildManifest refuses every shape it cannot read as a module list', () => {
  for (const [text, why] of [
    ['{:sources ["re_frame/core.cljc"]}', 'a map, not a vector of modules'],
    ['[{:module-id :main}]', 'a module with no :sources'],
    ['[{:module-id :main, :sources "re_frame/core.cljc"}]', ':sources not a vector'],
    ['[{:module-id :main, :sources [re_frame/core.cljc]}]', 'a :sources entry that is a symbol'],
    ['[{:module-id :main, :sources []}]', 'no module lists a source'],
    ['[]', 'no modules at all'],
    ['[{:module-id :main, :sources ["re_frame/core.cljc"]', 'truncated EDN'],
  ]) {
    const m = readBuildManifest(makeBundleDir({ 'manifest.edn': text }));
    assert.equal(m.status, 'unreadable', why);
    assert.equal(m.sources, null, why);
    assert.ok(m.reason, `${why}: the refusal must say why`);
  }
});

// ----- countSubstring --------------------------------------------------------

test('countSubstring treats regex metacharacters in the needle literally', () => {
  const blob = 'reagent.dom reagentXdom reagent.dom';
  assert.equal(countSubstring(blob, 'reagent.dom'), 2);

  const blob2 = 'cljs.core$truth_(x) cljs.core$truth_(y)';
  assert.equal(countSubstring(blob2, 'cljs.core$truth_('), 2);
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
