#!/usr/bin/env node
/*
 * Policy guard for `.github/workflows/lint.yml`: a `.splint.edn` change must reach
 * the Splint job, and that job must keep its teeth. Text assertions over the
 * committed workflow. Discovered by `npm run test:scripts`.
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

// The block from a `<indent><name>:` header to its next sibling.
function sectionBlock(text, headerRe, siblingRe) {
  const m = headerRe.exec(text);
  assert.notEqual(m, null, `section ${headerRe} not found in lint.yml`);
  const rest = text.slice(m.index + 1);
  const next = rest.search(siblingRe);
  return next === -1 ? rest : rest.slice(0, next);
}

test('push path filter includes .splint.edn (rf2-nlnd9y.3)', () => {
  const onBlock = workflow.slice(0, workflow.indexOf('\njobs:'));
  assert.match(
    onBlock,
    /^\s+-\s+'\.splint\.edn'\s*$/m,
    'push trigger paths must list .splint.edn so a .splint.edn-only push runs lint',
  );
});

test('PR detect classifier routes .splint.edn to lint_surface=true (rf2-nlnd9y.3)', () => {
  const detectBlock = sectionBlock(
    workflow,
    /\n {2}detect:\r?\n/,
    /\n {2}[A-Za-z0-9_-]+:\r?\n/,
  );
  assert.match(
    detectBlock,
    /\.splint\.edn\)\s*\r?\n\s*lint_surface=true ;;/,
    'the `.splint.edn)` case arm must set lint_surface=true',
  );
});

test('Splint job is gated on lint_surface and runs with --fail-on-errors (rf2-nlnd9y.3)', () => {
  const splintBlock = sectionBlock(
    workflow,
    /\n {2}splint:\r?\n/,
    /\n {2}[A-Za-z0-9_-]+:\r?\n/,
  );
  assert.match(
    splintBlock,
    /if: needs\.detect\.outputs\.lint_surface == 'true'/,
    'Splint must be gated on lint_surface (the routing a .splint.edn change lights)',
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
