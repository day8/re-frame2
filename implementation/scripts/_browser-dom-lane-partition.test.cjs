#!/usr/bin/env node

'use strict';

/*
 * rf2-mf4uy — the two browser DOM lanes must PARTITION the repo's
 * `*_dom_cljs_test` namespaces.
 *
 * `:browser-test` is the PR-blocking correctness gate. `:browser-test-freehand-
 * bench` is the Freehand B-spine's evidence lane. Until rf2-mf4uy both ran the
 * seven `re-frame.freehand.bench.*` DOM suites, and those seven were 71% of the
 * correctness gate's wall clock (one of them, `b10-two-clock`, was 49% on its
 * own) because they burn wall clock on purpose. The gate now excludes them by
 * negative lookahead and the bench build keeps them.
 *
 * WHAT THIS GATE IS FOR. Two `:ns-regexp` strings that were complementary when
 * written can stop being complementary later without anything going red: a new
 * suffix, a renamed namespace, a tightened prefix. Both failure directions are
 * silent in ordinary CI —
 *
 *   - a namespace matched by NEITHER lane stops running in a browser
 *     altogether, and no lane's exit code changes (shadow-cljs's
 *     `find-test-namespaces` reports nothing when a pattern selects less);
 *   - a namespace matched by BOTH is a benchmark back in the correctness gate,
 *     which is the state rf2-mf4uy removed and which nothing would announce.
 *
 * A bench that silently stops running is worse than a slow lane, so the
 * relationship is asserted rather than commented.
 *
 * DERIVED, NEVER RESTATED (the rf2-k41ph posture, matching
 * `scripts/check_freehand_conformance_index.py`): both patterns are read out of
 * `shadow-cljs.edn`, and the namespaces out of the `(ns ...)` forms of the
 * files themselves. A copy of either would be a second authority with nothing
 * holding it in step with the first.
 *
 * Wired into package.json via `test:script-policy`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const IMPL_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_DIR, '..');
const SHADOW_CLJS = path.join(IMPL_DIR, 'shadow-cljs.edn');

// The two lanes and the builds that own them.
const CORRECTNESS_BUILD = ':browser-test';
const BENCH_BUILD = ':browser-test-freehand-bench';

// Trees that carry test sources. `out/`, `node_modules/` and `.shadow-cljs/`
// hold compiled copies of the same files and would double-count.
const SEARCH_ROOTS = ['implementation', 'tools'];
const SKIP_DIRS = new Set(['node_modules', 'out', '.shadow-cljs', '.git', 'target']);
const DOM_TEST_RE = /_dom_cljs_test\.clj[sc]$/;

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ---- deriving the selectors ------------------------------------------------

// Each build in shadow-cljs.edn's `:builds` map opens with its keyword alone on
// a two-space-indented line, which is what delimits one build's text from the
// next. Slicing that way keeps a `:ns-regexp` from being attributed to a
// neighbouring build.
function buildBlock(text, buildKey) {
  const heads = [...text.matchAll(/^ {2}(:[A-Za-z0-9._/-]+)[ \t]*\r?$/gm)];
  const at = heads.findIndex((m) => m[1] === buildKey);
  assert.ok(at > -1, `${buildKey} is not declared in shadow-cljs.edn`);
  const start = heads[at].index;
  const end = at + 1 < heads.length ? heads[at + 1].index : text.length;
  return text.slice(start, end);
}

function unescapeEdnString(s) {
  return s.replace(/\\(.)/g, (_, ch) => {
    switch (ch) {
      case 'n': return '\n';
      case 't': return '\t';
      case 'r': return '\r';
      default: return ch;   // \\ -> \, \" -> ", \. -> .
    }
  });
}

function selectorFor(text, buildKey) {
  const block = buildBlock(text, buildKey);
  const found = block.match(/:ns-regexp\s+"((?:[^"\\]|\\.)*)"/);
  assert.ok(found, `${buildKey} declares no :ns-regexp, so its lane cannot be derived`);
  // shadow-cljs selects with `re-find`, so JS `.test` (an unanchored search) is
  // the matching semantics — the patterns carry their own anchors.
  return new RegExp(unescapeEdnString(found[1]));
}

// ---- deriving the namespaces ----------------------------------------------

function walk(dir, out) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const e of entries) {
    if (e.isDirectory()) {
      if (!SKIP_DIRS.has(e.name)) walk(path.join(dir, e.name), out);
    } else if (DOM_TEST_RE.test(e.name)) {
      out.push(path.join(dir, e.name));
    }
  }
  return out;
}

function namespaceOf(file) {
  const src = fs.readFileSync(file, 'utf8');
  const m = src.match(/\(ns\s+([A-Za-z0-9_.*+!?<>=$%&|-]+)/);
  assert.ok(m, `${path.relative(REPO_ROOT, file)} has no readable (ns ...) form`);
  return m[1];
}

function domTestNamespaces() {
  const files = [];
  for (const root of SEARCH_ROOTS) walk(path.join(REPO_ROOT, root), files);
  return files
    .map((f) => ({ ns: namespaceOf(f), file: path.relative(REPO_ROOT, f) }))
    .sort((a, b) => a.ns.localeCompare(b.ns));
}

// ---- the gate --------------------------------------------------------------

test('the two browser DOM lanes partition every *_dom_cljs_test namespace (rf2-mf4uy)', () => {
  const text = fs.readFileSync(SHADOW_CLJS, 'utf8');
  const correctness = selectorFor(text, CORRECTNESS_BUILD);
  const bench = selectorFor(text, BENCH_BUILD);
  const namespaces = domTestNamespaces();

  assert.ok(
    namespaces.length > 0,
    'found no *_dom_cljs_test sources at all — the walk is broken, not the config',
  );

  const orphaned = [];
  const doubled = [];
  const inBench = [];
  for (const { ns, file } of namespaces) {
    const a = correctness.test(ns);
    const b = bench.test(ns);
    if (!a && !b) orphaned.push(`${ns}  (${file})`);
    if (a && b) doubled.push(`${ns}  (${file})`);
    if (b) inBench.push(ns);
  }

  assert.deepEqual(
    orphaned, [],
    `these DOM suites are selected by NEITHER ${CORRECTNESS_BUILD} nor ` +
      `${BENCH_BUILD}, so they no longer run in any browser and no lane's exit ` +
      `code says so:\n  ${orphaned.join('\n  ')}`,
  );
  assert.deepEqual(
    doubled, [],
    `these DOM suites are selected by BOTH lanes, so the correctness gate is ` +
      `paying for benchmark wall clock again (rf2-mf4uy):\n  ${doubled.join('\n  ')}`,
  );

  // Non-vacuity, both ways: a pattern that selected nothing would empty a lane
  // and a pattern that selected everything would put the benches back — and a
  // partition over an empty set, or over one lane's whole set, is trivially
  // satisfied by both checks above.
  assert.ok(
    inBench.length > 0,
    `${BENCH_BUILD} selects no DOM suite at all — its evidence lane is empty`,
  );
  assert.ok(
    inBench.length < namespaces.length,
    `${BENCH_BUILD} selects every DOM suite — the correctness gate is empty`,
  );
});

test('the bench lane is exactly the Freehand bench DOM suites (rf2-mf4uy)', () => {
  const text = fs.readFileSync(SHADOW_CLJS, 'utf8');
  const bench = selectorFor(text, BENCH_BUILD);
  const stray = domTestNamespaces()
    .filter(({ ns }) => bench.test(ns) && !ns.startsWith('re-frame.freehand.bench.'))
    .map(({ ns }) => ns);
  assert.deepEqual(
    stray, [],
    `the evidence lane picked up namespaces outside re-frame.freehand.bench.*, ` +
      `which means ordinary correctness suites left the PR gate:\n  ${stray.join('\n  ')}`,
  );
});

let failed = 0;
for (const { name, fn } of tests) {
  try {
    fn();
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err.message);
  }
}
if (failed) {
  console.error(`browser-dom-lane-partition tests: ${failed} failed.`);
  process.exit(1);
}
console.log(`browser-dom-lane-partition tests: ${tests.length} passed.`);
