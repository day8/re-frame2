#!/usr/bin/env node

'use strict';

/*
 * Source-policy gate: NO NAVIGATION INHERITS AN ANONYMOUS CEILING.
 *
 * `page.goto(url)` and `page.reload()` apply Playwright's default 30s timeout
 * whenever no `timeout:` is passed. That is a SECOND budget on every lane that
 * navigates: invisible in the source, unreachable from the runner's own knob,
 * and indistinguishable in a CI log from the budget the runner does own, so the
 * fix reached for is a bigger lane timeout, which cannot move it.
 *
 * The defect is a CLASS, so this is a repository-wide SWEEP rather than
 * per-file assertions, and a STATIC one because the ceiling is dormant in a
 * healthy tree. It does not require `waitUntil: 'commit'` — whether a site waits
 * for `load` is a per-site judgement — only that whatever event a navigation
 * waits for, it NAMES THE NUMBER. There is no waiver table.
 *
 * Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');
const { createPolicyTestSuite } = require('./_policy-test-util.cjs');
const {
  walkDir,
  assertWalkComplete,
} = require('../../examples/scripts/walk-tree.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');

const { test, run } = createPolicyTestSuite('navigation-ceiling-policy');

/*
 * Every Playwright API that accepts `{ timeout }` and silently defaults it to
 * 30s, including ones with no call in the repo yet: the point of a sweep is to
 * be there first. `waitForSelector` is not a navigation, but a bare element-wait
 * is the same anonymous 30s no lane knob can reach.
 *
 * The lookbehind demands a RECEIVER (`page.goto(`, `dev.goto(`), which separates
 * a call from the string literal `'.goto('`; `\s*` inside it tolerates a
 * receiver left on the previous line.
 */
const NAV_METHODS = ['goto', 'reload', 'goBack', 'goForward', 'waitForNavigation',
  'waitForSelector'];
const NAV_CALL_RE = new RegExp(
  `(?<=[\\w$\\)\\]]\\s*)\\.(${NAV_METHODS.join('|')})\\s*\\(`,
  'g',
);

// Directories a source sweep never descends into: dependency trees, compiler
// and site output, and the local-only `ai/` tree. A POLICY prune, distinct from
// an unreadable directory, which fails the walk CLOSED below.
const PRUNED_DIRS = new Set([
  '.git', 'node_modules', '.shadow-cljs', '.cpcache', '.clj-kondo',
  'out', 'target', 'dist', 'site', 'coverage', 'test-results',
  'playwright-report', 'ai', '.beads', '.venv', '__pycache__',
]);

/*
 * The argument list of the call whose `(` is at `open`, by BALANCED-PAREN scan.
 *
 * Not a fixed-size window: reading a flat 300 characters after the call would
 * pass a bare `dev.goto(url);` followed three lines later by a
 * `waitForFunction(..., { timeout: TIMEOUT })`. String literals are skipped so a
 * paren inside a URL cannot unbalance the count. A residue scanner over our own
 * scripts, not a JS parser: it does not model `${}` interpolation nesting.
 */
function callArgs(src, open) {
  let depth = 0;
  for (let i = open; i < src.length; i += 1) {
    const c = src[i];
    if (c === '"' || c === "'" || c === '`') {
      i += 1;
      while (i < src.length && src[i] !== c) {
        if (src[i] === '\\') i += 1;
        i += 1;
      }
      continue;
    }
    if (c === '(') depth += 1;
    else if (c === ')') {
      depth -= 1;
      if (depth === 0) return src.slice(open + 1, i);
    }
  }
  // Unbalanced — return the tail rather than silently dropping a navigation.
  return src.slice(open + 1);
}

/*
 * Blank out comments, so the doc-comments that quote the forbidden shape are not
 * read as code. Not `_policy-test-util.cjs`'s `stripComments`: that one does not
 * track REGEX-LITERAL state, so a regex containing a quote desyncs it. Only
 * WHOLE-line comments, since a trailing `//` strip would truncate the `http://…`
 * URLs these calls navigate to; block comments are blanked in place.
 */
function codeOnly(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .split('\n')
    .map((line) => (/^\s*(\/\/|\*)/.test(line) ? '' : line))
    .join('\n');
}

// Split a source into its navigations: how many there are, and which of them
// pass no `timeout:` of their own.
function navigationsIn(src) {
  const code = codeOnly(src);
  const all = [];
  const bare = [];
  for (const m of code.matchAll(NAV_CALL_RE)) {
    const open = m.index + m[0].length - 1;
    const args = callArgs(code, open);
    const rendered = `.${m[1]}(${args.replace(/\s+/g, ' ').trim().slice(0, 90)})`;
    all.push(rendered);
    if (!/\btimeout:/.test(args)) bare.push(rendered);
  }
  return { all, bare };
}

// Every first-party `.cjs` / `.mjs` in the repo, FAIL-CLOSED: an unreadable
// directory throws rather than quietly shrinking the scan.
function scannedFiles() {
  const { items, walkErrors } = walkDir({
    roots: [REPO_ROOT],
    skipDir: (name) => PRUNED_DIRS.has(name),
    acceptFile: (name) => name.endsWith('.cjs') || name.endsWith('.mjs'),
  });
  assertWalkComplete(walkErrors, 'navigation-ceiling sweep');
  return items.sort();
}

// ---------------------------------------------------------------------------

test("every navigation passes an EXPLICIT timeout — none inherits Playwright's 30s default (rf2-dczpv, rf2-bhjzn, rf2-taj9b)", () => {
  const offenders = [];
  let navigations = 0;

  for (const file of scannedFiles()) {
    const { all, bare } = navigationsIn(fs.readFileSync(file, 'utf8'));
    navigations += all.length;
    const rel = path.relative(REPO_ROOT, file).replace(/\\/g, '/');
    for (const call of bare) offenders.push(`${rel}: ${call}`);
  }

  assert.ok(navigations > 0, 'the sweep must actually find navigations to check');
  assert.deepEqual(
    offenders,
    [],
    'A navigation must pass its OWN `timeout:`, tied to the budget that ' +
      'bounds it. Without one Playwright applies a 30s default that no lane ' +
      'budget can reach and whose CI failure line reads like the lane timeout ' +
      'it is not — so the fix reached for is a bigger lane budget, which ' +
      'moves nothing. Spec-side callers can use `navigate` / `reloadPage` ' +
      'from examples/scripts/spec-helpers.cjs, which require the number. ' +
      'There is no waiver table: fix the call.',
  );
});

// The static sweep sees the navigation inside `spec-helpers.cjs` and stops
// there; it cannot follow a CALLER of `navigate` to check that it supplied a
// number. The helpers close that gap at runtime, and REFUSING beats defaulting,
// because a default would be the very ceiling this gate removes, relocated.
//
// Probed before `run()` because the policy-suite harness is synchronous.
let helperContractFailures = null;

test('the shared spec helpers REFUSE a navigation with no timeout (rf2-taj9b)', () => {
  assert.deepEqual(
    helperContractFailures,
    [],
    'navigate / reloadPage must reject a missing or non-numeric timeoutMs ' +
      'before touching the page — a defaulted one is the anonymous ceiling ' +
      'again, just written in our own source',
  );
});

async function probeHelperContract() {
  const { navigate, reloadPage } = require('../../examples/scripts/spec-helpers.cjs');
  // A page whose navigation methods THROW, so "the helper called through" is
  // distinguishable from "the helper refused".
  const page = {
    goto: () => { throw new Error('navigate must reject BEFORE touching the page'); },
    reload: () => { throw new Error('reloadPage must reject BEFORE touching the page'); },
  };
  const cases = [
    ['navigate, no options', () => navigate(page, 'http://127.0.0.1/')],
    ['navigate, non-numeric', () => navigate(page, 'http://127.0.0.1/', { timeoutMs: '30000' })],
    ['navigate, zero', () => navigate(page, 'http://127.0.0.1/', { timeoutMs: 0 })],
    ['reloadPage, no options', () => reloadPage(page)],
  ];
  const failures = [];
  for (const [name, call] of cases) {
    try {
      await call();
      failures.push(`${name}: resolved instead of refusing`);
    } catch (err) {
      if (!/timeoutMs is REQUIRED/.test(err.message)) {
        failures.push(`${name}: rejected for the wrong reason — ${err.message}`);
      }
    }
  }
  return failures;
}

probeHelperContract()
  .then((failures) => { helperContractFailures = failures; })
  .catch((err) => { helperContractFailures = [`probe threw: ${err.message}`]; })
  .then(run);
