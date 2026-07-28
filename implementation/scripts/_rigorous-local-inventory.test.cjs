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
 *  1. Every `npm run test:*` command in the expensive-tests.yml
 *     `browser-bundle-and-story-gates` job also runs in the local script
 *     (lockstep — the local mirror must not drift BEHIND the nightly sweep
 *     for implementation browser/bundle commands).
 *  2. The `DIRECT_PINS` commands are present in the local script — a direct
 *     pin, independent of the parse above, so none can silently drop out again.
 *     Assertion (1) can only ever see commands that live in that one workflow
 *     job; a gate whose scheduled home is elsewhere is invisible to it, which is
 *     precisely how `bench:freehand-browser` went missing (rf2-rmtj0).
 *  3. Every command the local script invokes is a real `package.json` script
 *     (catches a typo'd or renamed-away gate).
 *  4. Each of those nightly commands runs in its OWN named step (rf2-wh5to).
 *
 * On (4): the eleven browser/bundle/Story/Xray gates used to be one unnamed
 * `run: |` block. A `set -e` chain aborts at the first failing gate, so a red
 * night could only ever reveal ONE broken gate — which is why the
 * 2026-07-08..07-21 nightly outage took two repair rounds and fourteen red
 * nights: `test:story-static` broke on 07-13 but stayed invisible behind
 * `test:story-feature-load` until 07-21. The blob was also a single step-level
 * result, so neither the Actions UI nor the jobs API recorded WHICH gate had
 * failed. One command per named step is therefore load-bearing, not cosmetic,
 * and is pinned here so a later tidy-up cannot silently reintroduce the
 * masking shape.
 *
 * Comment lines are stripped from BOTH files before any parse — the workflow's
 * `#` YAML comments and the shell script's `#` comments alike — so prose
 * mentioning a command can neither satisfy lockstep nor a direct pin, nor trip
 * the one-command-per-step rule.
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

// Drop full-line `#` comments — YAML in the workflow, shell in the local
// script. Prose in a comment must not be able to satisfy lockstep or a direct
// pin, nor to look like a second command inside a gate step.
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

// Narrow the workflow to the `browser-bundle-and-story-gates` job body: from
// its 2-space-indented job key to the next job key at the same indent. Scopes
// the parse so we only compare against the implementation browser/bundle
// sweep, not the template / mcp-live / jvm-slow-tests jobs in the same file.
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
      + `chain at the first one (rf2-wh5to). Offending step(s): ${offenders.join('; ')}`,
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

// Commands pinned DIRECTLY, independent of the lockstep parse above. That parse
// can only ever see the `browser-bundle-and-story-gates` job, so a gate whose
// scheduled home is another job — or another workflow entirely — is invisible to
// it and can drop out of the local script in silence. Each entry below is
// exactly that case, and each cost a real hole before it was pinned.
const DIRECT_PINS = {
  'test:examples-compile':
    'the example-build compile gate. test.yml runs it in its own '
    + 'cljs-examples-compile job, never in the nightly sweep (rf2-lm5mu9)',
  'test:story-play-scripts':
    'the Story play-script browser gate, a sweep command the local mirror had '
    + 'silently dropped (rf2-lm5mu9)',
  'bench:freehand-browser':
    'the `:browser-test-freehand-bench` build. rf2-mf4uy moved the seven '
    + '`re-frame.freehand.bench.*` DOM namespaces out of `:browser-test`, and '
    + 'their only scheduled home is freehand-bench.yml — which this file never '
    + 'reads. Without this pin the local sweep loses 30 mounted-correctness '
    + 'tests and nothing goes red (rf2-rmtj0)',
};

test('local rigorous script pins the gates the workflow parse cannot see (rf2-lm5mu9, rf2-rmtj0)', () => {
  for (const [required, why] of Object.entries(DIRECT_PINS)) {
    assert.ok(
      localScripts.has(required),
      `scripts/test-rigorous-local.sh must run \`npm run ${required}\` — ${why}.`,
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
