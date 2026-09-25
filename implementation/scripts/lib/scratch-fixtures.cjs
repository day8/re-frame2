#!/usr/bin/env node

'use strict';

/*
 * Per-process scratch fixture lanes for the script self-tests.
 *
 * WHY THIS EXISTS. Several `_*.test.cjs` suites materialise throwaway fixtures
 * under the repo's gitignored `.scratch/` — those that hand the fixture path
 * to `bash` cannot use `os.tmpdir()`, because they pass it as a path RELATIVE to a cwd
 * of REPO_ROOT, and an absolute Windows drive path is not resolvable across
 * Git Bash / WSL bash / Linux bash alike. Living inside the
 * repo is therefore a hard requirement, not a preference.
 *
 * WHY LANES. A suite that creates a unique `mkdtemp` dir under `.scratch/`
 * but tears down with
 *
 *     fs.rmSync(SCRATCH_ROOT, { recursive: true, force: true });
 *
 * deletes the SHARED ROOT, not the dir that suite created. Any second
 * process using `.scratch/` in the same checkout would have its live fixture
 * pulled out from under it mid-run, while every suite still passes
 * standalone. The
 * downstream symptom is a confusing lie — the shell script under test reports
 * `expected source file … not found` or `cd: .scratch/…: No such file or
 * directory`, so the failure reads as a defect in the script or the diff
 * rather than as a fixture that was deleted by a neighbour.
 *
 * THE SHAPE. Isolation, not tolerance: no retry, no sleep, no
 * lock. Each PROCESS gets its own lane under `.scratch/`, keyed on pid plus
 * random suffix, and teardown removes only the lanes THIS process created.
 * The shared root is created but never removed, so it is not a resource any
 * process can take from another.
 *
 * A plain helper module (not a `.test.cjs`), so the `test:script-*` runners
 * do not execute it as a suite. `_scratch-fixture-isolation.test.cjs` pins
 * the contract and forbids whole-root removal.
 */

const fs = require('fs');
const path = require('path');

// The gitignored in-repo scratch root. Every lane lives under it; it is
// created on demand and deliberately NEVER removed — see the header.
const SCRATCH_DIRNAME = '.scratch';

// Lanes created by THIS process, in creation order. Teardown drains this
// set; nothing else is ever a removal target.
const owned = new Set();

function scratchRoot(repoRoot) {
  return path.join(repoRoot, SCRATCH_DIRNAME);
}

// Create a fresh, process-scoped fixture lane under `<repoRoot>/.scratch/`
// and return its absolute path. `prefix` names the owning suite (e.g.
// `rf2-slim-ns`); the pid segment makes a concurrent sibling's lane
// visibly distinguishable when one is left behind by a killed run, and
// `mkdtemp`'s random suffix keeps two lanes of the SAME process distinct.
function makeScratchDir(repoRoot, prefix) {
  const root = scratchRoot(repoRoot);
  fs.mkdirSync(root, { recursive: true });
  const dir = fs.mkdtempSync(path.join(root, `${prefix}-${process.pid}-`));
  owned.add(dir);
  return dir;
}

// Remove every lane this process created — and nothing else. Safe to call
// repeatedly and from a `finally`; a lane already gone is not an error.
function cleanupScratchDirs() {
  for (const dir of owned) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
  owned.clear();
}

// Safety net for an aborted run (a thrown assertion escaping a `finally`,
// or a suite that exits early): drop this process's lanes on the way out so
// an interrupted run does not accumulate litter. Bounded to `owned`, so it
// can never reach a concurrent sibling's lane.
process.on('exit', () => {
  try {
    cleanupScratchDirs();
  } catch {
    // Teardown must never mask a real failure's exit code.
  }
});

module.exports = {
  SCRATCH_DIRNAME,
  scratchRoot,
  makeScratchDir,
  cleanupScratchDirs,
};
