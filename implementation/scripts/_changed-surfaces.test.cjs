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

test('Story feature-load runner changes trigger targeted Story/Causa browser CI', () => {
  const result = classify('tools/story/test/story_feature_load.cjs');
  assert.equal(result.story_causa_browser, 'true');
});

test('Story browser scenario changes trigger targeted Story/Causa browser CI', () => {
  const result = classify('tools/story/test/story_browser_scenarios.cjs');
  assert.equal(result.story_causa_browser, 'true');
});

test('Story feature-load testbed changes trigger targeted Story/Causa browser CI', () => {
  const result = classify('tools/story/testbeds/counter_with_stories/stories.cljs');
  assert.equal(result.story_causa_browser, 'true');
});

test('targeted Story/Causa workflow runs the Story feature-load gate', () => {
  const workflow = fs.readFileSync(WORKFLOW, 'utf8');
  assert.match(workflow, /story-causa-browser:[\s\S]*npm run test:story-feature-load/);
});

// rf2-8xzoe.35 — causa-mcp source-tree changes must fire the
// cljs-bundle-isolation job so the sentinel-based contract from
// check-bundle-isolation.cjs catches a stray :require leaking the
// Node-only MCP-server code into a browser bundle.
test('causa-mcp source changes trigger bundle_isolation CI', () => {
  const result = classify('tools/causa-mcp/src/day8/re_frame2_causa_mcp/server.cljs');
  assert.equal(result.bundle_isolation, 'true');
});

test('causa-mcp test changes trigger bundle_isolation CI', () => {
  const result = classify('tools/causa-mcp/test/day8/re_frame2_causa_mcp/server_test.cljs');
  assert.equal(result.bundle_isolation, 'true');
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
