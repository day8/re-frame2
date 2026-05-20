#!/usr/bin/env node

'use strict';

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'test.yml');

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function classify(...files) {
  const quote = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;
  const command = ['./.github/scripts/report-changed-surfaces.sh', ...files.map(quote)].join(' ');
  const env = { ...process.env };
  delete env.GITHUB_OUTPUT;
  const out = execFileSync('bash', ['-lc', command], {
    cwd: REPO_ROOT,
    env,
    encoding: 'utf8',
  });
  return Object.fromEntries(
    out
      .trim()
      .split(/\r?\n/)
      .filter(Boolean)
      .map((line) => line.split('=')),
  );
}

// rf2-k9ekz — Story/Causa browser Playwright gate trigger is narrowed
// to runtime-source changes under tools/{story,causa}/{src,testbeds}/**
// AND a runtime-extension (.cljs/.cljc/.js/.cjs/.css/.scss). Spec /
// test / EDN / deps / Markdown changes do not fire it.

test('Story src .cljs changes trigger story_causa_browser', () => {
  const result = classify('tools/story/src/foo.cljs');
  assert.equal(result.story_causa_browser, 'true');
});

test('Causa src .cljs changes trigger story_causa_browser', () => {
  const result = classify('tools/causa/src/foo.cljs');
  assert.equal(result.story_causa_browser, 'true');
});

test('Story testbed .cljs changes trigger story_causa_browser (gate runs the testbed)', () => {
  const result = classify('tools/story/testbeds/counter_with_stories/stories.cljs');
  assert.equal(result.story_causa_browser, 'true');
});

test('Causa feature_matrix testbed .cljs changes trigger story_causa_browser', () => {
  const result = classify('tools/causa/testbeds/feature_matrix/core.cljs');
  assert.equal(result.story_causa_browser, 'true');
});

test('Story spec-only .md changes do NOT trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/spec/Spec.md');
  assert.equal(result.story_causa_browser, 'false');
});

test('Causa spec-only .md changes do NOT trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/causa/spec/017-Test-Coverage-Matrix.md');
  assert.equal(result.story_causa_browser, 'false');
});

test('Story test-only changes do NOT trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/test/story_feature_load.cjs');
  assert.equal(result.story_causa_browser, 'false');
});

test('Causa test-only changes do NOT trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/causa/test/some_test.clj');
  assert.equal(result.story_causa_browser, 'false');
});

test('Story deps.edn changes do NOT trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/deps.edn');
  assert.equal(result.story_causa_browser, 'false');
});

test('Story README.md changes do NOT trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/README.md');
  assert.equal(result.story_causa_browser, 'false');
});

test('Mixed Story src + spec change DOES trigger story_causa_browser (rf2-k9ekz)', () => {
  const result = classify('tools/story/src/foo.cljs', 'tools/story/spec/bar.md');
  assert.equal(result.story_causa_browser, 'true');
});

test('targeted Story/Causa workflow runs the Story feature-load gate', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(workflow, /story-causa-browser:[\s\S]*npm run test:story-feature-load/);
});

// rf2-t5slp — the framework-testbeds gate was retired after all four
// rf2-tglku migration waves moved every framework + top-level testbed
// Playwright spec.cjs to CLJS/JVM unit tests. The classifier no longer
// emits a `framework_testbeds` output; testbed source diffs only light
// `cljs_browser` (for the transitive CLJS compile coverage).

test('framework_testbeds output is no longer emitted (rf2-t5slp)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(result.framework_testbeds, undefined);
});

test('top-level testbed .cljs change fires cljs_browser only (rf2-t5slp)', () => {
  const result = classify('testbeds/ssr_basic/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'false');
  assert.equal(result.cljs_browser, 'true');
});

test('Causa testbed .cljs change fires story_causa_browser only (rf2-t5slp)', () => {
  const result = classify('tools/causa/testbeds/feature_matrix/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'false');
  assert.equal(result.story_causa_browser, 'true');
});

test('Adapter source change fires adapter_testbed_smokes (rf2-t5slp regression guard)', () => {
  const result = classify('implementation/adapters/reagent/testbed/core.cljs');
  assert.equal(result.adapter_testbed_smokes, 'true');
});

test('framework-testbeds workflow job is removed (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.doesNotMatch(workflow, /^\s*framework-testbeds:/m);
  assert.doesNotMatch(workflow, /framework_testbeds/);
});

test('adapter-testbed-smokes workflow remains scoped to EXAMPLES_FILTER=adapters/ (rf2-t5slp)', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  // Find the adapter-testbed-smokes job block and verify it still
  // passes the narrow adapters/ filter — the only Playwright surface
  // under the examples orchestrator after framework-testbeds retired.
  assert.match(
    workflow,
    /adapter-testbed-smokes:[\s\S]*EXAMPLES_FILTER:\s*"adapters\/"/,
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
  console.error(`changed-surfaces tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`changed-surfaces tests: ${tests.length} passed.`);
