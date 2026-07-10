#!/usr/bin/env node
/*
 * Workflow-policy guard for `.github/workflows/lint.yml`.
 *
 * `.splint.edn` at repo root is the Splint gate's per-project config — Splint
 * reads it live once the job runs. This guard pins that `.splint.edn` is wired
 * into both routing surfaces and
 * that the Splint job stays gated on `lint_surface` (so the routing is what
 * decides whether Splint runs). It is a TEXT/policy assertion over the
 * committed workflow — no Actions runtime needed — mirroring the
 * test.yml shape assertions in `_changed-surfaces.test.cjs`. Wired into
 * `test:script-policy`.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const LINT_WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'lint.yml');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

const workflow = fs.readFileSync(LINT_WORKFLOW, 'utf8');

// Narrow the workflow text to a named job/section block: from a `\n<indent>
// <name>:` header to the next sibling at the same indent. Used to scope
// assertions so a match can't leak across sections.
function sectionBlock(text, headerRe, siblingRe) {
  const m = headerRe.exec(text);
  assert.notEqual(m, null, `section ${headerRe} not found in lint.yml`);
  const rest = text.slice(m.index + 1);
  const next = rest.search(siblingRe);
  return next === -1 ? rest : rest.slice(0, next);
}

test('push path filter includes .splint.edn (rf2-nlnd9y.3)', () => {
  // The `on: push: paths:` block lives before `jobs:`. Assert the quoted
  // `.splint.edn` entry appears in the trigger section.
  const onBlock = workflow.slice(0, workflow.indexOf('\njobs:'));
  assert.match(
    onBlock,
    /^\s+-\s+'\.splint\.edn'\s*$/m,
    'push trigger paths must list .splint.edn so a .splint.edn-only push runs lint',
  );
});

test('PR detect classifier routes .splint.edn to lint_surface=true (rf2-nlnd9y.3)', () => {
  // The detect job's case statement classifies changed files. Assert there is
  // a `.splint.edn)` case arm that sets lint_surface=true.
  const detectBlock = sectionBlock(
    workflow,
    /\n {2}detect:\r?\n/,
    /\n {2}[A-Za-z0-9_-]+:\r?\n/,
  );
  // A `.splint.edn)` case label must be present...
  assert.match(
    detectBlock,
    /\.splint\.edn\)/,
    'detect classifier must carry a `.splint.edn)` case arm',
  );
  // ...and the arm must set lint_surface=true (the arm is `.splint.edn)`
  // immediately followed by the `lint_surface=true ;;` body, allowing CRLF).
  assert.match(
    detectBlock,
    /\.splint\.edn\)\s*\r?\n\s*lint_surface=true ;;/,
    'the `.splint.edn)` case arm must set lint_surface=true',
  );
});

test('Splint job stays gated on lint_surface so routing decides whether it runs (rf2-nlnd9y.3)', () => {
  const splintBlock = sectionBlock(
    workflow,
    /\n {2}splint:\r?\n/,
    /\n {2}[A-Za-z0-9_-]+:\r?\n/,
  );
  assert.match(splintBlock, /needs: detect/);
  assert.match(
    splintBlock,
    /if: needs\.detect\.outputs\.lint_surface == 'true'/,
    'Splint must be gated on lint_surface (the routing the .splint.edn fix lights)',
  );
});

test('Splint job still runs with --fail-on-errors (regression: teeth intact) (rf2-nlnd9y.3)', () => {
  const splintBlock = sectionBlock(
    workflow,
    /\n {2}splint:\r?\n/,
    /\n {2}[A-Za-z0-9_-]+:\r?\n/,
  );
  assert.match(splintBlock, /--fail-on-errors/);
});

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

if (failed > 0) {
  console.error(`lint-workflow-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`lint-workflow-policy tests: ${tests.length} passed.`);
