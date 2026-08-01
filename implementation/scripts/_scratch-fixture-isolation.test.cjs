#!/usr/bin/env node

'use strict';

/*
 * Scratch-fixture isolation gate (rf2-2i1ay).
 *
 * THE DEFECT THIS PINS. Four script self-tests materialised fixtures in a
 * unique `mkdtemp` dir under the repo's gitignored `.scratch/`, then tore
 * down with `fs.rmSync(SCRATCH_ROOT, …)` — deleting the SHARED ROOT rather
 * than their own dir. Run concurrently in one checkout they failed 20/20;
 * each passes standalone. Worse than the flake itself is how it reads: the
 * script under test reports `expected source file … not found` or `cd:
 * .scratch/…: No such file or directory`, so a deleted fixture is
 * indistinguishable at a glance from a real defect in the diff.
 *
 * TWO ARMS, deliberately.
 *
 *   1. A BEHAVIOURAL probe — the load-bearing one. A child process creates
 *      its own lane and runs the real teardown; the parent asserts its own
 *      sentinel lane SURVIVED. This exercises `scratch-fixtures.cjs` rather
 *      than describing it, so it fails on the pre-fix teardown regardless of
 *      how that teardown is spelled.
 *
 *   2. A STATIC scan, because the behavioural probe only covers callers that
 *      route through the helper. A suite that re-rolls its own whole-root
 *      `rmSync` would reintroduce the defect while the probe stayed green.
 *      The scan reads EXECUTABLE source only (comments stripped), so this
 *      file's own prose — which necessarily quotes the forbidden form — is
 *      not a false positive.
 */

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');
const {
  SCRATCH_DIRNAME,
  scratchRoot,
  makeScratchDir,
  cleanupScratchDirs,
} = require('./lib/scratch-fixtures.cjs');

const SCRIPTS_DIR = __dirname;
const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');

const { test, run } = createPolicyTestSuite('scratch-fixture-isolation');

// ── 1. Behavioural: teardown is scoped to the calling process ────────────

test("a sibling process's teardown does not remove this process's lane (rf2-2i1ay)", () => {
  const sentinel = makeScratchDir(REPO_ROOT, 'rf2-isolation-sentinel');
  const marker = path.join(sentinel, 'marker.txt');
  fs.writeFileSync(marker, 'survives');
  try {
    // A separate process does exactly what a concurrent suite does: take a
    // lane, then tear down. `require` is resolved against this dir so the
    // child loads the same helper.
    const child = [
      `const h = require(${JSON.stringify(path.join(SCRIPTS_DIR, 'lib', 'scratch-fixtures.cjs'))});`,
      `const d = h.makeScratchDir(${JSON.stringify(REPO_ROOT)}, 'rf2-isolation-neighbour');`,
      'require("fs").writeFileSync(require("path").join(d, "x.txt"), "x");',
      'h.cleanupScratchDirs();',
      'process.stdout.write(d);',
    ].join('\n');
    const neighbourLane = execFileSync(process.execPath, ['-e', child], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    }).trim();

    // The neighbour cleaned up after itself …
    assert.equal(
      fs.existsSync(neighbourLane),
      false,
      'the neighbour process must remove its OWN lane',
    );
    // … and left this process's fixture untouched. This is the assertion
    // that fails on the pre-fix whole-root `rmSync`.
    assert.equal(
      fs.existsSync(marker),
      true,
      "a concurrent suite's teardown must not delete this process's fixtures — "
        + 'teardown must be scoped to the lanes its own process created (rf2-2i1ay)',
    );
    // The shared root is infrastructure, not a resource any process owns.
    assert.equal(
      fs.existsSync(scratchRoot(REPO_ROOT)),
      true,
      'the shared scratch root must survive a teardown',
    );
  } finally {
    cleanupScratchDirs();
  }
});

test('cleanupScratchDirs removes the lanes it created and is safe to repeat (rf2-2i1ay)', () => {
  const a = makeScratchDir(REPO_ROOT, 'rf2-isolation-a');
  const b = makeScratchDir(REPO_ROOT, 'rf2-isolation-b');
  assert.notEqual(a, b, 'two lanes of one process must be distinct');
  cleanupScratchDirs();
  assert.equal(fs.existsSync(a), false, 'lane a must be removed');
  assert.equal(fs.existsSync(b), false, 'lane b must be removed');
  // Idempotent: a `finally` may call teardown after an early cleanup.
  cleanupScratchDirs();
});

// ── 2. Static: no suite may re-roll a whole-root removal ─────────────────

// Recursive removal whose target names the scratch root rather than a lane:
// `rmSync(SCRATCH_ROOT`, `rmSync(scratchRoot(...)`, or a literal
// `rmSync(path.join(X, '.scratch')`. Lane-scoped removal (`rmSync(dir`) is
// exactly what the helper does and is not matched.
const WHOLE_ROOT_REMOVAL_RE = new RegExp(
  String.raw`rm(?:Sync|dirSync)?\s*\(\s*(?:SCRATCH_ROOT\b|scratchRoot\s*\(|[^)]*['"\`]\.scratch['"\`])`,
);

function firstPartyScripts() {
  return fs
    .readdirSync(SCRIPTS_DIR)
    .filter((f) => f.endsWith('.cjs'))
    .map((f) => path.join(SCRIPTS_DIR, f))
    .concat(
      fs
        .readdirSync(path.join(SCRIPTS_DIR, 'lib'))
        .filter((f) => f.endsWith('.cjs'))
        .map((f) => path.join(SCRIPTS_DIR, 'lib', f)),
    );
}

test('no first-party script recursively removes the shared scratch root (rf2-2i1ay)', () => {
  const offenders = [];
  for (const file of firstPartyScripts()) {
    const src = stripComments(fs.readFileSync(file, 'utf8'));
    if (WHOLE_ROOT_REMOVAL_RE.test(src)) {
      offenders.push(path.relative(REPO_ROOT, file));
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `these scripts remove the shared \`${SCRATCH_DIRNAME}/\` root rather than their own lane, `
      + 'which deletes a concurrent suite\'s live fixtures: '
      + `${offenders.join(', ')}. Take a lane with makeScratchDir() and tear down with `
      + 'cleanupScratchDirs() (implementation/scripts/lib/scratch-fixtures.cjs, rf2-2i1ay).',
  );
});

test(`every ${SCRATCH_DIRNAME}/ fixture dir is taken from the shared helper (rf2-2i1ay)`, () => {
  // A direct `mkdtempSync` under the scratch root bypasses the ownership
  // registry, so the process's own teardown cannot find that lane and it
  // leaks — or invites the whole-root removal that caused this bead.
  const directMkdtemp = /mkdtempSync\s*\([^)]*(?:SCRATCH_ROOT\b|['"`]\.scratch['"`])/;
  const offenders = [];
  for (const file of firstPartyScripts()) {
    if (path.basename(file) === 'scratch-fixtures.cjs') continue; // the helper itself
    const src = stripComments(fs.readFileSync(file, 'utf8'));
    if (directMkdtemp.test(src)) offenders.push(path.relative(REPO_ROOT, file));
  }
  assert.deepEqual(
    offenders,
    [],
    `these scripts create a \`${SCRATCH_DIRNAME}/\` fixture dir directly instead of via `
      + `makeScratchDir(): ${offenders.join(', ')} (rf2-2i1ay).`,
  );
});

test('the four historical callers still route through the helper (rf2-2i1ay)', () => {
  // Sanity for the two scans above: if these stop requiring the helper the
  // scans go vacuously green while the suites drift back to private roots.
  const callers = [
    '_transform-reagent-slim-ns.test.cjs',
    '_preflight-story-package.test.cjs',
    '_preflight-reagent-slim-package.test.cjs',
    '_rewrite-local-root-coord.test.cjs',
  ];
  for (const caller of callers) {
    const src = stripComments(fs.readFileSync(path.join(SCRIPTS_DIR, caller), 'utf8'));
    assert.match(
      src,
      /require\(['"]\.\/lib\/scratch-fixtures\.cjs['"]\)/,
      `${caller} must take its fixture lanes from lib/scratch-fixtures.cjs (rf2-2i1ay)`,
    );
  }
});

run();
