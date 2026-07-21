#!/usr/bin/env node
/*
 * Gate proof for rf2-vxgfnd.99.2 — day8/re-frame2-ui publication is a BLOCKING
 * pre-tag S7 gate.
 *
 * The ratified packaging decision (spec/006 §R-6) requires core and ui to ship
 * TOGETHER on the lockstep alpha release train. `.github/scripts/verify-version-
 * lockstep.sh` carries a REQUIRED_PUBLISHABLE positive assertion: every required
 * artefact (today: ui) MUST be publishable, so a core-only alpha tag — one where
 * ui was reverted to its pre-publication shape (no :clein/build) — FAILS the
 * release gate before any deploy. The generic inventory guard alone cannot close
 * this hole: it is conditional ("IF a deps.edn declares :clein/build THEN …") and
 * therefore falls silent the instant ui's descriptor is dropped. That silent
 * revert is precisely the rf2-pc479y drift this gate exists to prevent.
 *
 * This suite proves the gate has teeth WITHOUT depending on the whole repo tree.
 * It copies the SHIPPED script + the publishable-runtimes authority into a
 * throwaway fixture whose only variable is ui's publishability, and shows that:
 *
 *   - the REAL, shipped repo is green (exit 0) with ui publishable — the gate is
 *     live and not spuriously red (positive end-to-end control);
 *   - a fixture where ui declares :clein/build does NOT raise the REQUIRED error;
 *   - flipping ONLY that — ui reverted to no :clein/build (the core-only shape) —
 *     makes the same shipped script exit non-zero AND emit the REQUIRED error
 *     naming day8/re-frame2-ui. That is the "core-only alpha tag fails
 *     pre-deploy" acceptance criterion, proven by construction.
 *
 * Copying the shipped script (rather than restating its logic) means the proof
 * cannot drift from the gate. Standalone node-runnable, matching the sibling
 * _*.test.cjs convention (spawns `bash`, as _rewrite-local-root-coord.test.cjs
 * does — CI's ubuntu runner and local Git Bash both provide it).
 */

'use strict';

const assert = require('assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const { pathDeclaresBuildAlias } = require('./lib/publishable-runtimes.cjs');

const REPO_ROOT = path.resolve(__dirname, '..', '..');
const SCRIPT_REL = '.github/scripts/verify-version-lockstep.sh';
const AUTHORITY_REL = 'implementation/scripts/lib/publishable-runtimes.cjs';
const EDN_REL = 'implementation/scripts/lib/edn.cjs';

// Substrings that identify the REQUIRED_PUBLISHABLE assertion's error line. Kept
// loose enough to survive prose tweaks but specific enough that only that line
// matches.
const REQUIRED_ERR = 'REQUIRED artefact of the alpha release train';
const UI_COORD = 'day8/re-frame2-ui';

// A minimal ui/deps.edn WITH its publication descriptor. The publishable
// authority keys off the real :aliases/:clein/build KEY, so this is publishable.
const UI_DEPS_PUBLISHABLE = `{:paths ["src"]
 :deps {day8/re-frame2 {:local/root "../core"}}
 :aliases
 {:clein/build
  {:lib      day8/re-frame2-ui
   :main     re-frame.ui
   :version  "../../VERSION"
   :src-dirs ["src"]}}}
`;

// The SAME artefact reverted to its pre-publication shape — no :clein/build.
// This is the "core-only tag" shape the gate must refuse.
const UI_DEPS_PREPUBLICATION = `{:paths ["src"]
 :deps {day8/re-frame2 {:local/root "../core"}}}
`;

function runShippedScriptAt(repoRoot) {
  // The script resolves its own REPO_ROOT from BASH_SOURCE (dirname/../..), so
  // running the COPY located under <repoRoot>/.github/scripts targets <repoRoot>.
  // NB: a NON-login shell (`-c`, not `-lc`) that inherits process.env — the
  // shipped script shells out to `node` (the publishable-runtimes authority),
  // and a login shell can re-source profiles that drop the CI toolcache `node`
  // from PATH. `-c` inherits the parent env verbatim, so `node` resolves the
  // same way it does when the verify-version-lockstep CI job runs the script.
  return spawnSync('bash', ['-c', `./${SCRIPT_REL}`], {
    cwd: repoRoot,
    env: process.env,
    encoding: 'utf8',
  });
}

function combinedOutput(res) {
  return `${res.stdout || ''}${res.stderr || ''}`;
}

// Build a throwaway fixture repo containing exactly what the shipped script
// reads, with ui/deps.edn set to `uiDeps`. Returns the fixture root.
function buildFixture(uiDeps) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-ui-gate-'));
  const mk = (rel) => fs.mkdirSync(path.join(root, rel), { recursive: true });
  const cp = (rel) => fs.copyFileSync(path.join(REPO_ROOT, rel), path.join(root, rel));
  const write = (rel, body) => fs.writeFileSync(path.join(root, rel), body);

  mk('.github/scripts');
  mk('implementation/scripts/lib');
  mk('implementation/core');
  mk('implementation/ui');

  // The shipped script + the authority it invokes — copied, never restated.
  cp(SCRIPT_REL);
  cp(AUTHORITY_REL);
  cp(EDN_REL);
  cp('VERSION');
  // Real core deps.edn: a valid, publishable lockstep root so the authority
  // returns a non-empty inventory and core's own checks pass.
  cp('implementation/core/deps.edn');
  // The one variable under test.
  write('implementation/ui/deps.edn', uiDeps);

  return root;
}

const tests = [];
function test(name, thunk) {
  tests.push([name, thunk]);
}

// --- Positive end-to-end: the real shipped repo is green with ui publishable ---
test('real repo — ui declares :clein/build (publishable at source)', () => {
  assert.equal(
    pathDeclaresBuildAlias(path.join(REPO_ROOT, 'implementation'), 'ui'),
    true,
    'implementation/ui/deps.edn must declare a real :clein/build alias (the gate depends on it)',
  );
});

test('real repo — verify-version-lockstep.sh is GREEN (exit 0), no REQUIRED error', () => {
  const res = runShippedScriptAt(REPO_ROOT);
  const out = combinedOutput(res);
  assert.equal(res.status, 0, `lockstep gate must pass on the shipped repo.\n${out}`);
  assert.ok(/PASSED/.test(out), 'green run reports PASSED');
  assert.ok(!out.includes(REQUIRED_ERR), 'no REQUIRED error when ui is publishable');
});

// --- Fixture control: ui publishable → REQUIRED assertion is satisfied ---------
test('fixture — ui publishable: the REQUIRED-ui error is ABSENT', () => {
  const root = buildFixture(UI_DEPS_PUBLISHABLE);
  try {
    const out = combinedOutput(runShippedScriptAt(root));
    assert.ok(
      !out.includes(REQUIRED_ERR),
      `with ui publishable the REQUIRED assertion must not fire.\n${out}`,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

// --- The core-only-tag-fails proof: flip ONLY ui's publishability -------------
test('fixture — ui reverted to pre-publication (core-only tag): gate FAILS with the REQUIRED-ui error', () => {
  const root = buildFixture(UI_DEPS_PREPUBLICATION);
  try {
    const res = runShippedScriptAt(root);
    const out = combinedOutput(res);
    assert.notEqual(res.status, 0, `a core-only tag must FAIL the release gate.\n${out}`);
    assert.ok(out.includes(REQUIRED_ERR), `gate must emit the REQUIRED error.\n${out}`);
    assert.ok(
      out.includes(UI_COORD),
      `the REQUIRED error must name ${UI_COORD}.\n${out}`,
    );
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

let failed = 0;
for (const [name, thunk] of tests) {
  try {
    thunk();
    console.log(`ok — ${name}`);
  } catch (err) {
    failed += 1;
    console.error(`FAIL ${name}`);
    console.error(err && err.stack ? err.stack : err);
  }
}

if (failed > 0) {
  console.error(`release-ui-required-gate tests: ${failed} failed.`);
  process.exit(1);
}
console.log(`release-ui-required-gate tests: ${tests.length} passed.`);
