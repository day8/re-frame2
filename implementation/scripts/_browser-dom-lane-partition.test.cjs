#!/usr/bin/env node

'use strict';

/*
 * Every `*_dom_cljs_test` namespace must be selected by `:browser-test`, the one
 * browser DOM lane, and that lane must have an executor CI is held to running. A
 * pattern that stops selecting a namespace changes no exit code, so the relation
 * is derived — the pattern from shadow-cljs.edn, the namespaces from the files'
 * own (ns ...) forms — and asserted. Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const IMPL_DIR = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_DIR, '..');
const SHADOW_CLJS = path.join(IMPL_DIR, 'shadow-cljs.edn');
const PACKAGE_JSON = path.join(IMPL_DIR, 'package.json');
const GATE_SCHEDULING = path.join(REPO_ROOT, 'scripts', 'check_gate_scheduling.py');

const CORRECTNESS_BUILD = ':browser-test';

// Compiled copies under out/, node_modules/ and .shadow-cljs/ would double-count.
const SEARCH_ROOTS = ['implementation', 'tools'];
const SKIP_DIRS = new Set(['node_modules', 'out', '.shadow-cljs', '.git', 'target']);
const DOM_TEST_RE = /_dom_cljs_test\.clj[sc]$/;

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// ---- deriving the selectors ------------------------------------------------

// A build's text runs from its two-space-indented keyword line to the next one.
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
// The chain is: build id -> the npm script that compiles it -> a workflow `run:`.
// The last link is delegated to scripts/check_gate_scheduling.py, which requires
// every scanned script to have an executable `run:` home unless it carries a
// DISPOSITIONS entry; this suite holds the executor to that question.

// Whole tokens, so `browser-test` is not read as the executor of `browser-test-bench`.
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
  const scripts = JSON.parse(fs.readFileSync(PACKAGE_JSON, 'utf8')).scripts || {};
  const buildId = CORRECTNESS_BUILD.slice(1);
  const executors = executorsOf(scripts, buildId);
  const prefixes = gatePrefixes();
  const asked = executors.filter((n) => prefixes.some((p) => n.startsWith(p)));
  assert.ok(
    asked.length > 0,
    `the ${CORRECTNESS_BUILD} lane's executors (${executors.join(', ') || 'none'}) start with none of ` +
      `${prefixes.join(' / ')}, so check_gate_scheduling.py never asks whether a workflow runs it`,
  );
  const declared = dispositionKeys();
  assert.ok(
    asked.some((n) => !declared.has(n)),
    `every executor of ${CORRECTNESS_BUILD} (${asked.join(', ')}) has a DISPOSITIONS entry in ` +
      'scripts/check_gate_scheduling.py, so nothing requires the lane to run in CI',
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
