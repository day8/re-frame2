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

// rf2-wa3oo — the story-causa-browser PR job now runs the PR-SMOKE
// tier, not the full sweep. It runs the Causa gate in --smoke mode and
// the single-testbed Story :play-script gate (which renders the
// assertion-strip, keeping rf2-5lw9w covered per-PR). The full sweep —
// test:story-feature-load, the non-smoke test:causa-feature-gate, and
// test:story-static — moved to the nightly expensive-tests.yml workflow.
const EXPENSIVE_WORKFLOW = path.join(
  REPO_ROOT,
  '.github',
  'workflows',
  'expensive-tests.yml',
);

// Narrow the workflow text to the story-causa-browser job block so the
// per-tier assertions can't accidentally match a step in a sibling job.
function storyCausaJobBlock(workflow) {
  const start = workflow.indexOf('\n  story-causa-browser:');
  assert.notEqual(start, -1, 'story-causa-browser job not found in test.yml');
  // The next top-level job starts at the next `\n  <name>:` at 2-space
  // indent. Find it from just after the job header.
  const rest = workflow.slice(start + 1);
  const nextJob = rest.search(/\n {2}[A-Za-z0-9_-]+:\n/);
  return nextJob === -1 ? rest : rest.slice(0, nextJob);
}

test('PR story-causa-browser job runs the Causa --smoke gate (rf2-wa3oo)', () => {
  const block = storyCausaJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.match(block, /npm run test:causa-feature-gate:smoke/);
});

test('PR story-causa-browser job keeps the Story :play-script gate (assertion-strip cover, rf2-5lw9w)', () => {
  const block = storyCausaJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.match(block, /npm run test:story-play-scripts/);
});

test('PR story-causa-browser job does NOT run the full sweep (moved to nightly, rf2-wa3oo)', () => {
  const block = storyCausaJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.doesNotMatch(block, /npm run test:story-feature-load/);
  assert.doesNotMatch(block, /npm run test:story-static/);
  // The non-smoke (full-matrix) Causa gate must not run at PR time. The
  // `:smoke` suffix is intentionally allowed; assert the bare invocation
  // (followed by end-of-line, not `:smoke`) is absent.
  assert.doesNotMatch(block, /npm run test:causa-feature-gate(?!:smoke)/);
});

test('Nightly expensive workflow runs the full Story/Causa sweep (rf2-wa3oo)', () => {
  const workflow = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  assert.match(workflow, /npm run test:story-feature-load/);
  assert.match(workflow, /npm run test:causa-feature-gate\b/);
  assert.match(workflow, /npm run test:story-static/);
  assert.match(workflow, /npm run test:story-play-scripts/);
});

test('PR + nightly Story/Causa jobs cache the shadow-cljs compile output (rf2-og36y)', () => {
  const prBlock = storyCausaJobBlock(fs.readFileSync(WORKFLOW, 'utf8'));
  assert.match(prBlock, /implementation\/\.shadow-cljs/);
  assert.match(prBlock, /story-causa-shadow-/);
  const nightly = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
  assert.match(nightly, /implementation\/\.shadow-cljs/);
  assert.match(nightly, /story-causa-shadow-/);
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
