#!/usr/bin/env node
/*
 * Inventory/lockstep guard for `scripts/test-rigorous-local.sh` (rf2-lm5mu9).
 *
 * The rigorous local script is the local mirror of the rigorous
 * browser-bundle-and-story sweep in `.github/workflows/expensive-tests.yml`.
 * It had drifted behind that sweep: it ran `test:story-feature-load`,
 * `test:xray-feature-gate`, and `test:story-static` but omitted
 * `test:story-play-scripts` (a sweep command) and `test:examples-compile`
 * (the example-build compile gate that test.yml runs in its own parallel
 * cljs-examples-compile job — split out of cljs-browser per rf2-9cw850). A
 * developer running `scripts/test-rigorous-local.sh`
 * before a release-sized change could get `PASS rigorous local suite` without
 * the example-compile coverage gate or the Story play-script browser gate —
 * exactly the wiring regressions those gates were added to catch.
 *
 * This guard pins, as a TEXT/policy assertion over committed files (no Actions
 * runtime needed, mirroring the test.yml-shape assertions in
 * `_lint-workflow-policy.test.cjs`):
 *
 *  1. Every `npm run test:*` command in the expensive-tests.yml "Run rigorous
 *     implementation browser and bundle gates" step also runs in the local
 *     script (lockstep — the local mirror must not drift BEHIND the nightly
 *     sweep for implementation browser/bundle commands).
 *  2. The two commands the bead names — `test:examples-compile` and
 *     `test:story-play-scripts` — are present in the local script (a direct
 *     pin, independent of the parse, so neither can silently drop out again).
 *  3. Every command the local script invokes is a real `package.json` script
 *     (catches a typo'd or renamed-away gate).
 *
 * Wired into `test:script-policy`.
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const path = require('path');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const RIGOROUS_SCRIPT = path.join(REPO_ROOT, 'scripts', 'test-rigorous-local.sh');
const EXPENSIVE_WORKFLOW = path.join(REPO_ROOT, '.github', 'workflows', 'expensive-tests.yml');
const PACKAGE_JSON = path.join(IMPL_ROOT, 'package.json');

const tests = [];
function test(name, fn) {
  tests.push({ name, fn });
}

// Pull every `npm run <script>` invocation out of a blob of shell text,
// returning the set of script names (e.g. "test:browser"). Tolerates the
// trailing ` && \` continuation form used by the local script.
function npmRunScripts(text) {
  const out = new Set();
  const re = /npm run ([A-Za-z0-9:._-]+)/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    out.add(m[1]);
  }
  return out;
}

const scriptText = fs.readFileSync(RIGOROUS_SCRIPT, 'utf8');
const workflowText = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
const pkg = JSON.parse(fs.readFileSync(PACKAGE_JSON, 'utf8'));

const localScripts = npmRunScripts(scriptText);

// Narrow the workflow to the rigorous browser/bundle `run: |` step body: from
// the named step header to the next `- name:` sibling. Scopes the parse so we
// only compare against the implementation browser/bundle sweep, not other
// steps in the file.
function rigorousSweepStep(text) {
  const headerRe = /- name: Run rigorous implementation browser and bundle gates\r?\n/;
  const m = headerRe.exec(text);
  assert.notEqual(
    m,
    null,
    'expensive-tests.yml must carry the "Run rigorous implementation browser and bundle gates" step',
  );
  const rest = text.slice(m.index + m[0].length);
  const next = rest.search(/\n\s+- name:/);
  return next === -1 ? rest : rest.slice(0, next);
}

const sweepScripts = npmRunScripts(rigorousSweepStep(workflowText));

test('expensive-tests.yml sweep is non-empty (parse sanity) (rf2-lm5mu9)', () => {
  assert.ok(
    sweepScripts.size >= 8,
    `expected the rigorous sweep to list many npm scripts, parsed ${sweepScripts.size}`,
  );
});

test('local rigorous script runs every expensive-tests.yml sweep command (lockstep) (rf2-lm5mu9)', () => {
  const missing = [...sweepScripts].filter((s) => !localScripts.has(s));
  assert.deepEqual(
    missing,
    [],
    `scripts/test-rigorous-local.sh has drifted BEHIND expensive-tests.yml — missing: ${missing.join(', ')}. `
      + 'Keep the local mirror in lockstep with the nightly browser/bundle sweep.',
  );
});

test('local rigorous script pins examples-compile + story-play-scripts (rf2-lm5mu9)', () => {
  for (const required of ['test:examples-compile', 'test:story-play-scripts']) {
    assert.ok(
      localScripts.has(required),
      `scripts/test-rigorous-local.sh must run \`npm run ${required}\` `
        + '(the two implementation gates rf2-lm5mu9 fixed the omission of).',
    );
  }
});

test('every command the local rigorous script runs is a real package.json script (rf2-lm5mu9)', () => {
  const unknown = [...localScripts].filter((s) => !(s in (pkg.scripts || {})));
  assert.deepEqual(
    unknown,
    [],
    `scripts/test-rigorous-local.sh references npm script(s) not in implementation/package.json: ${unknown.join(', ')}`,
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
  console.error(`rigorous-local-inventory tests: ${failed} failed.`);
  process.exit(1);
}

console.log(`rigorous-local-inventory tests: ${tests.length} passed.`);
