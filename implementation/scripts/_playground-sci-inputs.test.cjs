#!/usr/bin/env node

'use strict';

/**
 * _playground-sci-inputs.test.cjs — close the Playground SCI input authority
 * over its REAL inputs and firing surfaces.
 *
 * `scripts/playground-sci-input-digest.mjs` declares ROSTER: the
 * source/config/lock inputs the shadow-cljs :advanced SCI bundle bakes in. Two
 * things must stay true of it:
 *
 *   1. CLOSURE. Every declared input selects the `playground` changed surface,
 *      so a PR touching a baked-in input rebuilds and renders the bundle.
 *   2. SENSITIVITY. Every declared input individually moves the digest, and an
 *      entry that matches no tracked file REDS rather than passing silently.
 *
 * docs/cljs/playground-rf2.js is untracked and generated at each consumption
 * boundary, so the digest is build PROVENANCE and the `playground` job is the
 * proof; there is no committed artefact to compare. Every arm expands the REAL
 * ROSTER rather than a second hardcoded list. Discovered by `npm run test:scripts`.
 */

const assert = require('assert/strict');
const { execFileSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { pathToFileURL } = require('url');

const IMPL_ROOT = path.resolve(__dirname, '..');
const REPO_ROOT = path.resolve(IMPL_ROOT, '..');
const DIGEST_SCRIPT = path.join(REPO_ROOT, 'scripts', 'playground-sci-input-digest.mjs');
const SURFACES_SCRIPT = './.github/scripts/report-changed-surfaces.sh';

const tests = [];

function test(name, fn) {
  tests.push({ name, fn });
}

function cleanEnv() {
  const env = { ...process.env };
  delete env.GITHUB_OUTPUT;
  delete env.GITHUB_EVENT_NAME;
  delete env.GITHUB_BASE_REF;
  for (const key of Object.keys(env)) {
    if (key.startsWith('GIT_')) delete env[key];
  }
  return env;
}

function trackedFiles(pathspec, cwd = REPO_ROOT) {
  return execFileSync('git', ['ls-files', '-z', '--', pathspec], {
    cwd,
    env: cleanEnv(),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  })
    .split('\0')
    .filter(Boolean)
    .sort();
}

/**
 * Classify MANY paths in ONE bash process and return path -> playground verdict.
 *
 * Per-file classification is the only honest closure check: the classifier ORs
 * its outputs, so one selecting file in a batch would mask every other. One
 * `bash -c` driver looping over "$@" costs ~3s where a `bash -lc` per file costs
 * ~56s. `set -euo pipefail` keeps a classifier failure fatal instead of being
 * swallowed by the `sed` that extracts the field.
 */
function classifyPlayground(files) {
  const driver = [
    'set -euo pipefail',
    'for f in "$@"; do',
    '  printf "%s\\t" "$f"',
    `  ${SURFACES_SCRIPT} "$f" | sed -n "s/^playground=//p"`,
    'done',
  ].join('\n');
  const out = execFileSync('bash', ['-c', driver, '_', ...files], {
    cwd: REPO_ROOT,
    env: cleanEnv(),
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });
  const verdicts = new Map();
  for (const line of out.trim().split(/\r?\n/).filter(Boolean)) {
    const [file, verdict] = line.split('\t');
    verdicts.set(file, verdict);
  }
  return verdicts;
}

// --- hermetic digest fixture -------------------------------------------------
//
// A throwaway git repo seeded with one tracked file per ROSTER entry. The digest
// module derives its repo root from process.cwd(), so the real CLI runs the real
// algorithm against a tree we can mutate freely.

function seedPathFor(entry) {
  // A concrete file entry has an extension; a directory entry gets a probe file.
  return path.basename(entry).includes('.') ? entry : `${entry}/probe.txt`;
}

function seedContentFor(entry) {
  return `seed ${entry}\n`;
}

function writeIn(root, relPath, contents) {
  const abs = path.join(root, relPath);
  fs.mkdirSync(path.dirname(abs), { recursive: true });
  fs.writeFileSync(abs, contents);
}

function gitIn(cwd, ...args) {
  return execFileSync('git', args, {
    cwd,
    env: cleanEnv(),
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
}

function makeFixture(roster) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'rf2-sci-inputs-'));
  gitIn(root, 'init', '-q');
  gitIn(root, 'config', 'user.email', 'ci@example.com');
  gitIn(root, 'config', 'user.name', 'CI');
  gitIn(root, 'config', 'commit.gpgsign', 'false');
  gitIn(root, 'config', 'core.autocrlf', 'false');
  for (const entry of roster) {
    writeIn(root, seedPathFor(entry), seedContentFor(entry));
  }
  gitIn(root, 'add', '-A');
  gitIn(root, 'commit', '-q', '-m', 'seed roster');
  return root;
}

function dropFixture(root) {
  try {
    fs.rmSync(root, { recursive: true, force: true });
  } catch {
    // Windows can transiently hold a lock on the scratch .git; the OS temp dir
    // is reclaimed anyway. A cleanup failure must not fail the test.
  }
}

function digestIn(cwd) {
  return execFileSync('node', [DIGEST_SCRIPT], {
    cwd,
    env: cleanEnv(),
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim();
}

async function main() {
  const { ROSTER } = await import(pathToFileURL(DIGEST_SCRIPT).href);

  assert.ok(Array.isArray(ROSTER) && ROSTER.length > 0, 'ROSTER must be a non-empty array');

  // The classifier's playground arms are directory globs, so the tracked files
  // stand for any file later added beside them.
  test('every ROSTER input selects the playground changed surface (derived closure)', () => {
    const perEntry = ROSTER.map((entry) => [entry, trackedFiles(entry)]);
    const verdicts = classifyPlayground([...new Set(perEntry.flatMap(([, files]) => files))]);
    const misses = [];
    for (const [entry, files] of perEntry) {
      // An entry expanding to nothing would make its row trivially green.
      if (files.length === 0) misses.push(`${entry} matched no tracked files`);
      for (const f of files) {
        if (verdicts.get(f) !== 'true') misses.push(`${entry} :: ${f} -> ${verdicts.get(f)}`);
      }
    }
    assert.deepEqual(
      misses,
      [],
      'these declared bundle inputs do NOT fire the playground job — the digest ' +
        'would claim them as inputs while nothing proves the bundle still builds:\n' +
        misses.join('\n'),
    );
  });

  test('a ROSTER entry moves the digest, and restoring it restores the digest', () => {
    const root = makeFixture(ROSTER);
    try {
      const base = digestIn(root);
      const rel = seedPathFor(ROSTER[0]);
      writeIn(root, rel, `mutated ${ROSTER[0]}\n`);
      assert.notEqual(digestIn(root), base, `${ROSTER[0]} does not affect the digest`);
      writeIn(root, rel, seedContentFor(ROSTER[0]));
      // A digest that cannot come back is a ratchet, not a fingerprint.
      assert.equal(digestIn(root), base, 'restoring the entry must restore the digest');
    } finally {
      dropFixture(root);
    }
  });

  // --- run -------------------------------------------------------------------

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
    console.error(`playground-sci-inputs tests: ${failed} failed.`);
    process.exit(1);
  }
  console.log(`playground-sci-inputs tests: ${tests.length} passed.`);
}

main().catch((err) => {
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
