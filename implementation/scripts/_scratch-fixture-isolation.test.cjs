#!/usr/bin/env node

'use strict';

/*
 * Script self-tests take fixture lanes under the shared, gitignored `.scratch/`
 * root; a teardown that removes the ROOT instead of its own lane deletes a
 * concurrent suite's live fixtures, which then reads like a real defect. A
 * behavioural probe runs the real helper's teardown in a child process; a static
 * scan catches suites that bypass the helper.
 */

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const { stripComments, createPolicyTestSuite } = require('./_policy-test-util.cjs');
const {
  SCRATCH_DIRNAME,
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
    const child = [
      `const h = require(${JSON.stringify(path.join(SCRIPTS_DIR, 'lib', 'scratch-fixtures.cjs'))});`,
      `const d = h.makeScratchDir(${JSON.stringify(REPO_ROOT)}, 'rf2-isolation-neighbour');`,
      'require("fs").writeFileSync(require("path").join(d, "x.txt"), "x");',
      'h.cleanupScratchDirs();',
    ].join('\n');
    execFileSync(process.execPath, ['-e', child], { cwd: REPO_ROOT });

    // Fails on a whole-root removal.
    assert.equal(
      fs.existsSync(marker),
      true,
      "a concurrent suite's teardown must not delete this process's fixtures",
    );
  } finally {
    cleanupScratchDirs();
  }
});

test('cleanupScratchDirs removes the lanes it created and is safe to repeat (rf2-2i1ay)', () => {
  const a = makeScratchDir(REPO_ROOT, 'rf2-isolation-a');
  const b = makeScratchDir(REPO_ROOT, 'rf2-isolation-b');
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
      + 'cleanupScratchDirs() (implementation/scripts/lib/scratch-fixtures.cjs).',
  );
});

test(`every ${SCRATCH_DIRNAME}/ fixture dir is taken from the shared helper (rf2-2i1ay)`, () => {
  // A direct `mkdtempSync` under the scratch root bypasses the ownership
  // registry, so the process's own teardown cannot find that lane and it
  // leaks — or invites a whole-root removal.
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
      + `makeScratchDir(): ${offenders.join(', ')}.`,
  );
});

test('the four historical callers still route through the helper (rf2-2i1ay)', () => {
  // Sanity for the two scans above: if these stop requiring the helper the
  // scans go vacuously green while the suites drift to private roots.
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
      `${caller} must take its fixture lanes from lib/scratch-fixtures.cjs`,
    );
  }
});

run();
