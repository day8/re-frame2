#!/usr/bin/env node

'use strict';

/*
 * rf2-mf4uy, rf2-0yp7w.6 — every `*_dom_cljs_test` namespace must be selected
 * by the repo's one browser DOM lane, and that lane must have an executor CI
 * is held to running.
 *
 * `:browser-test` is the PR-blocking correctness gate, and today it is the
 * ONLY browser DOM lane. It was one of two. `:browser-test-freehand-bench`
 * was the Freehand B-spine's evidence lane, and until rf2-mf4uy both ran the
 * seven `re-frame.freehand.bench.*` DOM suites, which were 71% of the
 * correctness gate's wall clock (one of them, `b10-two-clock`, was 49% on its
 * own) because they burn wall clock on purpose. rf2-mf4uy split them out: the
 * gate excluded them by negative lookahead and the bench build kept them. Then
 * the bench lane and all seven suites retired with the Freehand corpus
 * (rf2-0yp7w), the lookahead went with them, and `:browser-test` now selects
 * every DOM suite outright with a plain `.*-dom-cljs-test$`. So there is no
 * partition left to hold — see the note above the assertions for what survived
 * it.
 *
 * WHAT THIS GATE IS FOR. An `:ns-regexp` and the namespaces it is meant to
 * select can drift apart later without anything going red: a new suffix, a
 * renamed namespace, a tightened prefix. That failure is silent in ordinary
 * CI — a namespace the lane no longer matches stops running in a browser
 * altogether, and no lane's exit code changes, because shadow-cljs's
 * `find-test-namespaces` reports nothing when a pattern selects less.
 *
 * A suite that silently stops running is worse than a slow lane, so the
 * relationship is asserted rather than commented.
 *
 * DERIVED, NEVER RESTATED (the rf2-k41ph posture, which
 * `scripts/check_freehand_conformance_index.py` also held until it retired
 * with the Freehand corpus): the pattern is read out of
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
const PACKAGE_JSON = path.join(IMPL_DIR, 'package.json');
const GATE_SCHEDULING = path.join(REPO_ROOT, 'scripts', 'check_gate_scheduling.py');

// The lane, and the build that owns it.
const CORRECTNESS_BUILD = ':browser-test';

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

// ---- deriving the executors ------------------------------------------------
//
// rf2-j8os. A lane's SELECTOR is not a proof that the lane EXECUTES, and when
// this was written the second of the two lanes was held by nothing:
// `:browser-test-freehand-bench` was the sole browser home of seven
// `re-frame.freehand.bench.*` DOM suites. That lane has since retired with the
// Freehand corpus (rf2-0yp7w), but the argument holds for the lane that
// remains, and the chain that ends in a lane running is three links long:
//
//   the build id  ->  the npm script that compiles it  ->  a workflow `run:`
//
// The first two assertions below hold the FIRST link. The third delegates the
// second link to `scripts/check_gate_scheduling.py`, which already enforces
// that every `test:` / `bench:` / `build:` script in `package.json` is either
// reachable from an executable `run:` or carries a checked `DISPOSITIONS`
// entry — and which runs in `verify-skill-mcp-drift`, a `needs:` of the
// required `All required checks passed` aggregator.
//
// WHY DELEGATE RATHER THAN RE-DERIVE. Reading workflow `run:` values here
// would put a second authority on "what counts as executable text" beside the
// Python one, and that checker's own history is the argument against it: its
// first cut matched raw YAML, so a `run:` line deleted while its explanatory
// paragraph stayed put left the gate reading as scheduled (rf2-6ckzl). Two
// implementations of that rule can disagree, and the one that disagrees
// downward is a false green. So the link is delegated, not copied — and the
// delegation is HELD rather than asserted in prose, because a claim about the
// world in a comment is exactly what this file exists to stop trusting: the
// executor must carry a prefix that checker asks about, and must not carry a
// `DISPOSITIONS` entry excusing it from the question. Both are read out of the
// Python source rather than restated here, for the rf2-k41ph reason a copy is
// a second authority with nothing holding it in step.
//
// WHAT THIS DOES NOT CLOSE. Someone may still add a `DISPOSITIONS` entry for a
// lane's executor — and this arm will red and make them argue it, which is the
// whole difference between a decision and an accident. What it does close is
// the silent path: delete `freehand-bench.yml` and the four `package.json`
// scripts it is the sole home of (rf2-vyqq), and before this arm every gate in
// the repo stayed green while seven mounted DOM suites ran nowhere.

// `shadow-cljs <verb> <build-id> ...` is the only way a build id is executed.
// Whole tokens, never a substring test: `browser-test` was a prefix of the
// retired `browser-test-freehand-bench`, so `includes()` would have reported
// the correctness lane's script as the bench lane's executor too — the same
// prefix trap `check_gate_scheduling.py`'s `_PROBE_TAIL` was added for. The
// next build id to sit under an existing one brings that trap back, so the
// whole-token read stays.
function buildIdsInvokedBy(body) {
  const ids = new Set();
  for (const m of body.matchAll(/shadow-cljs\s+(?:compile|release|watch)\s+([^&|;<>]*)/g)) {
    for (const token of m[1].trim().split(/\s+/)) {
      if (token && !token.startsWith('-')) ids.add(token);
    }
  }
  return ids;
}

function executorsOf(scripts, buildId) {
  return Object.keys(scripts)
    .filter((name) => buildIdsInvokedBy(scripts[name]).has(buildId))
    .sort();
}

// The families `check_gate_scheduling.py` asks the scheduling question about.
function gatePrefixes() {
  const src = fs.readFileSync(GATE_SCHEDULING, 'utf8');
  const decl = src.match(/^GATE_PREFIXES\s*=\s*\(([^)]*)\)/m);
  assert.ok(decl, 'scripts/check_gate_scheduling.py declares no GATE_PREFIXES tuple');
  const prefixes = [...decl[1].matchAll(/"([^"]+)"/g)].map((m) => m[1]);
  assert.ok(prefixes.length > 0, 'GATE_PREFIXES parsed as empty — the derivation is broken');
  return prefixes;
}

// The scripts that checker excuses from needing a workflow home.
function dispositionKeys() {
  const src = fs.readFileSync(GATE_SCHEDULING, 'utf8');
  const at = src.indexOf('DISPOSITIONS: dict[str, dict] = {');
  assert.ok(at > -1, 'scripts/check_gate_scheduling.py declares no DISPOSITIONS dict');
  const rest = src.slice(at);
  const ends = rest.indexOf('\n}\n');
  const body = ends > -1 ? rest.slice(0, ends) : rest;
  return new Set([...body.matchAll(/^ {4}"([^"]+)":\s*\{/gm)].map((m) => m[1]));
}

// ---- the gate --------------------------------------------------------------

// rf2-0yp7w.6 — the two-lane PARTITION retired with the Freehand bench tree:
// `:browser-test-freehand-bench` was the only other DOM lane, and with it gone
// `:browser-test` selects every `*_dom_cljs_test` namespace outright, so a
// partition has nothing left to be true about. What survives is the claim that
// mattered independently — the lane that exists must have an executor CI is
// held to running — and it is asserted over the one remaining lane below.

test('every *_dom_cljs_test namespace is selected by the one browser DOM lane (rf2-mf4uy, rf2-0yp7w.6)', () => {
  const text = fs.readFileSync(SHADOW_CLJS, 'utf8');
  const correctness = selectorFor(text, CORRECTNESS_BUILD);
  const namespaces = domTestNamespaces();

  assert.ok(
    namespaces.length > 0,
    'found no *_dom_cljs_test sources at all — the walk is broken, not the config',
  );

  const orphaned = namespaces
    .filter(({ ns }) => !correctness.test(ns))
    .map(({ ns, file }) => `${ns}  (${file})`);

  assert.deepEqual(
    orphaned,
    [],
    'these DOM suites are selected by NO browser lane, so they compile nowhere:\n  ' +
      orphaned.join('\n  '),
  );
});

test('every browser DOM lane has an executor CI is held to running (rf2-j8os)', () => {
  const text = fs.readFileSync(SHADOW_CLJS, 'utf8');
  const scripts = JSON.parse(fs.readFileSync(PACKAGE_JSON, 'utf8')).scripts || {};
  const prefixes = gatePrefixes();
  const declared = dispositionKeys();
  const namespaces = domTestNamespaces();

  for (const buildKey of [CORRECTNESS_BUILD]) {
    const buildId = buildKey.slice(1);            // `:browser-test` -> `browser-test`
    const selector = selectorFor(text, buildKey);
    const selected = namespaces.filter(({ ns }) => selector.test(ns)).map(({ ns }) => ns);
    const carried = `it selects ${selected.length} DOM suite(s):\n  ${selected.join('\n  ')}`;

    const executors = executorsOf(scripts, buildId);
    assert.ok(
      executors.length > 0,
      `no script in implementation/package.json runs \`shadow-cljs compile|release|watch ` +
        `${buildId}\`, so the ${buildKey} lane has nothing that executes it — and ${carried}`,
    );

    const asked = executors.filter((n) => prefixes.some((p) => n.startsWith(p)));
    assert.ok(
      asked.length > 0,
      `the ${buildKey} lane is executed only by ${executors.join(', ')}, and no such name ` +
        `starts with one of ${prefixes.join(' / ')} — so check_gate_scheduling.py never asks ` +
        `whether a workflow runs it, and this lane can lose its CI home in silence. ` +
        `Rename the script into a scanned family, or widen GATE_PREFIXES. Meanwhile ${carried}`,
    );

    const held = asked.filter((n) => !declared.has(n));
    assert.ok(
      held.length > 0,
      `every executor of the ${buildKey} lane (${asked.join(', ')}) carries a DISPOSITIONS ` +
        `entry in scripts/check_gate_scheduling.py, which excuses it from needing a workflow ` +
        `home — so nothing now requires this lane to run anywhere in CI, and ${carried}`,
    );
  }
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
