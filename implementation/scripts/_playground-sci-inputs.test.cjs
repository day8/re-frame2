#!/usr/bin/env node

'use strict';

/**
 * _playground-sci-inputs.test.cjs — close the Playground SCI input authority
 * over its REAL inputs and firing surfaces (rf2-nyjml).
 *
 * WHAT THIS GUARDS. `scripts/playground-sci-input-digest.mjs` declares ROSTER:
 * the source/config/lock inputs the shadow-cljs :advanced SCI bundle bakes in.
 * Two independent things must stay true of that declaration, and neither was
 * covered anywhere before this file (`_changed-surfaces.test.cjs`, 1964 lines,
 * did not mention `playground` once):
 *
 *   1. CLOSURE. Every declared input must select the `playground` changed
 *      surface, so a PR touching a baked-in input actually rebuilds and renders
 *      the bundle. If an input is in the digest but not in the firing surface,
 *      that input can change with no proof the bundle still builds.
 *   2. SENSITIVITY. Every declared input must individually move the digest, and
 *      every entry must keep matching tracked files. A roster entry that matched
 *      nothing used to pass silently (see the vacuity arm below).
 *
 * NOTE ON SCOPE. rf2-tzy13 untracked docs/cljs/playground-rf2.js — it is
 * generated at each consumption boundary, so there is no committed snapshot to
 * go stale and the old freshness verifier was deleted with it. The digest is now
 * build PROVENANCE, and the `playground` job (build + live render under headless
 * Chromium) is the proof. This file therefore checks that the provenance claim
 * is honest and that the job fires for everything the claim covers; it does NOT
 * resurrect a committed-artefact comparison.
 *
 * DERIVED, NOT RE-DECLARED. Every arm below expands the REAL ROSTER rather than
 * carrying a second hardcoded list, so a roster edit is checked automatically
 * instead of drifting away from a copy. Wired into `test:script-policy`.
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
const notes = [];

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
 * its outputs, so handing it the whole list at once would let a single selecting
 * file mask every other. But a `bash -lc` per file sources the login profile and
 * cost 56s for 144 files; one `bash -c` driver looping over "$@" is ~3s for the
 * same work. `set -euo pipefail` keeps a classifier failure fatal instead of
 * being swallowed by the `sed` that extracts the field.
 */
function classifyPlayground(files) {
  assert.ok(files.length > 0, 'classifyPlayground: empty input — the probe would prove nothing');
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
  assert.equal(
    verdicts.size,
    files.length,
    `classifier returned ${verdicts.size} verdicts for ${files.length} paths`,
  );
  return verdicts;
}

// --- hermetic digest fixture -------------------------------------------------
//
// A throwaway git repo seeded with one tracked file per ROSTER entry. The digest
// module derives its repo root from process.cwd(), so running the real CLI with
// cwd set here exercises the real algorithm against a tree we can mutate freely
// — no writes to the working checkout, and the fixture SHAPE is derived from
// ROSTER so a new entry is seeded automatically.

function seedPathFor(entry) {
  // Roster entries are either a concrete file (basename carries an extension)
  // or a directory pathspec that expands to everything tracked beneath it.
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

function digestAttempt(cwd) {
  try {
    return { ok: true, digest: digestIn(cwd) };
  } catch (err) {
    return { ok: false, status: err.status, stderr: String((err && err.stderr) || '') };
  }
}

async function main() {
  const { ROSTER } = await import(pathToFileURL(DIGEST_SCRIPT).href);

  assert.ok(Array.isArray(ROSTER) && ROSTER.length > 0, 'ROSTER must be a non-empty array');

  // --- ARM 1: firing-surface closure, derived from the real roster -----------

  test('every ROSTER input selects the playground changed surface (derived closure)', () => {
    const perEntry = ROSTER.map((entry) => [entry, trackedFiles(entry)]);

    // Non-vacuity of the PROBE itself: an entry expanding to nothing would make
    // its row trivially green, which is the "0/5 PASS" shape this arm exists to
    // refuse. Assert before classifying, so the failure names the entry.
    for (const [entry, files] of perEntry) {
      assert.ok(files.length > 0, `roster entry matched no tracked files: ${entry}`);
    }

    const all = [...new Set(perEntry.flatMap(([, files]) => files))].sort();
    assert.ok(all.length > 0, 'roster expanded to zero files — this arm would prove nothing');

    const verdicts = classifyPlayground(all);
    const misses = [];
    const rows = [];
    for (const [entry, files] of perEntry) {
      const selecting = files.filter((f) => verdicts.get(f) === 'true');
      for (const f of files) {
        if (verdicts.get(f) !== 'true') misses.push(`${entry} :: ${f} -> ${verdicts.get(f)}`);
      }
      rows.push(`    ${String(selecting.length).padStart(3)}/${String(files.length).padEnd(3)} ${entry}`);
    }
    notes.push(`  playground closure, per roster entry (selecting/total):\n${rows.join('\n')}`);
    assert.deepEqual(
      misses,
      [],
      'these declared bundle inputs do NOT fire the playground job — the digest ' +
        'would claim them as inputs while nothing proves the bundle still builds:\n' +
        misses.join('\n'),
    );
  });

  // --- ARM 2: table-driven per-class firing ---------------------------------
  //
  // The closure arm proves today's TRACKED files fire. This arm proves the CASE
  // PATTERNS do, using paths that do not exist yet: a newly added source file in
  // any baked-in tree must select the job on the PR that adds it. The coverage
  // assertion below ties the table back to ROSTER so it cannot rot into a stale
  // hardcoded list.

  const CLASS_TABLE = [
    ['core source', 'implementation/core/src/re_frame/newly_added.cljc'],
    ['core deps', 'implementation/core/deps.edn'],
    ['reagent-slim source', 'implementation/adapters/reagent-slim/src/reagent2/newly_added.cljs'],
    ['reagent-slim deps', 'implementation/adapters/reagent-slim/deps.edn'],
    ['machines source', 'implementation/machines/src/re_frame/newly_added.cljc'],
    ['machines deps', 'implementation/machines/deps.edn'],
    ['flows source', 'implementation/flows/src/re_frame/newly_added.cljc'],
    ['flows deps', 'implementation/flows/deps.edn'],
    ['sci bundle source', 'docs/tools/playground/sci/src/rf2_playground/newly_added.cljs'],
    ['sci shadow config', 'docs/tools/playground/sci/shadow-cljs.edn'],
    ['sci deps', 'docs/tools/playground/sci/deps.edn'],
    ['sci npm manifest', 'docs/tools/playground/sci/package.json'],
    ['sci npm lock', 'docs/tools/playground/sci/package-lock.json'],
    ['bundle postprocess', 'docs/tools/playground/sci/scripts/copy-bundle.mjs'],
    ['digest algorithm', 'scripts/playground-sci-input-digest.mjs'],
  ];

  test('the per-class table covers every ROSTER entry (table cannot rot)', () => {
    const uncovered = ROSTER.filter(
      (entry) => !CLASS_TABLE.some(([, p]) => p === entry || p.startsWith(`${entry}/`)),
    );
    assert.deepEqual(
      uncovered,
      [],
      `ROSTER entries with no row in CLASS_TABLE: ${uncovered.join(', ')}`,
    );
  });

  test('every baked-in input class selects playground, including not-yet-existing files', () => {
    const verdicts = classifyPlayground(CLASS_TABLE.map(([, p]) => p));
    const misses = CLASS_TABLE.filter(([, p]) => verdicts.get(p) !== 'true').map(
      ([label, p]) => `${label} (${p}) -> ${verdicts.get(p)}`,
    );
    notes.push(`  per-class firing: ${CLASS_TABLE.length - misses.length}/${CLASS_TABLE.length} classes select playground`);
    assert.deepEqual(misses, [], `input classes not selecting playground:\n${misses.join('\n')}`);
  });

  // --- ARM 3: digest sensitivity, per entry (per-arm levers) -----------------

  test('every ROSTER entry is individually load-bearing in the digest', () => {
    const root = makeFixture(ROSTER);
    try {
      const base = digestIn(root);
      assert.match(base, /^[0-9a-f]{64}$/, 'baseline digest must be 64 hex');

      const inert = [];
      const perEntryDigest = new Map();
      for (const entry of ROSTER) {
        const rel = seedPathFor(entry);
        // One lever at a time: a blanket mutation could red some entries while
        // leaving others green and still look like a proof.
        writeIn(root, rel, `mutated ${entry}\n`);
        const moved = digestIn(root);
        if (moved === base) inert.push(entry);
        perEntryDigest.set(entry, moved);
        writeIn(root, rel, seedContentFor(entry));
        assert.equal(
          digestIn(root),
          base,
          `restoring ${entry} must restore the digest — a digest that cannot come ` +
            'back is a ratchet, not a fingerprint',
        );
      }

      assert.deepEqual(
        inert,
        [],
        `these ROSTER entries do not affect the digest at all: ${inert.join(', ')}`,
      );
      assert.equal(
        new Set(perEntryDigest.values()).size,
        ROSTER.length,
        'each entry must yield a DISTINCT digest — collisions mean the digest is ' +
          'not resolving which input moved',
      );
      notes.push(
        `  digest sensitivity: ${ROSTER.length}/${ROSTER.length} roster entries move the digest, all distinct`,
      );
    } finally {
      dropFixture(root);
    }
  });

  // --- ARM 4: the sequence (state, not just cases) ---------------------------

  test('digest sequence: change REDS, refresh clears, a DIFFERENT change REDS again', () => {
    const root = makeFixture(ROSTER);
    try {
      const first = ROSTER[0];
      const second = ROSTER[ROSTER.length - 1];
      assert.notEqual(first, second, 'sequence needs two distinct entries');

      const fresh = digestIn(root);

      // Round 1 — an input changes, the digest must move with it.
      writeIn(root, seedPathFor(first), 'round 1 mutation\n');
      const stale1 = digestIn(root);
      assert.notEqual(stale1, fresh, 'round 1: a changed input must move the digest');

      // Refresh — the tree returns to its declared state.
      writeIn(root, seedPathFor(first), seedContentFor(first));
      assert.equal(digestIn(root), fresh, 'refresh must return to the fresh digest');

      // Round 2 — a DIFFERENT input this time. A gate that latched on round 1,
      // or that only ever compares against a fixed ceiling, passes round 1 and
      // fails here.
      writeIn(root, seedPathFor(second), 'round 2 mutation\n');
      const stale2 = digestIn(root);
      assert.notEqual(stale2, fresh, 'round 2: a different changed input must move the digest');
      assert.notEqual(stale2, stale1, 'round 2 must differ from round 1 — not a latched value');

      // Refresh again — still reversible after two rounds.
      writeIn(root, seedPathFor(second), seedContentFor(second));
      assert.equal(digestIn(root), fresh, 'second refresh must return to the fresh digest');
      notes.push('  sequence: fresh -> stale -> refresh -> stale(different) -> refresh, all 5 states verified');
    } finally {
      dropFixture(root);
    }
  });

  // --- ARM 5: per-entry vacuity (the drift this bead found) ------------------
  //
  // Before rf2-nyjml the guard expanded the whole roster as ONE pathspec set and
  // only failed when the UNION was empty. Renaming implementation/flows/src left
  // 140 of 144 files, so the guard stayed silent while an entire input class had
  // left the digest. These teeth use a SUFFIX rename (src -> src_renamed)
  // deliberately: git pathspecs match at directory boundaries, so this really
  // does drop the entry — a rename that merely CONTAINED the old name would be a
  // passenger tooth that proved nothing.

  test('a single drifted roster entry REDS the digest (suffix rename, per entry)', () => {
    const dirEntries = ROSTER.filter((e) => !path.basename(e).includes('.'));
    assert.ok(dirEntries.length >= 2, 'need at least two directory entries to vary the lever');

    // A different violating entry each round, to rule out a guard that only ever
    // notices one hardcoded path.
    for (const entry of [dirEntries[0], dirEntries[dirEntries.length - 1]]) {
      const root = makeFixture(ROSTER);
      try {
        const fresh = digestAttempt(root);
        assert.equal(fresh.ok, true, `fixture must start green (${entry})`);

        fs.renameSync(path.join(root, entry), path.join(root, `${entry}_renamed`));
        gitIn(root, 'add', '-A');
        gitIn(root, 'commit', '-q', '-m', `drift ${entry}`);

        const drifted = digestAttempt(root);
        assert.equal(drifted.ok, false, `a vanished roster entry must RED: ${entry}`);
        assert.notEqual(drifted.status, 0, 'a drifted roster must exit non-zero');
        assert.match(
          drifted.stderr,
          /roster matched no tracked files/,
          'the original drift message must be preserved',
        );
        assert.ok(
          drifted.stderr.includes(entry),
          `the failure must name the drifted entry (${entry}); got:\n${drifted.stderr}`,
        );
      } finally {
        dropFixture(root);
      }
    }
    notes.push('  vacuity: 2 distinct roster entries independently RED on a suffix rename');
  });

  test('a drifted FILE entry REDS too (not just directory entries)', () => {
    const fileEntry = ROSTER.find((e) => path.basename(e).includes('.'));
    assert.ok(fileEntry, 'roster must contain at least one concrete file entry');
    const root = makeFixture(ROSTER);
    try {
      assert.equal(digestAttempt(root).ok, true, 'fixture must start green');
      gitIn(root, 'rm', '-q', '--', fileEntry);
      gitIn(root, 'commit', '-q', '-m', `drop ${fileEntry}`);
      const dropped = digestAttempt(root);
      assert.equal(dropped.ok, false, `a removed file entry must RED: ${fileEntry}`);
      assert.ok(
        dropped.stderr.includes(fileEntry),
        `the failure must name the dropped entry (${fileEntry}); got:\n${dropped.stderr}`,
      );
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

  for (const note of notes) console.log(note);

  if (failed > 0) {
    console.error(`playground-sci-inputs tests: ${failed} failed.`);
    process.exit(1);
  }
  assert.ok(tests.length > 0, 'suite selected no tests');
  console.log(`playground-sci-inputs tests: ${tests.length} passed.`);
}

main().catch((err) => {
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
