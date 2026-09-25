#!/usr/bin/env node
/*
 * Inventory/lockstep guard for `scripts/test-rigorous-local.sh`.
 *
 * The rigorous local script is the local mirror of the rigorous
 * browser-bundle-and-story sweep in `.github/workflows/expensive-tests.yml`.
 * If it drifts behind that sweep — omitting, say,
 * `test:story-play-scripts` (a sweep command) or `test:examples-compile`
 * (the example-build compile gate that test.yml runs in its own parallel
 * cljs-examples-compile job) — a
 * developer running `scripts/test-rigorous-local.sh`
 * before a release-sized change gets `PASS rigorous local suite` without
 * the example-compile coverage gate or the Story play-script browser gate —
 * exactly the wiring regressions those gates exist to catch.
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
 *     pin, independent of the parse above, so none can silently drop out.
 *     Assertion (1) can only ever see commands that live in that one workflow
 *     job; a gate whose scheduled home is elsewhere is invisible to it.
 *  3. Each pin's declared BASIS still matches the workflow — see
 *     `DIRECT_PINS`. A pin's reason is a claim about where the command is
 *     scheduled, and that claim rots when the workflow moves.
 *  4. Every command the local script invokes is a real `package.json` script
 *     (catches a typo'd or renamed-away gate).
 *  5. Each of those nightly commands runs in its OWN named step.
 *
 * SCOPE, STATED SO THE BLIND SPOT IS INTENTIONAL RATHER THAN ACCIDENTAL.
 * This guard mirrors the EXPENSIVE sweep and nothing else. It is
 * not an inventory of `implementation/package.json`, and deliberately does
 * not derive one.
 *
 * A command belongs in `scripts/test-rigorous-local.sh` when BOTH hold:
 *
 *   (a) it yields a VERDICT — a non-zero exit means something is broken — as
 *       opposed to producing a build artefact or publishing a record; and
 *   (b) no PR run gates it, because its only scheduled home is a nightly or
 *       workflow_dispatch lane.
 *
 * Those two conjuncts DERIVE the script's contents rather than describing them
 * after the fact, and they are why the implementation gates outside this guard's
 * window are correctly outside the script too. The `test.yml`-only ones
 * fail (b): that workflow is surface-classified, so the change that would break
 * such a gate is the change that queues it, and a PR cannot land past it in
 * silence. What the conjuncts teach is the DERIVATION rather than any list of
 * commands: a build step that feeds a benchmark workflow fails (a) — it
 * produces an artefact, not a verdict. A suite a nightly lane runs to CAPTURE
 * records fails (b) when its assertions already gate every PR through the
 * `:node-test` build, which `test:cljs` runs — test.yml calls that "the cheap
 * NAMED surface for local + worker iteration, not a second gate".
 * (`test:cljs` and `test:scripts` reach here transitively anyway, via
 * `test-fast-pr.sh`, which the rigorous script runs first.)
 *
 * The `DIRECT_PINS` map below is the authority on what is pinned; a list of
 * command names in this comment would be a second authority with nothing
 * holding it in step with the workflows.
 *
 * A package.json census would ALSO miss the likeliest drift. Moving test
 * CONTENT between builds that already exist changes no script name, so a
 * name-level census stays green while the local sweep's coverage narrows. The
 * gate that sees that class is `_browser-dom-lane-partition.test.cjs`, which
 * derives the DOM lane's `:ns-regexp` from `shadow-cljs.edn` and proves it
 * selects every `*_dom_cljs_test` namespace in the repo. Content-level
 * derivation is the answer to content-level drift; a name-level census is not.
 *
 * A command earns a `DIRECT_PINS` entry on top of that when it must also declare
 * a `kind` — and that declaration is CHECKED against the workflow rather than
 * believed. See the map.
 *
 * On (5): if the browser/bundle/Story/Xray gates shared one unnamed `run: |`
 * block, a `set -e` chain would abort at the first failing gate, so a red
 * night could only ever reveal ONE broken gate, and a gate broken behind
 * another would stay invisible until the first was repaired. The blob would
 * also be a single step-level result, so neither the Actions UI nor the jobs
 * API would record WHICH gate had failed. One command per named step is
 * therefore load-bearing, not cosmetic, and is pinned here so a tidy-up
 * cannot silently introduce the masking shape.
 *
 * Comment lines are stripped from BOTH files before any parse — the workflow's
 * `#` YAML comments and the shell script's `#` comments alike — so prose
 * mentioning a command can neither satisfy lockstep nor a direct pin, nor trip
 * the one-command-per-step rule.
 *
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

// Commands pinned DIRECTLY: the local script must run them whether or not the
// lockstep parse above can require them. Each closes a real hole.
//
// `kind` is the pin's BASIS, and it is a CHECKED claim rather than
// prose, because a pin's reason is a statement about where a command is
// scheduled and workflows move:
//
//   'parse-blind'      the command's scheduled home is outside the
//                      `browser-bundle-and-story-gates` job, so assertion (1)
//                      can never require it. Delete the pin and the command has
//                      no guard at all.
//   'belt-and-braces'  assertion (1) does require it — but only for as long as
//                      the workflow keeps it there. The pin makes the local
//                      script's coverage independent of that.
//
// The rot this catches: a pin whose reason reads "never in the nightly sweep"
// goes false the day the workflow adds the command to the gates job, and
// nothing else says so. Assertion (3) goes red that day.
const DIRECT_PINS = {
  'test:examples-compile': {
    kind: 'belt-and-braces',
    why:
      'the example-build compile gate. test.yml queues it only when the surface '
      + 'classifier says so — a core-only PR skips it — which is why it is also '
      + 'an unconditional step of the nightly sweep. This '
      + 'pin keeps the local mirror running it even if that step '
      + 'moves',
  },
  'test:story-play-scripts': {
    kind: 'belt-and-braces',
    why:
      'the Story play-script browser gate, a sweep command the local mirror must '
      + 'not silently drop',
  },
};

const PIN_KINDS = new Set(['parse-blind', 'belt-and-braces']);

test('local rigorous script pins the gates the workflow parse cannot see (rf2-lm5mu9, rf2-rmtj0)', () => {
  for (const [required, { why }] of Object.entries(DIRECT_PINS)) {
    assert.ok(
      localScripts.has(required),
      `scripts/test-rigorous-local.sh must run \`npm run ${required}\` — ${why}.`,
    );
  }
});

// Kept SEPARATE from the presence assertion above on purpose: a stale basis must
// never mask a real coverage hole, nor be masked by one.
test('every DIRECT_PINS basis still matches the workflow (rf2-0l1nv)', () => {
  const stale = [];
  for (const [cmd, { kind }] of Object.entries(DIRECT_PINS)) {
    if (!PIN_KINDS.has(kind)) {
      stale.push(
        `${cmd}: unknown kind \`${kind}\` — a pin must declare one of `
          + `${[...PIN_KINDS].join(' / ')}`,
      );
      continue;
    }
    const inSweep = sweepScripts.has(cmd);
    if (kind === 'parse-blind' && inSweep) {
      stale.push(
        `${cmd}: pinned \`parse-blind\`, but the ${GATES_JOB} job now runs it, so `
          + 'lockstep already requires it. Re-declare it `belt-and-braces` and '
          + 'rewrite its `why` — the current one says the parse cannot see this '
          + 'command, and that is no longer true',
      );
    }
    if (kind === 'belt-and-braces' && !inSweep) {
      stale.push(
        `${cmd}: pinned \`belt-and-braces\`, but the ${GATES_JOB} job no longer runs `
          + 'it, so this pin is now the ONLY thing keeping it in the local sweep. '
          + 'Re-declare it `parse-blind` and rewrite its `why`',
      );
    }
  }
  assert.deepEqual(
    stale,
    [],
    'a DIRECT_PINS entry states WHERE its command is scheduled, and workflows move: '
      + 'a basis that goes false does so '
      + 'with nothing else to say so. '
      + `Stale basis: ${stale.join('; ')}`,
  );
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
