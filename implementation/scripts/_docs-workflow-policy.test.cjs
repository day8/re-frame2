#!/usr/bin/env node
/*
 * Policy guard for `.github/workflows/docs.yml`: PR docs checks and main-push
 * Pages deploys must not share one concurrency group, or a PR run cancels a
 * queued deploy. Text assertions over the committed workflow. Discovered by
 * `npm run test:scripts`.
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

test('concurrency is event-scoped: per-PR groups, push serialises on `pages`, only PRs cancel (rf2-k8l1m)', () => {
  const group = /^\s*group:\s*(.+?)\s*$/m.exec(concurrencyBlock)[1];
  assert.match(group, /github\.event_name\s*==\s*'pull_request'/, 'the group must branch on the event');
  assert.match(concurrencyBlock, /github\.event\.pull_request\.number/, 'PR runs need a per-PR group');
  assert.match(concurrencyBlock, /\|\|\s*'pages'/, 'non-PR runs must serialise in `pages`');
  const cancel = /^\s*cancel-in-progress:\s*(.+?)\s*$/m.exec(concurrencyBlock)[1];
  assert.match(cancel, /github\.event_name\s*==\s*'pull_request'/, 'cancel-in-progress must be PR-only');
});

// Per-PR groups are safe only because a PR run can never deploy Pages.
test('deploy job stays gated to push events — the interlock the split relies on (rf2-k8l1m)', () => {
  const rest = workflow.slice(/\n {2}deploy:\r?\n/.exec(workflow).index + 1);
  const next = rest.search(/\n {2}[A-Za-z0-9_-]+:\r?\n/);
  assert.match(next === -1 ? rest : rest.slice(0, next), /if:\s*github\.event_name\s*==\s*'push'/);
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
