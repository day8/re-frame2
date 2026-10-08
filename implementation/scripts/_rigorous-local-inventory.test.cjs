#!/usr/bin/env node
/*
 * Lockstep guard for `scripts/test-rigorous-local.sh`, the local mirror of the
 * `browser-bundle-and-story-gates` sweep in `.github/workflows/expensive-tests.yml`:
 * a developer running it before a release-sized change must get every gate that
 * sweep runs. It mirrors that sweep only — a command belongs in the script when it
 * yields a verdict and no PR run gates it — so it is not an inventory of
 * package.json.
 *
 * Each sweep command must also be the whole body of its own named step: a shared
 * `run: |` block aborts at the first failing gate and records one step result, so
 * a red night would hide every gate broken behind the first.
 *
 * Full-line `#` comments are stripped from both files first, so prose mentioning a
 * command can neither satisfy lockstep nor trip the one-command-per-step rule.
 * Discovered by `npm run test:scripts`.
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

// Every `npm run <script>` name in a blob of shell text.
function npmRunScripts(text) {
  const out = new Set();
  const re = /npm run ([A-Za-z0-9:._-]+)/g;
  let m;
  while ((m = re.exec(text)) !== null) {
    out.add(m[1]);
  }
  return out;
}

function stripComments(text) {
  return text
    .split('\n')
    .filter((line) => !/^\s*#/.test(line))
    .join('\n');
}

const scriptText = fs.readFileSync(RIGOROUS_SCRIPT, 'utf8');
const workflowText = fs.readFileSync(EXPENSIVE_WORKFLOW, 'utf8');
const pkg = JSON.parse(fs.readFileSync(PACKAGE_JSON, 'utf8'));

const localScripts = npmRunScripts(stripComments(scriptText));

// Scoped to the gates job, not the other jobs in the same workflow.
const GATES_JOB = 'browser-bundle-and-story-gates';

function gatesJobBody(text) {
  const headerRe = new RegExp(`\\n {2}${GATES_JOB}:\\r?\\n`);
  const m = headerRe.exec(text);
  assert.notEqual(
    m,
    null,
    `expensive-tests.yml must carry the \`${GATES_JOB}\` job`,
  );
  const rest = text.slice(m.index + m[0].length);
  const next = rest.search(/\n {2}[A-Za-z0-9_-]+:\s*\r?\n/);
  return next === -1 ? rest : rest.slice(0, next);
}

const gatesJobText = stripComments(gatesJobBody(workflowText));
const sweepScripts = npmRunScripts(gatesJobText);

// Split the job body into step chunks on the 6-space `- ` step bullet.
function jobSteps(jobText) {
  return jobText.split(/\n {6}- /).slice(1);
}

test('expensive-tests.yml sweep is non-empty (parse sanity) (rf2-lm5mu9)', () => {
  assert.ok(
    sweepScripts.size >= 8,
    `expected the rigorous sweep to list many npm scripts, parsed ${sweepScripts.size}`,
  );
});

test('every nightly sweep command runs in its own named step (rf2-wh5to)', () => {
  const offenders = [];
  for (const step of jobSteps(gatesJobText)) {
    const commands = [...step.matchAll(/npm run ([A-Za-z0-9:._-]+)/g)].map((m) => m[1]);
    if (commands.length === 0) continue;
    const named = /(^|\n\s*)name:\s*\S/.test(step);
    if (commands.length > 1 || !named) {
      offenders.push(`${named ? '' : '(unnamed) '}${commands.join(' + ')}`);
    }
  }
  assert.deepEqual(
    offenders,
    [],
    'each nightly gate must be the whole body of its OWN named step, so a red night '
      + 'names the failing gate and reports EVERY broken gate instead of aborting the '
      + `chain at the first one. Offending step(s): ${offenders.join('; ')}`,
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

// Pinned directly as well as through lockstep, so the local script keeps them if
// the workflow moves them out of the gates job.
const DIRECT_PINS = ['test:examples-compile', 'test:story-play-scripts'];

test('local rigorous script runs the directly pinned gates (rf2-lm5mu9, rf2-rmtj0)', () => {
  const missing = DIRECT_PINS.filter((required) => !localScripts.has(required));
  assert.deepEqual(missing, [], `scripts/test-rigorous-local.sh must run: ${missing.join(', ')}`);
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
