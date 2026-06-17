#!/usr/bin/env node

'use strict';

/*
 * Shared utilities for the source-policy gate suites (rf2-j552l2).
 *
 * The `_*-policy.test.cjs` gates are static source scanners: they read a
 * first-party script's text and assert on it. Three pieces had drifted into
 * verbatim copies across them (rf2-u4vtlh / rf2-b4d7o1 review):
 *
 *   - `stripComments` — a deliberately simple (NOT a JS parser) stripper of
 *     line- and block-comments that LEAVES string literals intact, so a
 *     policy assertion matches EXECUTABLE source only and a doc-comment that
 *     necessarily quotes the very token the gate forbids/requires is not a
 *     false positive (or, for a forbidden token, a false negative). Three
 *     byte-identical copies lived in _mcp-conformance-install-policy /
 *     _orchestrator-teardown-policy / _script-spawn-policy.
 *
 *   - the standalone test harness (`const tests=[]; function test(){...}` +
 *     the run loop + the `<label> tests: N passed/failed.` footer) the
 *     framework-free `_*.test.cjs` convention repeats.
 *
 *   - `loopbackBindRe` — the http-server `-a 127.0.0.1` loopback-bind
 *     matcher. Two callers ( _script-spawn-policy + _story-script-runners-
 *     policy) shared the identical tail `,[\s\S]{0,80}?'-a','127.0.0.1'`
 *     but differ in the bin-token alternation they anchor it to; the factory
 *     keeps each caller's exact token set so neither gate's strictness
 *     changes.
 *
 * This is a plain helper module (NOT a `.test.cjs`), so the `test:script-*`
 * runners don't execute it as a suite; the policy suites `require` it.
 */

// Strip line- and block-comments so policy assertions match EXECUTABLE
// source only — the doc-comments necessarily quote the very symbols the
// gates require/forbid. String literals are left intact (so a real
// `shell: true` hidden in a literal would still trip its gate).
// Deliberately simple: a residue stripper over our own first-party
// scripts, not a general-purpose JS parser.
function stripComments(src) {
  let out = '';
  let i = 0;
  const n = src.length;
  let inLine = false;
  let inBlock = false;
  let inStr = false;
  let strQuote = '';
  while (i < n) {
    const c = src[i];
    const c2 = src[i + 1];
    if (inLine) {
      if (c === '\n') { inLine = false; out += c; }
      i += 1;
      continue;
    }
    if (inBlock) {
      if (c === '*' && c2 === '/') { inBlock = false; i += 2; continue; }
      i += 1;
      continue;
    }
    if (inStr) {
      out += c;
      if (c === '\\') { out += c2 == null ? '' : c2; i += 2; continue; }
      if (c === strQuote) { inStr = false; strQuote = ''; }
      i += 1;
      continue;
    }
    if (c === '/' && c2 === '/') { inLine = true; i += 2; continue; }
    if (c === '/' && c2 === '*') { inBlock = true; i += 2; continue; }
    if (c === '"' || c === "'" || c === '`') {
      inStr = true; strQuote = c; out += c; i += 1; continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

// Build the http-server loopback-bind matcher. `binTokenPattern` is the
// RegExp-source alternation for the bin token a launcher names before its
// `'-a', '127.0.0.1'` argument pair (e.g. `'HTTP_SERVER_BIN'` for the
// constant form, or `'(?:httpServerBin\\(\\)|HTTP_SERVER_BIN)'` to also
// accept the lazy-resolver form). The shared tail tolerates a short window
// of intervening args/whitespace between the bin token and the loopback
// pair. NB `[\s\S]{0,80}?` (not `[^]`): the JS-regex `[^]`-any-char gotcha.
function loopbackBindRe(binTokenPattern) {
  return new RegExp(
    `${binTokenPattern},[\\s\\S]{0,80}?['"]-a['"]\\s*,\\s*['"]127\\.0\\.0\\.1['"]`,
  );
}

// Create a standalone, framework-free test harness matching the
// `_*.test.cjs` convention. `label` names the suite in the footer.
//   opts.perTestPass : true ⇒ print `PASS  <name>` per passing test and
//                      `FAIL  <name>` (two-space) on failure (the
//                      _mcp-conformance-install-policy posture); false
//                      (default) ⇒ no per-test PASS line, `FAIL <name>`
//                      (one-space) on failure (the other policy suites).
// Returns { test, run }: `test(name, fn)` registers; `run()` executes all
// registered tests, prints the footer, and `process.exit(1)` on any
// failure (otherwise returns normally).
function createPolicyTestSuite(label, opts = {}) {
  const { perTestPass = false } = opts;
  const tests = [];
  function test(name, fn) {
    tests.push({ name, fn });
  }
  function run() {
    let failed = 0;
    for (const { name, fn } of tests) {
      try {
        fn();
        if (perTestPass) console.log(`PASS  ${name}`);
      } catch (err) {
        failed += 1;
        console.error(`${perTestPass ? 'FAIL ' : 'FAIL'} ${name}`);
        console.error(err && err.stack ? err.stack : err);
      }
    }
    if (failed > 0) {
      console.error(`${label} tests: ${failed} failed.`);
      process.exit(1);
    }
    console.log(`${label} tests: ${tests.length} passed.`);
  }
  return { test, run };
}

module.exports = {
  stripComments,
  loopbackBindRe,
  createPolicyTestSuite,
};
