#!/usr/bin/env node
/*
 * Workflow-policy guard for `.github/workflows/docs.yml` (rf2-k8l1m).
 *
 * docs.yml carries TWO concurrency concerns on one workflow: the main-push
 * Pages DEPLOY path, and an unfiltered pull_request build-only regression
 * check. They shared a single `pages` group, so a routine PR docs check could
 * cancel a queued main-push deployment before any job started (GitHub keeps
 * one running + one pending run per group and drops the older pending one even
 * with cancel-in-progress: false). This guard pins the event-scoped split, and
 * — critically — pins the invariant that makes the split safe: `deploy` runs
 * on `push` only, so per-PR groups can never yield two concurrent Pages
 * deployments.
 *
 * TEXT/policy assertions over the committed workflow — no Actions runtime
 * needed — mirroring `_lint-workflow-policy.test.cjs`. Wired into
 * `test:script-policy`.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const DOCS_WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'docs.yml');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

const workflow = fs.readFileSync(DOCS_WORKFLOW, 'utf8');

// The workflow-level `concurrency:` block — the one at column 0, before
// `jobs:`. Scoped so a job-level block (should one ever be added) can't
// satisfy these assertions by accident.
const concurrencyBlock = (() => {
  const head = workflow.slice(0, workflow.indexOf('\njobs:'));
  const m = /\nconcurrency:\r?\n((?:[ \t]+\S.*\r?\n)+)/.exec(head);
  assert.notEqual(m, null, 'docs.yml must declare a workflow-level concurrency block');
  return m[1];
})();

test('concurrency group is event-scoped, not a bare shared `pages` (rf2-k8l1m)', () => {
  const group = /^\s*group:\s*(.+?)\s*$/m.exec(concurrencyBlock);
  assert.notEqual(group, null, 'concurrency block must declare a group');
  assert.notEqual(
    group[1],
    'pages',
    'a bare `group: pages` puts unfiltered PR docs checks in the Pages deploy ' +
      'group, where they cancel queued main-push deployments (rf2-k8l1m)',
  );
  assert.match(
    group[1],
    /github\.event_name\s*==\s*'pull_request'/,
    'the group key must branch on github.event_name so PR runs and deploy runs ' +
      'land in different groups',
  );
});

test('pull_request runs get a per-PR group (rf2-k8l1m)', () => {
  assert.match(
    concurrencyBlock,
    /github\.event\.pull_request\.number/,
    'the PR arm of the group key must include the PR number so one PR cannot ' +
      'cancel another PR (or a main deploy)',
  );
});

test('non-PR (push / dispatch) runs still serialize in the `pages` group (rf2-k8l1m)', () => {
  assert.match(
    concurrencyBlock,
    /\|\|\s*'pages'/,
    'the non-PR arm must remain the single `pages` group so Pages publishes ' +
      'one artifact at a time',
  );
});

test('cancel-in-progress is PR-only — a running deploy is never cancelled (rf2-k8l1m)', () => {
  const cancel = /^\s*cancel-in-progress:\s*(.+?)\s*$/m.exec(concurrencyBlock);
  assert.notEqual(cancel, null, 'concurrency block must declare cancel-in-progress');
  assert.notEqual(
    cancel[1],
    'true',
    'an unconditional cancel-in-progress would let a new run kill an ' +
      'in-flight Pages deployment mid-publish',
  );
  assert.match(
    cancel[1],
    /github\.event_name\s*==\s*'pull_request'/,
    'cancel-in-progress must be scoped to pull_request so only PR runs supersede',
  );
});

// The safety interlock for the split above. Per-PR concurrency groups are only
// safe because a PR run cannot deploy. If `deploy` ever loses its push-only
// gate, two PR runs in two different groups could publish Pages concurrently.
test('deploy job stays gated to push events — the interlock the split relies on (rf2-k8l1m)', () => {
  const deployHeader = /\n {2}deploy:\r?\n/.exec(workflow);
  assert.notEqual(deployHeader, null, 'docs.yml must declare a `deploy` job');
  const rest = workflow.slice(deployHeader.index + 1);
  const next = rest.search(/\n {2}[A-Za-z0-9_-]+:\r?\n/);
  const deployBlock = next === -1 ? rest : rest.slice(0, next);
  assert.match(
    deployBlock,
    /if:\s*github\.event_name\s*==\s*'push'/,
    'the deploy job must run only on push; per-PR concurrency groups are safe ' +
      'ONLY because a PR run never publishes Pages',
  );
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
  console.error(`docs-workflow-policy tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`docs-workflow-policy tests: ${tests.length} passed.`);
